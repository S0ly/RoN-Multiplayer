package com.ron.lobby.ui.menu;

import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as ours and identifies which menu it is so the click
 * listener can route events without parsing localized titles.
 */
public class RonMenuHolder implements InventoryHolder {

    public enum MenuId { HUB, PRIVATE_LOBBY, VOTE, VOTE_MODES, MATCHES, LEADERBOARD }

    private final MenuId menuId;
    private final String payload;
    private Inventory inventory;

    public RonMenuHolder(MenuId menuId, String payload) {
        this.menuId = menuId;
        this.payload = payload;
    }

    public RonMenuHolder(MenuId menuId) {
        this(menuId, null);
    }

    public MenuId menuId() { return menuId; }
    public String payload() { return payload; }

    public Inventory createInventory(int size, String title) {
        this.inventory = Bukkit.createInventory(this, size, title);
        return this.inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
