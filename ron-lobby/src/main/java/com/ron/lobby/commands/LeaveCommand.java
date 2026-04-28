package com.ron.lobby.commands;

import com.ron.lobby.RonLobby;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LeaveCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        LobbyUI.refreshScreen(player);

        if (!RonLobby.matchQueue.isInAnyQueue(player.getUniqueId())) {
            player.sendMessage("You are not in the queue.");
            return true;
        }

        RonLobby.matchQueue.leaveQueue(player.getUniqueId(), player.getName());
        return true;
    }
}
