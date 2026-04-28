package com.ron.lobby.commands;

import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import com.ron.lobby.world.VoidWorldSetup;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MatchesCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        LobbyUI.refreshScreen(player);
        LobbyMessaging.requestServerInfo(info -> VoidWorldSetup.showRunningMatches(player));
        return true;
    }
}

