package com.ron.lobby.queue;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/**
 * Chat helpers for queue / custom-lobby messages. Broadcasts are gated on the
 * chat-messages UI setting; direct {@link #tellPlayer} messages (errors, personal
 * cues) always go through.
 */
final class LobbyChat {

    private LobbyChat() {}

    static void tellPlayer(UUID uuid, String message) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) player.sendMessage(message);
    }

    static void broadcastToPlayers(Set<UUID> players, String message) {
        if (!MatchQueue.uiSettings().chatMessages()) return;
        for (UUID uuid : players) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(ChatColor.GREEN + "[RoN] " + message);
            }
        }
    }

    static void broadcastToLobby(CustomLobby lobby, String message) {
        broadcastToPlayers(lobby.players, "[Custom] " + message);
    }

    static void broadcastToAll(String message) {
        if (!MatchQueue.uiSettings().chatMessages()) return;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            player.sendMessage(message);
        }
    }
}
