package com.ron.lobby.commands;

import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.LobbyUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class SpectateCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        LobbyUI.refreshScreen(player);

        if (args.length < 1) {
            player.sendMessage("Usage: /spectate <instance>");
            player.sendMessage("Join a running match as an observer.");
            return true;
        }

        String instance = args[0];
        player.sendMessage("Joining " + instance + " as spectator...");
        LobbyMessaging.sendTransfer(List.of(player.getUniqueId().toString()), instance);
        return true;
    }
}
