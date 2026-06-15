package com.ron.lobby.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ron.common.scoring.ScoringUtil;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LeaderboardCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!LobbyMessaging.isRankedEnabled()) {
            player.sendMessage(ChatColor.RED + "Ranked is disabled on this network.");
            return true;
        }

        LobbyUI.refreshScreen(player);

        LobbyMessaging.requestLeaderboard(10, response -> {
            if (response.has("error")) {
                player.sendMessage("Failed to load leaderboard.");
                return;
            }
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "--- Leaderboard ---");
            JsonArray players = response.getAsJsonArray("players");
            for (int i = 0; i < players.size(); i++) {
                JsonObject s = players.get(i).getAsJsonObject();
                int points = s.get("points").getAsInt();
                String rank = ScoringUtil.getRank(points);
                ChatColor color = LobbyUI.getRankChatColor(points);
                player.sendMessage(String.format(" #%d %s%s %s- %d pts (%s) [%dW/%dL]",
                    i + 1, color, s.get("name").getAsString(), ChatColor.RESET,
                    points, rank, s.get("wins").getAsInt(), s.get("losses").getAsInt()));
            }
            player.sendMessage("");
        });
        return true;
    }

}
