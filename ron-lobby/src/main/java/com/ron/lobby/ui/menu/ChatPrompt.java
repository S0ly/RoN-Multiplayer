package com.ron.lobby.ui.menu;

import com.ron.lobby.RonLobby;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Simple "type something in chat" prompt — used for entering a custom lobby
 * code without needing an anvil GUI. Pending prompts are stored per-player and
 * consume the player's next chat message.
 */
public class ChatPrompt implements Listener {

    private static final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public static void prompt(Player player, String message, Consumer<String> onAnswer) {
        player.closeInventory();
        player.sendMessage(ChatColor.YELLOW + "[RoN] " + message);
        player.sendMessage(ChatColor.GRAY + "(type 'cancel' to abort)");
        pending.put(player.getUniqueId(), onAnswer);
    }

    public static void cancel(UUID uuid) {
        pending.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Consumer<String> cb = pending.remove(uuid);
        if (cb == null) return;

        event.setCancelled(true);
        String msg = event.getMessage().trim();
        Bukkit.getScheduler().runTask(RonLobby.INSTANCE, () -> {
            if (msg.equalsIgnoreCase("cancel")) {
                event.getPlayer().sendMessage(ChatColor.GRAY + "[RoN] Cancelled.");
                return;
            }
            cb.accept(msg);
        });
    }
}
