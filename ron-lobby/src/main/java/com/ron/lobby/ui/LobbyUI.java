package com.ron.lobby.ui;

import com.ron.lobby.RonLobby;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class LobbyUI {

    public static ChatColor getRankChatColor(int points) {
        if (points >= 1000) return ChatColor.AQUA;
        if (points >= 500) return ChatColor.LIGHT_PURPLE;
        if (points >= 250) return ChatColor.GOLD;
        if (points >= 100) return ChatColor.GRAY;
        return ChatColor.RED;
    }

    private static final int CLEAR_LINES = 100;

    public static void refreshScreen(Player player) {
        for (int i = 0; i < CLEAR_LINES; i++) {
            player.sendMessage("");
        }

        player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Welcome to Reign of Nether!");
        player.sendMessage(ChatColor.DARK_GRAY + "Community Server");
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "Commands:");
        player.sendMessage("  /queue — Join the matchmaking queue");
        player.sendMessage("  /leave — Leave the queue");
        player.sendMessage("  /queue custom — Create custom lobby");
        player.sendMessage("  /queue join <code> — Join a custom lobby");
        player.sendMessage("  /matches — View running matches and available servers");
        player.sendMessage("  /spectate <instance> — Watch a match");
        player.sendMessage("  /leaderboard — Top 10 players");
        player.sendMessage("  /rank — Your stats");
        player.sendMessage("");

        int queueSize = RonLobby.matchQueue.getPublicQueueSize();
        boolean inQueue = RonLobby.matchQueue.isInAnyQueue(player.getUniqueId());
        player.sendMessage(ChatColor.WHITE + "Queue: " + ChatColor.YELLOW + queueSize + " players" +
                (inQueue ? ChatColor.GREEN + " (you are queued)" : ""));
        player.sendMessage(ChatColor.DARK_GRAY + "―――――――――――――――――――――――――――――――");
        player.sendMessage("");
    }
}
