package com.ron.lobby.ui.menu;

import com.ron.lobby.RonLobby;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class MenuItems {

    public static final NamespacedKey ACTION_KEY = new NamespacedKey(RonLobby.INSTANCE, "action");
    public static final NamespacedKey PAYLOAD_KEY = new NamespacedKey(RonLobby.INSTANCE, "payload");

    private MenuItems() {}

    public static ItemStack border() {
        ItemStack pane = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(" ");
        meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, "noop");
        pane.setItemMeta(meta);
        return pane;
    }

    public static ItemStack action(Material material, String displayName, String action, String payload, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(displayName);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
        if (payload != null) meta.getPersistentDataContainer().set(PAYLOAD_KEY, PersistentDataType.STRING, payload);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    public static ItemStack action(Material material, String displayName, String action, String payload, List<String> lore) {
        return action(material, displayName, action, payload, lore.toArray(new String[0]));
    }

    public static ItemStack playerHead(UUID uuid, String displayName, String action, String payload, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        meta.setDisplayName(displayName);
        if (lore != null && !lore.isEmpty()) meta.setLore(lore);
        meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
        if (payload != null) meta.getPersistentDataContainer().set(PAYLOAD_KEY, PersistentDataType.STRING, payload);
        head.setItemMeta(meta);
        return head;
    }

    public static String readAction(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(ACTION_KEY, PersistentDataType.STRING);
    }

    public static String readPayload(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(PAYLOAD_KEY, PersistentDataType.STRING);
    }

    public static String stripColor(String s) {
        return s == null ? null : ChatColor.stripColor(s);
    }

    /** Mode-name → icon mapping (coop/ffa/team). */
    public static Material modeIcon(String modeName) {
        if (modeName == null) return Material.PAPER;
        String lower = modeName.toLowerCase();
        if (lower.contains("coop")) return Material.IRON_PICKAXE;
        if (lower.contains("ffa")) return Material.IRON_AXE;
        return Material.IRON_SWORD;
    }

    /** Status string → concrete block colour for the hub status row. */
    public static Material statusColor(String status) {
        if (status == null) return Material.GRAY_CONCRETE;
        return switch (status) {
            case "IDLE", "READY" -> Material.LIME_CONCRETE;
            case "RUNNING" -> Material.YELLOW_CONCRETE;
            case "PREPARING", "FINISHED" -> Material.ORANGE_CONCRETE;
            default -> Material.RED_CONCRETE; // OFFLINE + unknown
        };
    }

    public static ItemStack withAmount(ItemStack stack, int amount) {
        if (stack == null) return null;
        stack.setAmount(Math.max(1, Math.min(64, amount)));
        return stack;
    }

    public static ItemStack back() {
        return action(Material.ARROW, ChatColor.WHITE + "← Back", "back", null,
                ChatColor.GRAY + "Return to the hub");
    }
}
