package com.ron.lobby.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class StatusCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ron.status")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (sender instanceof Player player) {
            LobbyUI.refreshScreen(player);
        }

        sender.sendMessage(ChatColor.GRAY + "[RoN] Fetching server status...");

        LobbyMessaging.requestServerInfo(info -> {
            if (info == null) {
                sender.sendMessage(ChatColor.RED + "[RoN] No server info available yet. Try again in a moment.");
                return;
            }

            sender.sendMessage("");
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== RoN Server Status ===");
            sender.sendMessage("");

            if (!info.has("availableInstances") || !info.has("occupiedInstances") ||
                    !info.has("minPlayers") || !info.has("maxPlayers")) {
                sender.sendMessage(ChatColor.RED + "[RoN] Server info is incomplete. Try again later.");
                return;
            }
            int available = info.get("availableInstances").getAsInt();
            int occupied = info.get("occupiedInstances").getAsInt();
            sender.sendMessage(ChatColor.WHITE + "Instances: " +
                    ChatColor.GREEN + available + " available" +
                    ChatColor.WHITE + " / " +
                    ChatColor.RED + occupied + " in game");
            sender.sendMessage(ChatColor.WHITE + "Players: " +
                    info.get("minPlayers").getAsInt() + "-" + info.get("maxPlayers").getAsInt() + "p");

            sender.sendMessage(ChatColor.WHITE + "Queue: " + ChatColor.YELLOW + RonLobby.matchQueue.getPublicQueueSize() + " players");
            sender.sendMessage("");

            if (info.has("matches") && info.getAsJsonObject("matches").size() > 0) {
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Running Matches:");
                for (Map.Entry<String, JsonElement> entry : info.getAsJsonObject("matches").entrySet()) {
                    JsonObject match = entry.getValue().getAsJsonObject();
                    String instanceName = entry.getKey();
                    String map = match.get("map").getAsString();
                    int players = match.get("players").getAsInt();
                    long seconds = match.get("elapsedSeconds").getAsLong();
                    String time = String.format("%d:%02d", seconds / 60, seconds % 60);

                    StringBuilder playerList = new StringBuilder();
                    if (match.has("playerNames")) {
                        for (JsonElement el : match.getAsJsonArray("playerNames")) {
                            if (playerList.length() > 0) playerList.append(", ");
                            playerList.append(el.getAsString());
                        }
                    }

                    sender.sendMessage(ChatColor.YELLOW + "  " + instanceName + ChatColor.WHITE +
                            " | " + map + " | " + players + " players | " + time);
                    if (playerList.length() > 0) {
                        sender.sendMessage(ChatColor.GRAY + "    " + playerList);
                    }
                }
            } else {
                sender.sendMessage(ChatColor.GRAY + "No matches running.");
            }

            sender.sendMessage("");
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Instances:");
            if (info.has("instances")) {
                for (Map.Entry<String, JsonElement> entry : info.getAsJsonObject("instances").entrySet()) {
                    JsonObject inst = entry.getValue().getAsJsonObject();
                    String name = entry.getKey();
                    String status = inst.get("status").getAsString();
                    ChatColor statusColor = switch (status) {
                        case "IDLE", "READY" -> ChatColor.GREEN;
                        case "RUNNING" -> ChatColor.RED;
                        default -> ChatColor.DARK_GRAY;
                    };

                    String map = inst.has("map") ? inst.get("map").getAsString() : "";
                    int mapCount = inst.has("mapCount") ? inst.get("mapCount").getAsInt() : 0;

                    sender.sendMessage(ChatColor.YELLOW + "  " + name + " " +
                            statusColor + "[" + status + "]" +
                            ChatColor.WHITE + " | " + mapCount + " maps" +
                            (map.isEmpty() ? "" : " | " + map));
                }
            }

            sender.sendMessage("");
        });

        return true;
    }
}
