package com.ron.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.ron.common.db.Database;
import com.ron.common.db.MatchDAO;
import com.ron.common.db.PlayerStatsDAO;
import com.ron.common.messaging.MessageProtocol;
import com.ron.proxy.sync.RankSyncConfig;
import com.ron.proxy.sync.RankSyncService;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
    id = "ron-proxy",
    name = "RoN Proxy",
    version = "1.0.0",
    description = "Proxy plugin for the Reign of Nether community server system",
    authors = {"Soly"}
)
public class RonProxy {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final InstanceTracker instanceTracker;
    private final PlayerRouter playerRouter;
    private final ActiveMatchTracker activeMatchTracker;
    private final MessageHandler messageHandler;
    private final ReconnectHandler reconnectHandler;
    private Database database;
    private PlayerStatsDAO statsDAO;
    private MatchDAO matchDAO;
    private MatchService matchService;
    private QueueMirror queueMirror;
    private RankSyncService rankSyncService;
    private boolean rankedEnabled = true;

    public static final Gson GSON = new Gson();

    public static final MinecraftChannelIdentifier TRANSFER_CHANNEL =
            MinecraftChannelIdentifier.from(MessageProtocol.Channels.TRANSFER);
    public static final MinecraftChannelIdentifier MATCH_CHANNEL =
            MinecraftChannelIdentifier.from(MessageProtocol.Channels.MATCH);

    @Inject
    public RonProxy(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.instanceTracker = new InstanceTracker(server, logger);
        this.playerRouter = new PlayerRouter(server, logger);
        this.playerRouter.setPlugin(this);
        this.activeMatchTracker = new ActiveMatchTracker(logger);
        this.messageHandler = new MessageHandler(server, logger, instanceTracker, playerRouter, activeMatchTracker);
        this.messageHandler.setPlugin(this);
        this.reconnectHandler = new ReconnectHandler(server, logger, this, activeMatchTracker, instanceTracker, playerRouter);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        instanceTracker.setMessageHandler(messageHandler);
        instanceTracker.setPlayerRouter(playerRouter);
        instanceTracker.setActiveMatchTracker(activeMatchTracker);
        if (statsDAO != null) {
            instanceTracker.setStatsDAO(statsDAO);
            messageHandler.setStatsDAO(statsDAO);
        }
        if (rankSyncService != null) {
            instanceTracker.setRankSyncService(rankSyncService);
        }
        matchService = new MatchService(logger, matchDAO);
        matchService.setRankedEnabled(rankedEnabled);
        instanceTracker.setRankedEnabled(rankedEnabled);
        instanceTracker.setMatchService(matchService);
        messageHandler.setMatchService(matchService);
        queueMirror = new QueueMirror();
        messageHandler.setQueueMirror(queueMirror);
        instanceTracker.setQueueMirror(queueMirror);
        rehydrateMatches();
        server.getChannelRegistrar().register(TRANSFER_CHANNEL, MATCH_CHANNEL);
        server.getEventManager().register(this, messageHandler);
        server.getEventManager().register(this, reconnectHandler);
        server.getCommandManager().register("rejoin", new RejoinCommand(
                server, logger, this, activeMatchTracker, instanceTracker, playerRouter));
        instanceTracker.startPolling();
        logger.info("RoN Proxy initialized");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        instanceTracker.shutdown();
        if (rankSyncService != null) {
            rankSyncService.shutdown();
        }
        logger.info("RoN Proxy shut down");
    }

    public RankSyncService getRankSyncService() {
        return rankSyncService;
    }

    private void rehydrateMatches() {
        if (matchDAO == null) return;
        try {
            var unfinished = matchDAO.findUnfinished();
            if (unfinished.isEmpty()) return;
            for (var match : unfinished) {
                matchService.rehydrate(match);
                logger.info("Rehydrated match {} on {} (state={})",
                        match.id(), match.instance(), match.state());
            }
            logger.info("Rehydrated {} unfinished matches — reconciliation will run on first poll", unfinished.size());
        } catch (Exception e) {
            logger.error("Failed to rehydrate matches from DB", e);
        }
    }

    private void loadConfig() {
        Path configFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(configFile)) {
            Path legacyJson = dataDirectory.resolve("config.json");
            if (Files.exists(legacyJson)) {
                migrateLegacyJson(legacyJson, configFile);
            } else {
                createDefaultConfig(configFile);
            }
        }

