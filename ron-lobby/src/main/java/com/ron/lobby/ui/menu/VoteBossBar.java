package com.ron.lobby.ui.menu;

import com.ron.lobby.RonLobby;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Per-target BossBar showing a live countdown. Polls a supplier each second so
 * the timer source stays in MatchQueue / VoteSession. Used for both the vote
 * window and the queue-fill window.
 */
public final class VoteBossBar {

    private static final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private static int taskId = -1;
    private static int totalSeconds = 1;
    private static IntSupplier remainingSupplier;
    private static String label = "";

    private VoteBossBar() {}

    public static void startVote(Set<UUID> voters, int total, IntSupplier remaining) {
        start(voters, "Vote", total, remaining);
    }

    public static void startQueue(Set<UUID> queue, int total, IntSupplier remaining) {
        start(queue, "Queue locks in", total, remaining);
    }

    private static void start(Set<UUID> targets, String labelText, int total, IntSupplier remaining) {
        stopAll();
        totalSeconds = Math.max(1, total);
        remainingSupplier = remaining;
        label = labelText;

        for (UUID uuid : targets) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            BossBar bar = Bukkit.createBossBar(label + ": " + total + "s", BarColor.GREEN, BarStyle.SEGMENTED_10);
            bar.setProgress(1.0);
            bar.addPlayer(p);
            bars.put(uuid, bar);
        }

        if (bars.isEmpty()) return;

        taskId = new BukkitRunnable() {
            @Override
            public void run() {
                int rem = remainingSupplier == null ? 0 : remainingSupplier.getAsInt();
                if (rem <= 0) {
                    stopAll();
                    return;
                }
                double progress = Math.max(0.0, Math.min(1.0, rem / (double) totalSeconds));
                BarColor color = rem <= 10 ? BarColor.RED : rem <= 30 ? BarColor.YELLOW : BarColor.GREEN;
                for (BossBar bar : bars.values()) {
                    bar.setTitle(label + ": " + rem + "s");
                    bar.setProgress(progress);
                    bar.setColor(color);
                }
                if (rem >= 1 && rem <= 5) {
                    SoundEffects.countdownTick(bars.keySet());
                }
            }
        }.runTaskTimer(RonLobby.INSTANCE, 20L, 20L).getTaskId();
    }

    public static void remove(UUID uuid) {
        BossBar bar = bars.remove(uuid);
        if (bar != null) bar.removeAll();
    }

    public static void stopAll() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        remainingSupplier = null;
    }
}
