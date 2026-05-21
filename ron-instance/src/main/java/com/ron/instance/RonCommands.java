package com.ron.instance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class RonCommands {

    private static final Gson gson = new Gson();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // /ron-maps — returns available maps as JSON with mode info
        dispatcher.register(Commands.literal("ron-maps").executes(context -> {
            JsonArray maps = new JsonArray();
            for (InstanceStateManager.MapInfo map : InstanceStateManager.getAvailableMaps()) {
                JsonObject m = new JsonObject();
                m.addProperty("folder", map.folder());
                m.addProperty("name", map.name());
                m.addProperty("defaultMode", map.defaultMode());

                JsonArray modesArr = new JsonArray();
                for (InstanceStateManager.ModeInfo mode : map.modes()) {
                    JsonObject modeObj = new JsonObject();
                    modeObj.addProperty("name", mode.name());
                    modeObj.addProperty("minPlayers", mode.minPlayers());
                    modeObj.addProperty("maxPlayers", mode.maxPlayers());
                    modeObj.addProperty("teamCount", mode.teamCount());
                    modesArr.add(modeObj);
                }
                m.add("modes", modesArr);
                maps.add(m);
            }
            context.getSource().sendSuccess(() -> Component.literal(gson.toJson(maps)), false);
            return 1;
        }));

        // /ron-status — returns current state as JSON (includes matchResult when FINISHED)
        dispatcher.register(Commands.literal("ron-status").executes(context -> {
            JsonObject status = new JsonObject();
            status.addProperty("state", InstanceStateManager.getState().name());
            status.addProperty("map", InstanceStateManager.getCurrentMap());

            String mode = InstanceStateManager.getCurrentMode();
            if (mode != null) {
                status.addProperty("mode", mode);
            }

            List<RTSPlayer> rtsPlayers = PlayerServerEvents.rtsPlayers;
            synchronized (rtsPlayers) {
                status.addProperty("rtsPlayers", rtsPlayers.size());
                JsonArray names = new JsonArray();
                for (RTSPlayer p : rtsPlayers) {
                    names.add(p.name);
                }
                status.add("playerNames", names);
            }
            status.addProperty("gameTicks", PlayerServerEvents.rtsGameTicks);
            status.addProperty("gameSeconds", PlayerServerEvents.rtsGameTicks / 20);

            int onlinePlayers = context.getSource().getServer().getPlayerCount();
            status.addProperty("onlinePlayers", onlinePlayers);
            status.addProperty("spectatorCount", Math.max(0, onlinePlayers - PlayerTracker.getParticipantCount()));

            status.addProperty("ranked", MatchLifecycle.isRanked());
            status.addProperty("privateMatch", MatchLifecycle.isPrivateMatch());

            // Include match results when finished
            MatchResult result = MatchResult.getCurrent();
            if (result != null && InstanceStateManager.getState() == InstanceState.FINISHED) {
                JsonObject matchResult = new JsonObject();

                JsonArray winnersArr = new JsonArray();
                for (MatchResult.PlayerResult p : result.getWinners()) {
                    JsonObject pj = new JsonObject();
                    pj.addProperty("uuid", p.uuid());
                    pj.addProperty("name", p.name());
                    pj.addProperty("pointsBefore", p.pointsBefore());
                    pj.addProperty("pointsChange", p.pointsChange());
                    winnersArr.add(pj);
                }
                matchResult.add("winners", winnersArr);

                JsonArray losersArr = new JsonArray();
                for (MatchResult.PlayerResult p : result.getLosers()) {
                    JsonObject pj = new JsonObject();
                    pj.addProperty("uuid", p.uuid());
                    pj.addProperty("name", p.name());
                    pj.addProperty("pointsBefore", p.pointsBefore());
                    pj.addProperty("pointsChange", p.pointsChange());
                    losersArr.add(pj);
                }
                matchResult.add("losers", losersArr);

                status.add("matchResult", matchResult);
            }

            context.getSource().sendSuccess(() -> Component.literal(gson.toJson(status)), false);
            return 1;
        }));

        // /ron-playerscores <json> — receive player scores from proxy
        dispatcher.register(Commands.literal("ron-playerscores")
            .then(Commands.argument("scores", StringArgumentType.greedyString())
                .executes(context -> {
                    String scoresJson = StringArgumentType.getString(context, "scores");
                    try {
                        Map<String, Integer> scores = gson.fromJson(scoresJson,
                                new TypeToken<Map<String, Integer>>(){}.getType());
                        MatchResult.getPlayerScores().putAll(scores);
                        RonInstance.LOGGER.info("Received player scores for {} players", scores.size());
                        context.getSource().sendSuccess(
                            () -> Component.literal("Loaded " + scores.size() + " player scores"), false);
                        return 1;
                    } catch (Exception e) {
                        RonInstance.LOGGER.error("Failed to parse player scores: {}", scoresJson, e);
                        context.getSource().sendFailure(Component.literal("Failed to parse scores"));
                        return 0;
                    }
                })
            )
        );

        // /ron-loadmap <mapname> — writes flag file and halts server
        dispatcher.register(Commands.literal("ron-loadmap")
            .then(Commands.argument("map", StringArgumentType.greedyString())
                .executes(context -> {
                    String mapName = StringArgumentType.getString(context, "map");

                    boolean found = InstanceStateManager.getAvailableMaps().stream()
                            .anyMatch(m -> m.folder().equals(mapName));
                    if (!found) {
                        context.getSource().sendFailure(Component.literal("Map not found: " + mapName));
                        return 0;
                    }

                    if (InstanceStateManager.getState() == InstanceState.RUNNING) {
                        context.getSource().sendFailure(Component.literal("Cannot load map while match is running"));
                        return 0;
                    }

                    InstanceStateManager.setState(InstanceState.PREPARING);

                    Path flagFile = Paths.get(".").toAbsolutePath().normalize().resolve("pending-map.txt");
                    try {
                        Files.writeString(flagFile, mapName);
                        RonInstance.LOGGER.info("Flag file written: {}", mapName);
                    } catch (IOException e) {
                        RonInstance.LOGGER.error("Failed to write flag file", e);
                        context.getSource().sendFailure(Component.literal("Failed to write flag file"));
                        return 0;
                    }

                    context.getSource().sendSuccess(
                        () -> Component.literal("Loading map: " + mapName + " — server restarting..."), true);

                    net.minecraft.server.MinecraftServer srv = context.getSource().getServer();
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {}
                        RonInstance.LOGGER.info("Stopping server for map swap");
                        srv.halt(false);
                    }).start();

                    return 1;
                })
            )
        );

        // /ron-setmode <mode> — set the game mode for this instance
        dispatcher.register(Commands.literal("ron-setmode")
            .then(Commands.argument("mode", StringArgumentType.greedyString())
                .executes(context -> {
                    String mode = StringArgumentType.getString(context, "mode");
                    String currentMap = InstanceStateManager.getCurrentMap();

                    // Validate mode exists for current map
                    boolean valid = false;
                    for (InstanceStateManager.MapInfo map : InstanceStateManager.getAvailableMaps()) {
                        if (map.folder().equals(currentMap) || map.name().equals(currentMap)) {
                            for (InstanceStateManager.ModeInfo m : map.modes()) {
                                if (m.name().equals(mode)) {
                                    valid = true;
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    if (!valid) {
                        RonInstance.LOGGER.warn("ron-setmode rejected: mode '{}' not valid for map '{}'", mode, currentMap);
                        context.getSource().sendFailure(
                            Component.literal("Invalid mode '" + mode + "' for map: " + currentMap));
                        return 0;
                    }

                    InstanceStateManager.setCurrentMode(mode);
                    context.getSource().sendSuccess(
                        () -> Component.literal("Mode set to: " + mode), true);
                    return 1;
                })
            )
        );

        // /ron-setprivate <true|false> — flag this match as private (unranked)
        dispatcher.register(Commands.literal("ron-setprivate")
            .then(Commands.argument("value", StringArgumentType.word())
                .executes(context -> {
                    String value = StringArgumentType.getString(context, "value");
                    boolean isPrivate = "true".equalsIgnoreCase(value) || "1".equals(value);
                    MatchLifecycle.setPrivateMatch(isPrivate);
                    context.getSource().sendSuccess(
                        () -> Component.literal("Private match: " + isPrivate), true);
                    return 1;
                })
            )
        );

        // /ron-setranked <true|false> — proxy-decided ranked flag (overrides local heuristic)
        dispatcher.register(Commands.literal("ron-setranked")
            .then(Commands.argument("value", StringArgumentType.word())
                .executes(context -> {
                    String value = StringArgumentType.getString(context, "value");
                    boolean ranked = "true".equalsIgnoreCase(value) || "1".equals(value);
                    MatchLifecycle.setRankedOverride(ranked);
                    context.getSource().sendSuccess(
                        () -> Component.literal("Ranked: " + ranked), true);
                    return 1;
                })
            )
        );

        // /ron-reset — reset instance to IDLE state (called by proxy after match)
        // Returns JSON: {"ok": true, "state": "IDLE"} on success, {"ok": false, "reason": "..."} on failure.
        dispatcher.register(Commands.literal("ron-reset").executes(context -> {
            if (InstanceStateManager.getState() == InstanceState.RUNNING) {
                JsonObject failure = new JsonObject();
                failure.addProperty("ok", false);
                failure.addProperty("reason", "Cannot reset during active match");
                failure.addProperty("state", InstanceStateManager.getState().name());
                context.getSource().sendSuccess(() -> Component.literal(gson.toJson(failure)), false);
                return 0;
            }

            RonInstance.LOGGER.info("Resetting instance to IDLE state");
            InstanceStateManager.setState(InstanceState.IDLE);
            InstanceStateManager.setCurrentMap("none");
            InstanceStateManager.setCurrentMode(null);
            MatchLifecycle.reset();

            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            ok.addProperty("state", InstanceStateManager.getState().name());
            ok.addProperty("map", InstanceStateManager.getCurrentMap());
            context.getSource().sendSuccess(() -> Component.literal(gson.toJson(ok)), true);
            return 1;
        }));

        RonInstance.LOGGER.info("RCON commands registered: ron-maps, ron-status, ron-playerscores, ron-loadmap, ron-setmode, ron-setprivate, ron-reset");
    }
}
