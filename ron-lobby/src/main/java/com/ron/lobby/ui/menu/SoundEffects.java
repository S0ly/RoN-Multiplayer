package com.ron.lobby.ui.menu;

import com.ron.lobby.queue.MatchQueue;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/**
 * Tiny audio cues for queue + vote events. Every call no-ops if {@code ui.sounds}
 * is false so the lobby stays silent on demand.
 */
public final class SoundEffects {

    private SoundEffects() {}

    private static boolean enabled() {
        return MatchQueue.uiSettings().sounds();
    }

    private static void play(Player p, Sound s, float pitch) {
        if (p != null) p.playSound(p.getLocation(), s, 0.6f, pitch);
    }

    private static void playAll(Set<UUID> targets, Sound s, float pitch) {
        for (UUID uuid : targets) play(Bukkit.getPlayer(uuid), s, pitch);
    }

    public static void queueJoin(Player p) {
        if (enabled()) play(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
    }

    public static void queueLeave(Player p) {
        if (enabled()) play(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f);
    }

    public static void voteStart(Set<UUID> voters) {
        if (enabled()) playAll(voters, Sound.BLOCK_BELL_USE, 1.0f);
    }

    public static void voteCast(Player p) {
        if (enabled()) play(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);
    }

    public static void countdownTick(Set<UUID> targets) {
        if (enabled()) playAll(targets, Sound.UI_BUTTON_CLICK, 1.2f);
    }

    public static void countdownFinal(Set<UUID> targets) {
        if (enabled()) playAll(targets, Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f);
    }

    public static void matchFound(Set<UUID> targets) {
        if (enabled()) playAll(targets, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
    }
}