        try {
            String content = Files.readString(configFile);
            // YAML is a superset of JSON; SnakeYAML parses it to plain Maps/Lists,
            // which Gson re-wraps as a JsonObject so the existing per-section parsers
            // keep working unchanged.
            Object parsed = new Yaml().load(content);
            JsonObject config = parsed == null ? new JsonObject() : GSON.toJsonTree(parsed).getAsJsonObject();

            ProxySettings.load(config);
            initDatabase(config);
            initRanked(config);
            initRankSync(config);
            applyModeFilter(config);
            registerInstances(config);
        } catch (IOException e) {
            logger.error("Failed to load config", e);
        }
    }

    /** Copy the bundled, commented default config.yml to the data directory on first run. */
    private void createDefaultConfig(Path configFile) {
        try {
            Files.createDirectories(dataDirectory);
            try (java.io.InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in == null) {
                    logger.error("Bundled config.yml resource missing — cannot create default config");
                    return;
                }
                Files.copy(in, configFile);
            }
            logger.info("Created default config at {}", configFile);
            logger.warn("Default RCON passwords are 'changeme' — edit config.yml before exposing this proxy");
        } catch (IOException e) {
            logger.error("Failed to create default config", e);
        }
    }

    /** One-time upgrade: convert a legacy config.json into config.yml, preserving all values. */
    private void migrateLegacyJson(Path legacyJson, Path configFile) {
        try {
            // JSON is valid YAML — load it as a plain object and dump it back in block style.
            Object data = new Yaml().load(Files.readString(legacyJson));
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);
            Files.writeString(configFile, new Yaml(opts).dump(data));
            logger.warn("Migrated legacy config.json to config.yml — review it (comments were not carried over) and delete config.json");
        } catch (IOException e) {
            logger.error("Failed to migrate config.json to config.yml", e);
        }
    }

    private void initDatabase(JsonObject config) {
        String dbPath = "ron.db";
        if (config.has("database") && config.getAsJsonObject("database").has("path")) {
            dbPath = config.getAsJsonObject("database").get("path").getAsString();
        }
        Path dbFile = dataDirectory.resolve(dbPath);
        try {
            database = new Database(dbFile.toString());
            database.initialize();
            statsDAO = new PlayerStatsDAO(database);
            matchDAO = new MatchDAO(database);
            logger.info("Database initialized at {}", dbFile);
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
        }
    }

    /**
     * Network-wide ranked switch. When disabled, every match is unranked and the lobby
     * hides ranked UI. Absent block defaults to enabled (documented in the shipped config.yml).
     */
    private void initRanked(JsonObject config) {
        if (config.has("ranked") && config.getAsJsonObject("ranked").has("enabled")) {
            rankedEnabled = config.getAsJsonObject("ranked").get("enabled").getAsBoolean();
        }
        logger.info("Ranked system {} network-wide", rankedEnabled ? "ENABLED" : "DISABLED");
    }

    private void initRankSync(JsonObject config) {
        if (!rankedEnabled) {
            logger.info("Ranked disabled — skipping rank sync service");
            return;
        }
        RankSyncConfig syncConfig = RankSyncConfig.fromJson(config);
        if (syncConfig.enabled && statsDAO != null) {
            rankSyncService = new RankSyncService(syncConfig, statsDAO, logger);
            rankSyncService.start();
        }
    }

    /**
     * Apply the network-wide mode switchboard. Called before instances register so the
     * filter is in place before the first poll fetches their maps. An absent block means
     * "all modes enabled" — the supported names are logged so operators can restrict them.
     */
    private void applyModeFilter(JsonObject config) {
        ModeFilterConfig modeFilter = ModeFilterConfig.fromJson(config);
        instanceTracker.setEnabledModes(modeFilter.enabledModes());
        if (!modeFilter.present()) {
            logger.info("No 'gameModes' filter set — all modes enabled. Supported modes: {}",
                    com.ron.common.modes.ModeCatalog.namesHint());
        } else {
            logger.info("Mode switchboard: {} modes enabled network-wide", modeFilter.enabledModes().size());
        }
    }

    private void registerInstances(JsonObject config) {
        if (!config.has("instances")) return;
        for (var entry : config.getAsJsonObject("instances").entrySet()) {
            JsonObject inst = entry.getValue().getAsJsonObject();
            if (!inst.has("rconHost") || !inst.has("rconPort") || !inst.has("rconPassword")) {
                logger.warn("Instance '{}' missing required fields (rconHost, rconPort, rconPassword) — skipping", entry.getKey());
                continue;
            }
            instanceTracker.addInstance(
                entry.getKey(),
                inst.get("rconHost").getAsString(),
                inst.get("rconPort").getAsInt(),
                inst.get("rconPassword").getAsString()
            );
            logger.info("Configured instance: {} ({}:{})",
                entry.getKey(), inst.get("rconHost").getAsString(), inst.get("rconPort").getAsInt());
        }
    }
}
