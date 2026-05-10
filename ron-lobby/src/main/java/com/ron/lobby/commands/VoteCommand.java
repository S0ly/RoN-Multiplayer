package com.ron.lobby.commands;

import com.ron.lobby.RonLobby;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class VoteCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can vote.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "[RoN] Usage: /vote <number> [mode]");
            return true;
        }

        try {
            int choice = Integer.parseInt(args[0]);
            String mode = args.length >= 2 ? args[1] : null;
            RonLobby.matchQueue.castVote(player.getUniqueId(), choice, mode);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "[RoN] Invalid number. Usage: /vote <number> [mode]");
        }

        return true;
    }
}
