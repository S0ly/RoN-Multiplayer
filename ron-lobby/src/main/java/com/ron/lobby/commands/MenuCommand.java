package com.ron.lobby.commands;

import com.ron.lobby.RonLobby;
import com.ron.lobby.ui.menu.CustomLobbyMenu;
import com.ron.lobby.ui.menu.HubMenu;
import com.ron.lobby.ui.menu.StatsMenu;
import com.ron.lobby.ui.menu.VoteMenu;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MenuCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (args.length == 0) {
            HubMenu.openHub(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "vote" -> {
                if (!RonLobby.matchQueue.isVoteActive()) {
                    player.sendMessage(ChatColor.RED + "[RoN] No vote is active.");
                    return true;
                }
                VoteMenu.openVote(player);
            }
            case "custom" -> CustomLobbyMenu.openCustom(player);
            case "matches" -> StatsMenu.openMatches(player);
            case "leaderboard" -> StatsMenu.openLeaderboard(player);
            case "rank" -> StatsMenu.openRank(player);
            default -> HubMenu.openHub(player);
        }
        return true;
    }
}
