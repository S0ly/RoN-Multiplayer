package com.ron.lobby.ui.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ron.common.scoring.ScoringUtil;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the read-only stats menus: running matches, the leaderboard, and the player's own rank. */
public final class StatsMenu {

    private StatsMenu() {}

    // ---------- Matches ----------

    public static void openMatches(Player player) {
        LobbyMessaging.requestServerInfo(info -> renderMatches(player, info));
    }

    private static void renderMatches(Player player, JsonObject info) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.MATCHES);
        Inventory inv = holder.createInventory(27, ChatColor.AQUA + "Running Matches");
        MenuSupport.fillBorder(inv);

        inv.setItem(18, MenuItems.back());

        JsonObject matches = (info != null && info.has("matches")) ? info.getAsJsonObject("matches") : null;
        if (matches == null || matches.isEmpty()) {
            inv.setItem(13, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "No matches running", "noop", null));
            player.openInventory(inv);
            return;
        }

        int slot = 10;
        for (Map.Entry<String, JsonElement> entry : matches.entrySet()) {
            if (slot >= 17) break;
            JsonObject m = entry.getValue().getAsJsonObject();
            String instance = entry.getKey();
            String map = m.get("map").getAsString();
            int players = m.get("players").getAsInt();
            long seconds = m.get("elapsedSeconds").getAsLong();
            int spectators = m.has("spectatorCount") ? m.get("spectatorCount").getAsInt() : 0;
            int maxSpec = m.has("maxSpectators") ? m.get("maxSpectators").getAsInt() : 0;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Map: " + ChatColor.WHITE + map);
            lore.add(ChatColor.GRAY + "Players: " + ChatColor.WHITE + players);
            lore.add(ChatColor.GRAY + "Elapsed: " + ChatColor.WHITE + time);
            if (maxSpec > 0) {
                lore.add(ChatColor.GRAY + "Spectators: " + ChatColor.WHITE + spectators + "/" + maxSpec);
            }
            lore.add("");
            boolean full = maxSpec > 0 && spectators >= maxSpec;
            lore.add(full ? ChatColor.RED + "Spectator slots full" : ChatColor.GREEN + "Click to spectate");

            inv.setItem(slot, MenuItems.action(Material.FILLED_MAP,
                    ChatColor.YELLOW + instance, full ? "noop" : "spectate", instance, lore));
            slot++;
        }

        player.openInventory(inv);
    }

    // ---------- Leaderboard ----------

    public static void openLeaderboard(Player player) {
        if (!LobbyMessaging.isRankedEnabled()) {
            player.sendMessage(ChatColor.RED + "[RoN] Ranked is disabled on this network.");
            return;
        }
        LobbyMessaging.requestLeaderboard(10, response -> renderLeaderboard(player, response));
    }

    private static void renderLeaderboard(Player player, JsonObject response) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.LEADERBOARD);
        Inventory inv = holder.createInventory(36, ChatColor.GOLD + "Top Players");
        MenuSupport.fillBorder(inv);

        inv.setItem(27, MenuItems.back());

        if (response == null || response.has("error") || !response.has("players")) {
            inv.setItem(13, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Leaderboard unavailable", "noop", null));
            player.openInventory(inv);
            return;
        }

        JsonArray players = response.getAsJsonArray("players");
        int[] slots = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
        for (int i = 0; i < players.size() && i < slots.length; i++) {
            JsonObject s = players.get(i).getAsJsonObject();
            String name = s.get("name").getAsString();
            int points = s.get("points").getAsInt();
            int wins = s.get("wins").getAsInt();
            int losses = s.get("losses").getAsInt();
            String rank = ScoringUtil.getRank(points);
            ChatColor color = LobbyUI.getRankChatColor(points);

            inv.setItem(slots[i], MenuItems.playerHead(MenuSupport.uuidByName(name),
                    color + "#" + (i + 1) + " " + name,
                    "noop", null,
                    List.of(
                            ChatColor.GRAY + "Rank: " + color + rank,
                            ChatColor.GRAY + "Points: " + ChatColor.WHITE + points,
                            ChatColor.GRAY + "Record: " + ChatColor.WHITE + wins + "W / " + losses + "L"
                    )));
        }

        player.openInventory(inv);
    }

    // ---------- Rank ----------

    public static void openRank(Player player) {
        if (!LobbyMessaging.isRankedEnabled()) {
            player.sendMessage(ChatColor.RED + "[RoN] Ranked is disabled on this network.");
            return;
        }
        LobbyMessaging.requestRank(player.getUniqueId().toString(), player.getName(), response -> {
            if (response == null || response.has("error")) {
                player.sendMessage(ChatColor.RED + "[RoN] Failed to load your stats.");
                return;
            }
            int points = response.get("points").getAsInt();
            int wins = response.get("wins").getAsInt();
            int losses = response.get("losses").getAsInt();
            String rank = ScoringUtil.getRank(points);
            ChatColor color = LobbyUI.getRankChatColor(points);
            player.sendMessage("");
            player.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "--- Your Stats ---");
            player.sendMessage(" Rank: " + color + rank);
            player.sendMessage(" Points: " + points);
            player.sendMessage(String.format(" Record: %dW / %dL", wins, losses));
            player.sendMessage("");
        });
    }
}
