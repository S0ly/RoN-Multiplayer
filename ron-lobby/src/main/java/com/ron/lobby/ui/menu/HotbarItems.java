package com.ron.lobby.ui.menu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

/**
 * Hotbar shortcut items shown to players when `ui.hand-items: true`. Each item
 * carries an action tag readable by MenuListener's PlayerInteractEvent handler.
 */
public final class HotbarItems {

    private HotbarItems() {}

    public static void give(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setItem(0, MenuItems.action(Material.COMPASS,
                "§eMenu", "open-hub", null,
                "§7Right-click to open the lobby menu"));
        inv.setHeldItemSlot(0);
    }

    public static void clear(Player player) {
        player.getInventory().clear();
    }
}
