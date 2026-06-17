package com.ron.lobby.ui.menu;

import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;
import com.ron.lobby.ui.UiSettings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared helpers used across the menu builders (HubMenu, CustomLobbyMenu,
 * VoteMenu, StatsMenu): UI settings, border painting, mode tiles, name lookups
 * and the cross-menu live refresh. No menu owns these on its own.
 */
public final class MenuSupport {

    private static UiSettings settings = new UiSettings(true, true, true, true, false, true, true, false, false, true);

    private MenuSupport() {}

    public static void setSettings(UiSettings s) { settings = s; }
    static UiSettings settings() { return settings; }

    // ---------- Borders ----------

    /** Fill a full border (top + bottom rows, left + right columns) for any inventory size. */
    static void fillBorder(Inventory inv) {
        ItemStack pane = MenuItems.border();
        int size = inv.getSize();
        int rows = size / 9;
        for (int col = 0; col < 9; col++) {
            inv.setItem(col, pane);
            inv.setItem(size - 9 + col, pane);
        }
        for (int row = 1; row < rows - 1; row++) {
            inv.setItem(row * 9, pane);
            inv.setItem(row * 9 + 8, pane);
        }
    }

    /**
     * 45-slot border with a divider row between actions and the status row:
     *  row 1: full border
     *  row 2: side border only (actions)
     *  row 3: full border (divider)
     *  row 4: side border only (status row)
     *  row 5: full border
     */
    static void fillBorder45(Inventory inv) {
        ItemStack pane = MenuItems.border();
        for (int i = 0; i < 9; i++) inv.setItem(i, pane);
        for (int i = 18; i < 27; i++) inv.setItem(i, pane);
        for (int i = 36; i < 45; i++) inv.setItem(i, pane);
        inv.setItem(9, pane);
        inv.setItem(17, pane);
        inv.setItem(27, pane);
        inv.setItem(35, pane);
    }

    // ---------- Mode tiles / mode-select shell (shared by host + vote menus) ----------

    static List<ModeOption> modesOf(MapOption map) {
        return map.modes() != null ? map.modes() : List.of();
    }

    /** Lore listing each mode and its player count: "• Name (N players)". */
    static List<String> modesLore(MapOption map) {
        List<String> lore = new ArrayList<>();
        for (ModeOption m : modesOf(map)) {
            lore.add(ChatColor.AQUA + "• " + m.name() + ChatColor.GRAY + " (" + m.players() + " players)");
        }
        return lore;
    }

    /**
     * Shared mode tile used by both the host and the vote mode menus: mode icon,
     * a "N players" line, a blank line, then the caller's context-specific tail.
     */
    static ItemStack modeItem(String mapFolder, ModeOption mode, String action, String title, String... tail) {
        String[] lore = new String[2 + tail.length];
        lore[0] = ChatColor.GRAY + "" + mode.players() + " players";
        lore[1] = "";
        System.arraycopy(tail, 0, lore, 2, tail.length);
        return MenuItems.action(MenuItems.modeIcon(mode.name()), title, action,
                mapFolder + "/" + mode.name(), lore);
    }

    /** Shared 27-slot mode-select shell: border, modes in slots 10–16, back at 18. */
    static void openModeSelect(Player player, RonMenuHolder.MenuId id, MapOption map,
                               Function<ModeOption, ItemStack> itemFor) {
        RonMenuHolder holder = new RonMenuHolder(id, map.folder());
        Inventory inv = holder.createInventory(27, ChatColor.GOLD + "Modes — " + map.name());
        fillBorder(inv);

        int slot = 10;
        for (ModeOption mode : modesOf(map)) {
            if (slot >= 17) break;
            inv.setItem(slot, itemFor.apply(mode));
            slot++;
        }

        inv.setItem(18, MenuItems.back());
        player.openInventory(inv);
    }

    // ---------- Name lookups ----------

    static String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    static UUID uuidByName(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    // ---------- Live refresh for Hub + Custom Lobby ----------

    /**
     * Re-renders open Hub and Custom Lobby inventories in place. Called after
     * any state mutation that could change what those menus display: queue
     * size, custom lobby member list, instance status push.
     */
    public static void refreshLobbyMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof RonMenuHolder holder)) continue;
            switch (holder.menuId()) {
                case HUB -> HubMenu.rebuildHub(top, p);
                case CUSTOM_LOBBY -> CustomLobbyMenu.rebuildCustom(top, p);
                default -> { /* others use VoteMenu.refreshVoteMenus or open on demand */ }
            }
        }
    }
}
