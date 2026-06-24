package com.ron.lobby.ui.menu;

import com.ron.lobby.RonLobby;
import com.ron.lobby.queue.CustomLobbyView;
import com.ron.lobby.queue.MatchQueue;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds the custom-lobby menus: the lobby browser, the in-lobby view with host controls, and host map/mode selection. */
public final class CustomLobbyMenu {

    private CustomLobbyMenu() {}

    /** Public-lobby head slots, in fill order: bottom interior row first, then the row above. */
    private static final int[] PUBLIC_LOBBY_SLOTS = {28, 29, 30, 31, 32, 33, 34, 19, 20, 21, 22, 23, 24, 25};

    public static void openCustom(Player player) {
        CustomLobbyView lobby = RonLobby.matchQueue.getCustomLobbyView(player.getUniqueId());
        if (lobby == null) {
            openCustomNotInLobby(player);
        } else {
            openCustomInLobby(player, lobby);
        }
    }

    private static void openCustomNotInLobby(Player player) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.CUSTOM_LOBBY);
        Inventory inv = holder.createInventory(45, ChatColor.GOLD + "Custom Lobby");
        MenuSupport.fillBorder(inv);

        inv.setItem(11, MenuItems.action(Material.ANVIL,
                ChatColor.GREEN + "Create Lobby",
                "custom-create", null,
                ChatColor.GRAY + "Generates a code to share"));

        inv.setItem(15, MenuItems.action(Material.CHAIN,
                ChatColor.AQUA + "Join by Code",
                "custom-join", null,
                ChatColor.GRAY + "Type the code in chat"));

        // Public lobbies — click a host's head to join directly (no code needed).
        // Fill the bottom interior row first, then grow upward, like the hub's
        // instance list.
        List<CustomLobbyView> publics = RonLobby.matchQueue.getPublicLobbies();
        if (publics.isEmpty()) {
            inv.setItem(31, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "No public lobbies",
                    "noop", null,
                    ChatColor.GRAY + "Create one or join by code"));
        } else {
            int i = 0;
            for (CustomLobbyView lobby : publics) {
                if (i >= PUBLIC_LOBBY_SLOTS.length) break;
                inv.setItem(PUBLIC_LOBBY_SLOTS[i], buildPublicLobbyHead(lobby));
                i++;
            }
        }

        inv.setItem(40, MenuItems.back());

        player.openInventory(inv);
    }

    private static ItemStack buildPublicLobbyHead(CustomLobbyView lobby) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Host: " + ChatColor.WHITE + MenuSupport.nameOf(lobby.host()));
        lore.add(ChatColor.GRAY + "Players: " + ChatColor.WHITE + lobby.members().size());
        if (lobby.selectedMapName() != null) {
            lore.add(ChatColor.GRAY + "Map: " + ChatColor.WHITE + lobby.selectedMapName()
                    + (lobby.selectedMode() != null ? ChatColor.GRAY + " (" + lobby.selectedMode() + ")" : ""));
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "Click to join");
        return MenuItems.playerHead(lobby.host(),
                ChatColor.AQUA + MenuSupport.nameOf(lobby.host()) + ChatColor.GRAY + "'s lobby",
                "custom-join-public", lobby.code(), lore);
    }

    private static void openCustomInLobby(Player player, CustomLobbyView lobby) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.CUSTOM_LOBBY);
        Inventory inv = holder.createInventory(36, ChatColor.GOLD + "Custom Lobby — " + lobby.code());
        renderCustomLobby(inv, lobby, player);
        player.openInventory(inv);
    }

    /**
     * Paint the full custom-lobby view in place. Used for both initial open and
     * live refresh. The host gets the configuration controls (map → mode → optional
     * rules → start); everyone gets the member list + leave/back.
     */
    private static void renderCustomLobby(Inventory inv, CustomLobbyView lobby, Player player) {
        // Repaint the border first so leftover host-control items are cleared.
        MenuSupport.fillBorder(inv);
        for (int s = 10; s <= 16; s++) inv.setItem(s, null);
        for (int s = 19; s <= 25; s++) inv.setItem(s, null);

        boolean isHost = lobby.host().equals(player.getUniqueId());

        inv.setItem(4, MenuItems.action(Material.NAME_TAG,
                ChatColor.YELLOW + "Code: " + ChatColor.WHITE + ChatColor.BOLD + lobby.code(),
                "noop", null,
                ChatColor.GRAY + "Share with friends",
                ChatColor.GRAY + "Host: " + ChatColor.WHITE + MenuSupport.nameOf(lobby.host()),
                ChatColor.GRAY + "Visibility: " + (lobby.isPublic()
                        ? ChatColor.GREEN + "Public" : ChatColor.YELLOW + "Private")));

        // Members 10..16 then 19..25
        int slot = 10;
        for (UUID memberUuid : lobby.members()) {
            if (slot == 16 || slot == 17) slot = 19;
            if (slot > 25) break;
            inv.setItem(slot, MenuItems.playerHead(memberUuid,
                    ChatColor.WHITE + MenuSupport.nameOf(memberUuid)
                            + (memberUuid.equals(lobby.host()) ? ChatColor.GOLD + " (host)" : ""),
                    "noop", null, null));
            slot++;
        }

        if (isHost) renderHostControls(inv, lobby);

        inv.setItem(35, MenuItems.action(Material.BARRIER,
                ChatColor.RED + "Leave Lobby",
                "custom-leave", null,
                ChatColor.GRAY + (isHost ? "Disbands the lobby" : "Removes you from this lobby")));
        inv.setItem(27, MenuItems.back());
    }

    /** Host-only bottom row: Select Map (28) → Select Mode (29) → optional rules (30/31) → Start (33). */
    private static void renderHostControls(Inventory inv, CustomLobbyView lobby) {
        boolean hasMap = lobby.selectedMapFolder() != null;
        boolean hasMode = lobby.selectedMode() != null;

        inv.setItem(28, MenuItems.action(Material.FILLED_MAP,
                ChatColor.AQUA + "Select Map",
                "custom-select-map", null,
                ChatColor.GRAY + "Map: " + ChatColor.WHITE + (hasMap ? lobby.selectedMapName() : "none"),
                ChatColor.GRAY + "Click to choose"));

        if (hasMap) {
            inv.setItem(29, MenuItems.action(MenuItems.modeIcon(lobby.selectedMode()),
                    ChatColor.AQUA + "Select Game Mode",
                    "custom-select-mode", null,
                    ChatColor.GRAY + "Mode: " + ChatColor.WHITE + (hasMode ? lobby.selectedMode() : "none"),
                    ChatColor.GRAY + "Click to choose"));
        } else {
            inv.setItem(29, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Select Game Mode",
                    "noop", null,
                    ChatColor.RED + "Pick a map first"));
        }

        // Optional rules are always shown to the host (discoverable), but only
        // interactive once their prerequisite is met (a mode chosen; FFA for the
        // alliance lock). Order: map → mode → rules → start.
        boolean fog = lobby.fogOfWar();
        if (hasMode) {
            inv.setItem(31, MenuItems.action(fog ? Material.SCULK_SENSOR : Material.GLOWSTONE_DUST,
                    (fog ? ChatColor.GREEN : ChatColor.GRAY) + "Fog of War: " + (fog ? "ON" : "OFF"),
                    "custom-toggle-fog", null,
                    ChatColor.GRAY + "Optional rule — default OFF",
                    ChatColor.GRAY + "Click to toggle"));
        } else {
            inv.setItem(31, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Fog of War: OFF",
                    "noop", null,
                    ChatColor.GRAY + "Optional rule — default OFF",
                    ChatColor.RED + "Select a mode first"));
        }

        boolean ffa = hasMode && lobby.selectedMode().toLowerCase().startsWith("ffa");
        if (ffa) {
            boolean lock = lobby.allianceLock();
            inv.setItem(30, MenuItems.action(lock ? Material.SHIELD : Material.IRON_DOOR,
                    (lock ? ChatColor.GREEN : ChatColor.GRAY) + "Lock Alliances: " + (lock ? "ON" : "OFF"),
                    "custom-toggle-alliance", null,
                    ChatColor.GRAY + "FFA only — default OFF",
                    ChatColor.GRAY + "Click to toggle"));
        } else {
            inv.setItem(30, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Lock Alliances: ON",
                    "noop", null,
                    ChatColor.GRAY + "FFA only — locked in team/coop",
                    ChatColor.RED + (hasMode ? "Always locked for this mode" : "Select an FFA mode to change")));
        }

        // Visibility — always togglable; controls how others can join (slot 32).
        boolean pub = lobby.isPublic();
        inv.setItem(32, MenuItems.action(pub ? Material.ENDER_EYE : Material.ENDER_PEARL,
                (pub ? ChatColor.GREEN : ChatColor.YELLOW) + "Visibility: " + (pub ? "Public" : "Private"),
                "custom-toggle-visibility", null,
                ChatColor.GRAY + (pub ? "Anyone can join from the menu" : "Code required to join"),
                ChatColor.GRAY + "Click to toggle"));

        boolean countOk = hasMode && lobby.members().size() == lobby.selectedModePlayers();
        boolean canStart = hasMap && hasMode && countOk;
        List<String> startLore = new ArrayList<>();
        startLore.add(ChatColor.GRAY + "Begin the match");
        if (!hasMap) startLore.add(ChatColor.RED + "Select a map");
        else if (!hasMode) startLore.add(ChatColor.RED + "Select a game mode");
        else if (!countOk) startLore.add(ChatColor.RED + "Need " + lobby.selectedModePlayers()
                + " players (have " + lobby.members().size() + ")");
        else startLore.add(ChatColor.GREEN + "Ready — click to start");
        inv.setItem(33, MenuItems.action(Material.FIRE_CHARGE,
                (canStart ? ChatColor.GREEN : ChatColor.GRAY) + "Start",
                canStart ? "custom-start" : "noop", null,
                startLore.toArray(new String[0])));
    }

    // ---------- Host map / mode selection ----------

    public static void openHostMapSelect(Player player) {
        CustomLobbyView lobby = RonLobby.matchQueue.getCustomLobbyView(player.getUniqueId());
        if (lobby == null || !lobby.host().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[RoN] Only the host can pick the map.");
            return;
        }
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.CUSTOM_MAP_SELECT);
        Inventory inv = holder.createInventory(54, ChatColor.GOLD + "Select a Map");
        for (int i = 0; i < 9; i++) inv.setItem(i, MenuItems.border());
        for (int i = 45; i < 54; i++) inv.setItem(i, MenuItems.border());

        List<MapOption> options = lobby.mapOptions();
        if (options == null || options.isEmpty()) {
            inv.setItem(22, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "No maps available",
                    "noop", null,
                    ChatColor.GRAY + "for " + lobby.members().size() + " players right now"));
        } else {
            int slot = 10;
            for (MapOption map : options) {
                if (slot >= 44) break;
                if (slot % 9 == 8) slot += 2;
                inv.setItem(slot, buildHostMapItem(map, lobby.selectedMapFolder()));
                slot++;
            }
        }

        // Let the server pick — selectable just like a real map (mode chosen next).
        inv.setItem(53, buildRandomMapItem(lobby.selectedMapFolder()));

        // Coop/PvE maps are hidden by default; this re-fetches the list with them included.
        boolean coop = lobby.showCoop();
        inv.setItem(45, MenuItems.action(coop ? Material.ZOMBIE_HEAD : Material.GRAY_DYE,
                (coop ? ChatColor.GREEN : ChatColor.GRAY) + "Coop / PvE maps: " + (coop ? "Shown" : "Hidden"),
                "custom-toggle-coop", null,
                ChatColor.GRAY + "Off by default",
                ChatColor.GRAY + "Click to toggle"));

        inv.setItem(49, MenuItems.action(Material.ARROW, ChatColor.WHITE + "← Back",
                "back", null, ChatColor.GRAY + "Return to the lobby"));
        player.openInventory(inv);
    }

    private static ItemStack buildRandomMapItem(String selectedFolder) {
        boolean selected = MatchQueue.RANDOM_MAP_FOLDER.equals(selectedFolder);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Let the server pick a map");
        lore.add("");
        lore.add(selected ? ChatColor.GREEN + "Selected — click to change modes"
                          : ChatColor.YELLOW + "Click to choose a mode");
        return MenuItems.action(Material.NETHER_STAR,
                (selected ? ChatColor.GREEN : ChatColor.LIGHT_PURPLE) + "Random",
                "custom-pick-map", MatchQueue.RANDOM_MAP_FOLDER, lore);
    }

    private static ItemStack buildHostMapItem(MapOption map, String selectedFolder) {
        List<String> lore = MenuSupport.modesLore(map);
        lore.add("");
        boolean selected = map.folder().equals(selectedFolder);
        lore.add(selected ? ChatColor.GREEN + "Selected — click to change modes"
                          : ChatColor.YELLOW + "Click to choose a mode");
        return MenuItems.action(Material.FILLED_MAP,
                (selected ? ChatColor.GREEN : ChatColor.WHITE) + map.name(),
                "custom-pick-map", map.folder(), lore);
    }

    public static void openHostModeSelect(Player player, String mapFolder) {
        CustomLobbyView lobby = RonLobby.matchQueue.getCustomLobbyView(player.getUniqueId());
        if (lobby == null || !lobby.host().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[RoN] Only the host can pick the mode.");
            return;
        }
        MapOption map = MatchQueue.RANDOM_MAP_FOLDER.equals(mapFolder)
                ? MatchQueue.randomMapOption(lobby.mapOptions())
                : lobby.mapOptions().stream()
                        .filter(o -> o.folder().equals(mapFolder)).findFirst().orElse(null);
        if (map == null || map.modes().isEmpty()) {
            player.sendMessage(ChatColor.RED + "[RoN] That map is no longer available.");
            return;
        }

        MenuSupport.openModeSelect(player, RonMenuHolder.MenuId.CUSTOM_MODE_SELECT, map,
                mode -> buildHostModeItem(mapFolder, mode, mode.name().equals(lobby.selectedMode())));
    }

    private static ItemStack buildHostModeItem(String mapFolder, ModeOption mode, boolean selected) {
        return MenuSupport.modeItem(mapFolder, mode, "custom-pick-mode",
                (selected ? ChatColor.GREEN : ChatColor.AQUA) + mode.name(),
                selected ? ChatColor.GREEN + "Selected" : ChatColor.GREEN + "Click to choose");
    }

    static void rebuildCustom(Inventory inv, Player player) {
        CustomLobbyView lobby = RonLobby.matchQueue.getCustomLobbyView(player.getUniqueId());
        if (lobby == null || inv.getSize() != 36) {
            // Layout no longer matches (lobby disbanded or we joined one) —
            // re-open so the player gets the right inventory size.
            openCustom(player);
            return;
        }
        renderCustomLobby(inv, lobby, player);
    }
}
