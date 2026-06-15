package com.ron.instance;

import com.ron.common.scoring.ScoringUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MatchEndHandler {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RoN-MatchEndHandler");
        t.setDaemon(true);
        return t;
    });
    private static MinecraftServer serverInstance;
    private static final AtomicBoolean matchEnded = new AtomicBoolean(false);

    /** Delay before flipping to FINISHED so players can read the end-of-match summary. */
    private static final int FINISHED_STATE_DELAY_SECONDS = 10;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        serverInstance = event.getServer();
        matchEnded.set(false);
    }

    public static boolean hasMatchEnded() {
        return matchEnded.get();
    }

    public static void resetMatchEnded() {
        matchEnded.set(false);
    }

    public static void onDraw(List<String> allPlayers) {
        if (!matchEnded.compareAndSet(false, true)) return;

        RonInstance.LOGGER.info("Draw — all players allied: {}", allPlayers);

        List<MatchResult.PlayerResult> drawResults = new ArrayList<>();
        for (String name : allPlayers) {
            String uuid = resolveUuid(name);
            if (uuid != null) {
                int score = MatchResult.getPlayerScore(uuid);
                drawResults.add(new MatchResult.PlayerResult(uuid, name, score, 0));
            }
        }

        MatchResult result = new MatchResult(drawResults, List.of());
        MatchResult.setCurrent(result);

        broadcast(Component.literal("=== Match Draw ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        broadcast(Component.literal("All players allied — no rating change.").withStyle(ChatFormatting.YELLOW));
        for (MatchResult.PlayerResult p : drawResults) {
            broadcast(Component.literal(p.name() + " — 0 points").withStyle(ChatFormatting.GRAY));
        }

        scheduler.schedule(() -> {
            RonInstance.LOGGER.info("Setting state to FINISHED after draw");
            InstanceStateManager.setState(InstanceState.FINISHED);
        }, FINISHED_STATE_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public static void onVictory(List<String> winners, List<String> losers) {
        if (!matchEnded.compareAndSet(false, true)) return;

        // Leavers who were on the winning side still get a loss
        Set<UUID> leavers = MatchLifecycle.getLeftPlayers();
        List<String> effectiveWinners = new ArrayList<>();
        List<String> effectiveLosers = new ArrayList<>(losers);

        for (String name : winners) {
            UUID uuid = PlayerTracker.getUuidByName(name);
            if (uuid != null && leavers.contains(uuid)) {
                effectiveLosers.add(name);
            } else {
                effectiveWinners.add(name);
            }
        }

        RonInstance.LOGGER.info("Victory — winners: {}, losers: {}", effectiveWinners, effectiveLosers);
        processVictory(effectiveWinners, effectiveLosers);
    }

    private static void processVictory(List<String> winners, List<String> losers) {
        boolean ranked = MatchLifecycle.isRanked();

        int winnerAvg = ranked ? getAverageScore(winners) : 0;
        int loserAvg = ranked ? getAverageScore(losers) : 0;

        List<MatchResult.PlayerResult> winnerResults = new ArrayList<>();
        List<MatchResult.PlayerResult> loserResults = new ArrayList<>();

        for (String name : winners) {
            String uuid = resolveUuid(name);
            if (uuid != null) {
                int score = MatchResult.getPlayerScore(uuid);
                int gained = ranked ? ScoringUtil.calculateWinPoints(score, loserAvg) : 0;
                winnerResults.add(new MatchResult.PlayerResult(uuid, name, score, gained));
            }
        }

        for (String name : losers) {
            String uuid = resolveUuid(name);
            if (uuid != null) {
                int score = MatchResult.getPlayerScore(uuid);
                int lost = ranked ? ScoringUtil.calculateLossPoints(score, winnerAvg) : 0;
                loserResults.add(new MatchResult.PlayerResult(uuid, name, score, -lost));
            }
        }

        MatchResult result = new MatchResult(winnerResults, loserResults);
        MatchResult.setCurrent(result);

        broadcastResults(result, ranked);

        scheduler.schedule(() -> {
            RonInstance.LOGGER.info("Setting state to FINISHED after match end");
            InstanceStateManager.setState(InstanceState.FINISHED);
        }, FINISHED_STATE_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static String resolveUuid(String name) {
        // Try online player first
        ServerPlayer sp = findPlayer(name);
        if (sp != null) return sp.getStringUUID();
        // Fall back to PlayerTracker for disconnected players
        UUID tracked = PlayerTracker.getUuidByName(name);
        return tracked != null ? tracked.toString() : null;
    }

    private static void broadcastResults(MatchResult result, boolean ranked) {
        if (serverInstance == null) return;

        broadcast(Component.literal("=== Match Over ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        if (!ranked) {
            broadcast(Component.literal("Unranked match — no rating changes.").withStyle(ChatFormatting.GRAY));
        }

        for (MatchResult.PlayerResult p : result.getWinners()) {
            if (ranked) {
                broadcast(Component.literal(p.name() + " — +" + p.pointsChange() + " points").withStyle(ChatFormatting.GREEN));
            } else {
                broadcast(Component.literal(p.name() + " — Winner").withStyle(ChatFormatting.GREEN));
            }
        }

        for (MatchResult.PlayerResult p : result.getLosers()) {
            if (ranked) {
                broadcast(Component.literal(p.name() + " — " + p.pointsChange() + " points").withStyle(ChatFormatting.RED));
            } else {
                broadcast(Component.literal(p.name() + " — Defeated").withStyle(ChatFormatting.RED));
            }
        }

        if (ranked) {
            for (MatchResult.PlayerResult p : result.getWinners()) {
                int newScore = p.pointsBefore() + p.pointsChange();
                String oldRank = ScoringUtil.getRank(p.pointsBefore());
                String newRank = ScoringUtil.getRank(newScore);
                if (!oldRank.equals(newRank)) {
                    broadcast(Component.literal(p.name() + " ranked up to " + newRank + "!").withStyle(ChatFormatting.AQUA));
                }
            }
            for (MatchResult.PlayerResult p : result.getLosers()) {
                int newScore = Math.max(0, p.pointsBefore() + p.pointsChange());
                String oldRank = ScoringUtil.getRank(p.pointsBefore());
                String newRank = ScoringUtil.getRank(newScore);
                if (!oldRank.equals(newRank)) {
                    broadcast(Component.literal(p.name() + " dropped to " + newRank).withStyle(ChatFormatting.GRAY));
                }
            }
        }
    }

    private static void broadcast(Component message) {
        if (serverInstance != null) {
            serverInstance.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private static int getAverageScore(List<String> playerNames) {
        if (playerNames.isEmpty()) return 0;
        int total = 0;
        int count = 0;
        for (String name : playerNames) {
            String uuid = resolveUuid(name);
            if (uuid != null) {
                total += MatchResult.getPlayerScore(uuid);
                count++;
            }
        }
        return count > 0 ? total / count : 0;
    }

    private static ServerPlayer findPlayer(String name) {
        if (serverInstance == null) return null;
        return serverInstance.getPlayerList().getPlayerByName(name);
    }
}
