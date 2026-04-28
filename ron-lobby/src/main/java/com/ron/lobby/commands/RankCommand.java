package com.ron.lobby.commands;

import com.ron.common.scoring.ScoringUtil;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RankCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        LobbyUI.refreshScreen(player);

        LobbyMessaging.requestRank(
            player.getUniqueId().toString(),
            player.getName(),
            response -> {
                if (response.has("error")) {
                    player.sendMessage("Failed to load your stats.");
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
            }
        );
        return true;
    }

}
