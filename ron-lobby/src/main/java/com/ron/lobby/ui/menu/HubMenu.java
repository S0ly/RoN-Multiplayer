package com.ron.lobby.ui.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.queue.MatchQueue;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the hub menu: queue tile, custom lobby / leaderboard / rank entries, instance status row. */
public final class HubMenu {

    private HubMenu() {}

    public static void openHub(Player player) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.HUB);
        Inventory inv = holder.createInventory(45, ChatColor.DARK_AQUA + "Reign of Nether");
        MenuSupport.fillBorder45(inv);

        MatchQueue q = RonLobby.matchQueue;

        setQueueTile(inv, q, player);

        inv.setItem(12, MenuItems.action(Material.CHEST,
                ChatColor.GOLD + "Custom Lobby",
                "open-custom", null,
                ChatColor.GRAY + "Create or join a custom lobby"));

        boolean ranked = LobbyMessaging.isRankedEnabled();

        inv.setItem(14, MenuItems.action(Material.GOLD_INGOT,
                ChatColor.GOLD + "Leaderboard",
                "open-leaderboard", null,
                ranked ? ChatColor.GRAY + "Top 10 players"
                       : ChatColor.RED + "Ranked is disabled"));

        inv.setItem(16, MenuItems.playerHead(player.getUniqueId(),
                ChatColor.LIGHT_PURPLE + "Your Rank",
                "open-rank", null,
                List.of(ranked ? ChatColor.GRAY + "Click to view your stats"
                               : ChatColor.RED + "Ranked is disabled")));

        fillStatusRow(inv, player);

        player.openInventory(inv);
    }

    /**
     * Bottom of the hub: one colored concrete block per configured instance.
     * Unused interior slots show as gray "not configured" placeholders so the
     * row visually represents the total capacity at a glance.
     *  green=available, yellow=playing, orange=transitional, red=offline.
     * Click handling: if the viewer's name appears in the match's player list,
     * the click rejoins; otherwise running matches spectate.
     */
    private static void fillStatusRow(Inventory inv, Player viewer) {
        // Pre-fill all interior status slots with a grey placeholder.
        for (int s = 28; s <= 34; s++) {
            inv.setItem(s, MenuItems.action(Material.GRAY_CONCRETE,
                    ChatColor.GRAY + "—",
                    "noop", null,
                    ChatColor.DARK_GRAY + "No instance configured"));
        }

        JsonObject info = LobbyMessaging.getLastServerInfo();
        if (info == null || !info.has("instances")) return;

        JsonObject instances = info.getAsJsonObject("instances");
        JsonObject matches = info.has("matches") ? info.getAsJsonObject("matches") : null;
        int slot = 28;
        for (Map.Entry<String, JsonElement> entry : instances.entrySet()) {
            if (slot > 34) break;
            JsonObject inst = entry.getValue().getAsJsonObject();
            String name = entry.getKey();
            String status = inst.has("status") ? inst.get("status").getAsString() : "OFFLINE";
            String map = inst.has("map") ? inst.get("map").getAsString() : "";
            boolean running = status.equals("RUNNING");
            boolean canRejoin = running && matches != null && matches.has(name)
                    && matchHasPlayer(matches.getAsJsonObject(name), viewer.getName());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + statusColor(status) + statusLabel(status));
            if (running && !map.isEmpty()) {
                lore.add(ChatColor.GRAY + "Map: " + ChatColor.WHITE + map);
                if (inst.has("spectatorCount") && inst.has("maxSpectators")) {
                    lore.add(ChatColor.GRAY + "Spectators: " + ChatColor.WHITE
                            + inst.get("spectatorCount").getAsInt() + "/"
                            + inst.get("maxSpectators").getAsInt());
                }
                lore.add("");
                lore.add(canRejoin ? ChatColor.AQUA + "Click to rejoin"
                                   : ChatColor.GREEN + "Click to spectate");
            }

            String action = running ? (canRejoin ? "rejoin" : "spectate") : "noop";
            inv.setItem(slot, MenuItems.action(MenuItems.statusColor(status),
                    ChatColor.WHITE + name, action, name, lore));
            slot++;
        }
    }

    private static boolean matchHasPlayer(JsonObject match, String playerName) {
        if (!match.has("playerNames")) return false;
        for (JsonElement el : match.getAsJsonArray("playerNames")) {
            if (el.getAsString().equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    private static ChatColor statusColor(String status) {
        return switch (status) {
            case "IDLE", "READY" -> ChatColor.GREEN;
            case "RUNNING" -> ChatColor.YELLOW;
            case "PREPARING", "FINISHED" -> ChatColor.GOLD;
            default -> ChatColor.RED;
        };
    }

    /** Player-facing label for an instance status — READY (loaded, awaiting players) reads as "PREPARING". */
    private static String statusLabel(String status) {
        return "READY".equals(status) ? "PREPARING" : status;
    }

    static void rebuildHub(Inventory inv, Player player) {
        setQueueTile(inv, RonLobby.matchQueue, player);
        fillStatusRow(inv, player);
    }

    /** Hub slot 10: the public-queue join/leave tile, greyed out when the queue is disabled. */
    private static void setQueueTile(Inventory inv, MatchQueue q, Player player) {
        if (!q.isPublicQueueEnabled()) {
            inv.setItem(10, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Public Queue",
                    "noop", null,
                    ChatColor.RED + "Public matchmaking is disabled",
                    ChatColor.GRAY + "Use a custom lobby instead"));
            return;
        }
        boolean inQueue = q.isInAnyQueue(player.getUniqueId());
        inv.setItem(10, MenuItems.action(Material.CLOCK,
                ChatColor.YELLOW + (inQueue ? "Leave Queue" : "Join Queue"),
                "queue-toggle", null,
                ChatColor.GRAY + "Public queue: " + ChatColor.WHITE + q.getPublicQueueSize() + " players",
                ChatColor.GRAY + "Click to " + (inQueue ? "leave" : "join")));
    }
}
