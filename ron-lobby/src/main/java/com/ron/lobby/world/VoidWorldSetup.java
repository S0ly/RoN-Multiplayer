package com.ron.lobby.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;

public class VoidWorldSetup implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Spectator mode — hides body, hotbar, and inventory
        player.setGameMode(GameMode.SPECTATOR);

        // Welcome screen with running matches
        if (RonLobby.INSTANCE.getConfig().getBoolean("show-welcome-message", true)) {
            LobbyMessaging.requestServerInfo();
            Bukkit.getScheduler().runTaskLater(RonLobby.INSTANCE, () -> {
                LobbyUI.refreshScreen(player);
                showRunningMatches(player);
            }, 20L); // 1 second delay to allow server info response
        }
    }

    public static void showRunningMatches(Player player) {
        JsonObject info = LobbyMessaging.getLastServerInfo();
        if (info == null) return;

        if (!info.has("availableInstances")) return;
        int available = info.get("availableInstances").getAsInt();

        if (info.has("matches") && info.getAsJsonObject("matches").size() > 0) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "Running Matches:");
            for (Map.Entry<String, JsonElement> entry : info.getAsJsonObject("matches").entrySet()) {
                JsonObject match = entry.getValue().getAsJsonObject();
                String instanceName = entry.getKey();
                String map = match.get("map").getAsString();
                int players = match.get("players").getAsInt();
                long seconds = match.get("elapsedSeconds").getAsLong();
                String time = String.format("%d:%02d", seconds / 60, seconds % 60);

                player.sendMessage(ChatColor.YELLOW + "  " + instanceName + ChatColor.WHITE +
                        " | " + map + " | " + players + " players | " + time);
            }
        } else {
            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + "No matches currently running.");
        }

        player.sendMessage(ChatColor.WHITE + "Available servers: " + ChatColor.GREEN + available);
        player.sendMessage(ChatColor.WHITE + "Queue: " + ChatColor.YELLOW + RonLobby.matchQueue.getPublicQueueSize() + " players");
        player.sendMessage("");
    }

}
