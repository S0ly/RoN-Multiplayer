package com.ron.lobby;

import com.ron.common.messaging.MessageProtocol.Channels;
import com.ron.lobby.commands.*;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.queue.MatchQueue;
import com.ron.lobby.ui.UiSettings;
import com.ron.lobby.ui.menu.ChatPrompt;
import com.ron.lobby.ui.menu.MenuListener;
import com.ron.lobby.ui.menu.MenuSupport;
import com.ron.lobby.world.VoidWorldSetup;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public class RonLobby extends JavaPlugin {

    public static RonLobby INSTANCE;
    public static MatchQueue matchQueue;

    /** Interval for sweeping expired messaging callbacks (60 seconds, in ticks). */
    private static final long CALLBACK_CLEANUP_INTERVAL_TICKS = 1200L;

    @Override
    public void onEnable() {
        INSTANCE = this;
        saveDefaultConfig();
        // Merge any new keys from the bundled config.yml into the user's file
        // so additions across plugin versions become editable on disk.
        getConfig().options().copyDefaults(true);
        saveConfig();

        // UI settings — drive chat/menu/HUD toggles
        UiSettings uiSettings = UiSettings.load(getConfig());
        MatchQueue.setUiSettings(uiSettings);
        MenuSupport.setSettings(uiSettings);

        matchQueue = new MatchQueue(this);
        matchQueue.configureTimings(
            getConfig().getInt("queue.fill-seconds", 120),
            getConfig().getInt("queue.vote-seconds", 60),
            getConfig().getInt("queue.min-players", 2),
            getConfig().getInt("queue.transfer-timeout-seconds", 120),
            getConfig().getInt("queue.vote-final-countdown-seconds", 10),
            getConfig().getInt("queue.vote-reminder-interval-seconds", 15)
        );
        matchQueue.setPublicQueueEnabled(getConfig().getBoolean("queue.public-enabled", true));
        LobbyMessaging.setCallbackTtlSeconds(getConfig().getInt("messaging.callback-ttl-seconds", 30));

        getServer().getMessenger().registerOutgoingPluginChannel(this, Channels.TRANSFER);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Channels.MATCH);
        LobbyMessaging listener = new LobbyMessaging();
        getServer().getMessenger().registerIncomingPluginChannel(this, Channels.MATCH, listener);

        getServer().getPluginManager().registerEvents(new VoidWorldSetup(), this);
        getServer().getPluginManager().registerEvents(matchQueue, this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new ChatPrompt(), this);

        CommandExecutor disabled = (sender, cmd, label, args) -> {
            sender.sendMessage(ChatColor.YELLOW + "[RoN] Chat commands are disabled. Use /menu.");
            return true;
        };
        boolean cc = uiSettings.chatCommands();
        getCommand("queue").setExecutor(cc ? new QueueCommand() : disabled);
        getCommand("leave").setExecutor(cc ? new LeaveCommand() : disabled);
        getCommand("spectate").setExecutor(cc ? new SpectateCommand() : disabled);
        getCommand("leaderboard").setExecutor(cc ? new LeaderboardCommand() : disabled);
        getCommand("rank").setExecutor(cc ? new RankCommand() : disabled);
        getCommand("vote").setExecutor(cc ? new VoteCommand() : disabled);
        getCommand("matches").setExecutor(cc ? new MatchesCommand() : disabled);
        getCommand("ronstatus").setExecutor(new StatusCommand()); // OP tool — always on
        getCommand("menu").setExecutor(new MenuCommand());

        getServer().getScheduler().runTaskTimer(this, LobbyMessaging::cleanupStaleCallbacks,
                CALLBACK_CLEANUP_INTERVAL_TICKS, CALLBACK_CLEANUP_INTERVAL_TICKS);

        getLogger().info("RoN Lobby enabled");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
    }
}
