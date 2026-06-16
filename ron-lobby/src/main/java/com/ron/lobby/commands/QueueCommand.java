package com.ron.lobby.commands;

import com.ron.lobby.RonLobby;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class QueueCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        LobbyUI.refreshScreen(player);

        if (args.length == 0) {
            if (!RonLobby.matchQueue.isPublicQueueEnabled()) {
                player.sendMessage(ChatColor.RED + "[RoN] Public matchmaking is disabled — use /queue custom instead.");
                return true;
            }
            if (RonLobby.matchQueue.isInAnyQueue(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "[RoN] You are already in a queue! Use /queue leave first.");
                return true;
            }
            RonLobby.matchQueue.joinPublicQueue(player.getUniqueId(), player.getName());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "custom" -> {
                if (RonLobby.matchQueue.isInAnyQueue(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "[RoN] You are already in a queue! Use /queue leave first.");
                    return true;
                }
                String code = RonLobby.matchQueue.createCustomLobby(player.getUniqueId(), player.getName());
                if (code == null) return true;
                player.sendMessage(ChatColor.GREEN + "[RoN] Custom lobby created! Code: " + ChatColor.WHITE + ChatColor.BOLD + code);
                player.sendMessage(ChatColor.GRAY + "Share this code with friends: /queue join " + code);
            }
            case "join" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /queue join <code>");
                    return true;
                }
                if (RonLobby.matchQueue.isInAnyQueue(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "[RoN] You are already in a queue! Use /queue leave first.");
                    return true;
                }
                RonLobby.matchQueue.joinCustomLobby(player.getUniqueId(), player.getName(), args[1].toUpperCase());
            }
            case "leave" -> {
                RonLobby.matchQueue.leaveQueue(player.getUniqueId(), player.getName());
            }
            case "start" -> {
                RonLobby.matchQueue.startCustom(player.getUniqueId());
            }
            default -> {
                player.sendMessage(ChatColor.GOLD + "[RoN] Queue commands:");
                player.sendMessage("  /queue — Join public queue");
                player.sendMessage("  /queue custom — Create custom lobby");
                player.sendMessage("  /queue join <code> — Join a custom lobby");
                player.sendMessage("  /queue leave — Leave queue");
                player.sendMessage("  /queue start — Force start (custom lobby host)");
            }
        }

        return true;
    }
}
