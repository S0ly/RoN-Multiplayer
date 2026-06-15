package com.ron.lobby.ui.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ron.common.scoring.ScoringUtil;
import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.queue.MatchQueue;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;
import com.ron.lobby.queue.PrivateLobbyView;
import com.ron.lobby.ui.LobbyUI;
import com.ron.lobby.ui.UiSettings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single entry point the rest of the plugin uses to open menus. All business
 * logic stays in MatchQueue / LobbyMessaging — this class only builds and
 * shows inventories.
 */
public final class MenuService {

    private static UiSettings settings = new UiSettings(true, true, true, true, false, true, true, false, false, true);

    private MenuService() {}

    public static void setSettings(UiSettings s) { settings = s; }
    public static UiSettings settings() { return settings; }

    // ---------- Hub ----------

    public static void openHub(Player player) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.HUB);
        Inventory inv = holder.createInventory(45, ChatColor.DARK_AQUA + "Reign of Nether");
        fillBorder45(inv);

        MatchQueue q = RonLobby.matchQueue;
        boolean inQueue = q.isInAnyQueue(player.getUniqueId());

        inv.setItem(10, MenuItems.action(Material.CLOCK,
                ChatColor.YELLOW + (inQueue ? "Leave Queue" : "Join Queue"),
                "queue-toggle", null,
                ChatColor.GRAY + "Public queue: " + ChatColor.WHITE + q.getPublicQueueSize() + " players",
                ChatColor.GRAY + "Click to " + (inQueue ? "leave" : "join")));

        inv.setItem(12, MenuItems.action(Material.CHEST,
                ChatColor.GOLD + "Private Lobby",
                "open-private", null,
                ChatColor.GRAY + "Create or join a private lobby"));

        if (LobbyMessaging.isRankedEnabled()) {
            inv.setItem(14, MenuItems.action(Material.GOLD_INGOT,
                    ChatColor.GOLD + "Leaderboard",
                    "open-leaderboard", null,
                    ChatColor.GRAY + "Top 10 players"));

            inv.setItem(16, MenuItems.playerHead(player.getUniqueId(),
                    ChatColor.LIGHT_PURPLE + "Your Rank",
                    "open-rank", null,
                    List.of(ChatColor.GRAY + "Click to view your stats")));
        }

        fillStatusRow(inv, player);

        player.openInventory(inv);
    }

    /**
     * Bottom of the hub: one colored concrete block per configured instance.
     * Unused interior slots show as gray "not configured" placeholders so the
     * row visually represents the total capacity at a glance.
     *  green=available, yellow=playing, orange=transitional, red=offline.
     * Click handling: if the viewer's name appears in the match's player list,
     * the click rejoins; otherwise running matches spectate.
     */
    private static void fillStatusRow(Inventory inv, Player viewer) {
        // Pre-fill all interior status slots with a grey placeholder.
        for (int s = 28; s <= 34; s++) {
            inv.setItem(s, MenuItems.action(Material.GRAY_CONCRETE,
                    ChatColor.GRAY + "—",
                    "noop", null,
                    ChatColor.DARK_GRAY + "No instance configured"));
        }

        JsonObject info = LobbyMessaging.getLastServerInfo();
        if (info == null || !info.has("instances")) return;

        JsonObject instances = info.getAsJsonObject("instances");
        JsonObject matches = info.has("matches") ? info.getAsJsonObject("matches") : null;
        int slot = 28;
        for (Map.Entry<String, JsonElement> entry : instances.entrySet()) {
            if (slot > 34) break;
            JsonObject inst = entry.getValue().getAsJsonObject();
            String name = entry.getKey();
            String status = inst.has("status") ? inst.get("status").getAsString() : "OFFLINE";
            String map = inst.has("map") ? inst.get("map").getAsString() : "";
            boolean running = status.equals("RUNNING");
            boolean canRejoin = running && matches != null && matches.has(name)
                    && matchHasPlayer(matches.getAsJsonObject(name), viewer.getName());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Status: " + statusColor(status) + statusLabel(status));
            if (running && !map.isEmpty()) {
                lore.add(ChatColor.GRAY + "Map: " + ChatColor.WHITE + map);
                if (inst.has("spectatorCount") && inst.has("maxSpectators")) {
                    lore.add(ChatColor.GRAY + "Spectators: " + ChatColor.WHITE
                            + inst.get("spectatorCount").getAsInt() + "/"
                            + inst.get("maxSpectators").getAsInt());
                }
                lore.add("");
                lore.add(canRejoin ? ChatColor.AQUA + "Click to rejoin"
                                   : ChatColor.GREEN + "Click to spectate");
            }

            String action = running ? (canRejoin ? "rejoin" : "spectate") : "noop";
            inv.setItem(slot, MenuItems.action(MenuItems.statusColor(status),
                    ChatColor.WHITE + name, action, name, lore));
            slot++;
        }
    }

    private static boolean matchHasPlayer(JsonObject match, String playerName) {
        if (!match.has("playerNames")) return false;
        for (JsonElement el : match.getAsJsonArray("playerNames")) {
            if (el.getAsString().equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    private static ChatColor statusColor(String status) {
        return switch (status) {
            case "IDLE", "READY" -> ChatColor.GREEN;
            case "RUNNING" -> ChatColor.YELLOW;
            case "PREPARING", "FINISHED" -> ChatColor.GOLD;
            default -> ChatColor.RED;
        };
    }

    /** Player-facing label for an instance status — READY (loaded, awaiting players) reads as "PREPARING". */
    private static String statusLabel(String status) {
        return "READY".equals(status) ? "PREPARING" : status;
    }

    // ---------- Private Lobby ----------

    public static void openPrivate(Player player) {
        PrivateLobbyView lobby = RonLobby.matchQueue.getPrivateLobbyView(player.getUniqueId());
        if (lobby == null) {
            openPrivateNotInLobby(player);
        } else {
            openPrivateInLobby(player, lobby);
        }
    }

    private static void openPrivateNotInLobby(Player player) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.PRIVATE_LOBBY);
        Inventory inv = holder.createInventory(27, ChatColor.GOLD + "Private Lobby");
        fillBorder(inv);

        inv.setItem(12, MenuItems.action(Material.EMERALD,
                ChatColor.GREEN + "Create Lobby",
                "private-create", null,
                ChatColor.GRAY + "Generates a code to share"));

        inv.setItem(14, MenuItems.action(Material.ANVIL,
                ChatColor.AQUA + "Join by Code",
                "private-join", null,
                ChatColor.GRAY + "Type the code in chat"));

        inv.setItem(18, MenuItems.back());

        player.openInventory(inv);
    }

    private static void openPrivateInLobby(Player player, PrivateLobbyView lobby) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.PRIVATE_LOBBY);
        Inventory inv = holder.createInventory(36, ChatColor.GOLD + "Private Lobby — " + lobby.code());
        renderPrivateLobby(inv, lobby, player);
        player.openInventory(inv);
    }

    /**
     * Paint the full private-lobby view in place. Used for both initial open and
     * live refresh. The host gets the configuration controls (map → mode → optional
     * rules → start); everyone gets the member list + leave/back.
     */
    private static void renderPrivateLobby(Inventory inv, PrivateLobbyView lobby, Player player) {
        // Repaint the border first so leftover host-control items are cleared.
        fillBorder(inv);
        for (int s = 10; s <= 16; s++) inv.setItem(s, null);
        for (int s = 19; s <= 25; s++) inv.setItem(s, null);

        boolean isHost = lobby.host().equals(player.getUniqueId());

        inv.setItem(4, MenuItems.action(Material.NAME_TAG,
                ChatColor.YELLOW + "Code: " + ChatColor.WHITE + ChatColor.BOLD + lobby.code(),
                "noop", null,
                ChatColor.GRAY + "Share with friends",
                ChatColor.GRAY + "Host: " + ChatColor.WHITE + nameOf(lobby.host())));

        // Members 10..16 then 19..25
        int slot = 10;
        for (UUID memberUuid : lobby.members()) {
            if (slot == 16 || slot == 17) slot = 19;
            if (slot > 25) break;
            inv.setItem(slot, MenuItems.playerHead(memberUuid,
                    ChatColor.WHITE + nameOf(memberUuid)
                            + (memberUuid.equals(lobby.host()) ? ChatColor.GOLD + " (host)" : ""),
                    "noop", null, null));
            slot++;
        }

        if (isHost) renderHostControls(inv, lobby);

        inv.setItem(35, MenuItems.action(Material.BARRIER,
                ChatColor.RED + "Leave Lobby",
                "private-leave", null,
                ChatColor.GRAY + (isHost ? "Disbands the lobby" : "Removes you from this lobby")));
        inv.setItem(27, MenuItems.back());
    }

    /** Host-only bottom row: Select Map (28) → Select Mode (29) → optional rules (30/31) → Start (33). */
    private static void renderHostControls(Inventory inv, PrivateLobbyView lobby) {
        boolean hasMap = lobby.selectedMapFolder() != null;
        boolean hasMode = lobby.selectedMode() != null;

        inv.setItem(28, MenuItems.action(Material.FILLED_MAP,
                ChatColor.AQUA + "Select Map",
                "private-select-map", null,
                ChatColor.GRAY + "Map: " + ChatColor.WHITE + (hasMap ? lobby.selectedMapName() : "none"),
                ChatColor.GRAY + "Click to choose"));

        if (hasMap) {
            inv.setItem(29, MenuItems.action(MenuItems.modeIcon(lobby.selectedMode()),
                    ChatColor.AQUA + "Select Game Mode",
                    "private-select-mode", null,
                    ChatColor.GRAY + "Mode: " + ChatColor.WHITE + (hasMode ? lobby.selectedMode() : "none"),
                    ChatColor.GRAY + "Click to choose"));
        } else {
            inv.setItem(29, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Select Game Mode",
                    "noop", null,
                    ChatColor.RED + "Pick a map first"));
        }

        // Optional rules only appear once a mode is chosen.
        if (hasMode) {
            boolean fog = lobby.fogOfWar();
            inv.setItem(31, MenuItems.action(fog ? Material.SCULK_SENSOR : Material.GLOWSTONE_DUST,
                    (fog ? ChatColor.GREEN : ChatColor.GRAY) + "Fog of War: " + (fog ? "ON" : "OFF"),
                    "private-toggle-fog", null,
                    ChatColor.GRAY + "Optional rule — default OFF",
                    ChatColor.GRAY + "Click to toggle"));

            if (lobby.selectedMode().toLowerCase().startsWith("ffa")) {
                boolean lock = lobby.allianceLock();
                inv.setItem(30, MenuItems.action(lock ? Material.SHIELD : Material.IRON_DOOR,
                        (lock ? ChatColor.GREEN : ChatColor.GRAY) + "Lock Alliances: " + (lock ? "ON" : "OFF"),
                        "private-toggle-alliance", null,
                        ChatColor.GRAY + "FFA only — default ON",
                        ChatColor.GRAY + "Click to toggle"));
            }
        }

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
                canStart ? "private-start" : "noop", null,
                startLore.toArray(new String[0])));
    }

    // ---------- Host map / mode selection ----------

    public static void openHostMapSelect(Player player) {
        PrivateLobbyView lobby = RonLobby.matchQueue.getPrivateLobbyView(player.getUniqueId());
        if (lobby == null || !lobby.host().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[RoN] Only the host can pick the map.");
            return;
        }
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.PRIVATE_MAP_SELECT);
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

        inv.setItem(49, MenuItems.action(Material.ARROW, ChatColor.WHITE + "← Back",
                "back", null, ChatColor.GRAY + "Return to the lobby"));
        player.openInventory(inv);
    }

    private static ItemStack buildHostMapItem(MapOption map, String selectedFolder) {
        List<String> lore = new ArrayList<>();
        List<ModeOption> modes = map.modes() != null ? map.modes() : List.of();
        for (ModeOption m : modes) {
            lore.add(ChatColor.AQUA + "• " + m.name() + ChatColor.GRAY + " (" + m.players() + " players)");
        }
        lore.add("");
        boolean selected = map.folder().equals(selectedFolder);
        lore.add(selected ? ChatColor.GREEN + "Selected — click to change modes"
                          : ChatColor.YELLOW + "Click to choose a mode");
        return MenuItems.action(Material.FILLED_MAP,
                (selected ? ChatColor.GREEN : ChatColor.WHITE) + map.name(),
                "private-pick-map", map.folder(), lore);
    }

    public static void openHostModeSelect(Player player, String mapFolder) {
        PrivateLobbyView lobby = RonLobby.matchQueue.getPrivateLobbyView(player.getUniqueId());
        if (lobby == null || !lobby.host().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[RoN] Only the host can pick the mode.");
            return;
        }
        MapOption map = lobby.mapOptions().stream()
                .filter(o -> o.folder().equals(mapFolder)).findFirst().orElse(null);
        if (map == null) {
            player.sendMessage(ChatColor.RED + "[RoN] That map is no longer available.");
            return;
        }

        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.PRIVATE_MODE_SELECT, mapFolder);
        Inventory inv = holder.createInventory(27, ChatColor.GOLD + "Modes — " + map.name());
        fillBorder(inv);

        int slot = 10;
        for (ModeOption mode : map.modes()) {
            if (slot >= 17) break;
            inv.setItem(slot, buildHostModeItem(mapFolder, mode, mode.name().equals(lobby.selectedMode())));
            slot++;
        }

        inv.setItem(18, MenuItems.action(Material.ARROW, ChatColor.WHITE + "← Back",
                "back", null, ChatColor.GRAY + "Back to maps"));
        player.openInventory(inv);
    }

    private static ItemStack buildHostModeItem(String mapFolder, ModeOption mode, boolean selected) {
        return MenuItems.action(MenuItems.modeIcon(mode.name()),
                (selected ? ChatColor.GREEN : ChatColor.AQUA) + mode.name(),
                "private-pick-mode", mapFolder + "/" + mode.name(),
                ChatColor.GRAY + "" + mode.players() + " players",
                "",
                selected ? ChatColor.GREEN + "Selected" : ChatColor.GREEN + "Click to choose");
    }

    // ---------- Vote ----------

    public static void openVote(Player player) {
        MatchQueue q = RonLobby.matchQueue;
        if (!q.isVoteActive()) {
            player.sendMessage(ChatColor.RED + "[RoN] No vote is active.");
            return;
        }
        openVote(player, q.getVoteMapOptions());
    }

    public static void openVote(Player player, List<MapOption> options) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.VOTE);
        Inventory inv = holder.createInventory(54, ChatColor.GOLD + "Vote for a Map");
        for (int i = 0; i < 9; i++) inv.setItem(i, MenuItems.border());
        for (int i = 45; i < 54; i++) inv.setItem(i, MenuItems.border());

        Map<MatchQueue.CombinedOption, Integer> counts = RonLobby.matchQueue.getVoteCounts();

        int slot = 10;
        for (MapOption map : options) {
            if (slot >= 43) break;            // reserve slot 43 for Random
            if (slot % 9 == 8) slot += 2;     // skip side border, jump into next row
            inv.setItem(slot, buildMapItem(map, voteCountForMap(map.folder(), counts)));
            slot++;
        }

        inv.setItem(43, buildRandomItem(voteCountForMap(null, counts)));

        player.openInventory(inv);
    }

    private static ItemStack buildMapItem(MapOption map, int voteCount) {
        List<String> lore = new ArrayList<>();
        List<ModeOption> modes = map.modes() != null ? map.modes() : List.of();
        for (ModeOption m : modes) {
            lore.add(ChatColor.AQUA + "• " + m.name()
                    + ChatColor.GRAY + " (" + m.players() + " players)");
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "Votes: " + ChatColor.WHITE + voteCount);
        lore.add(modes.size() > 1 ? ChatColor.YELLOW + "Click to pick a mode"
                                  : ChatColor.GREEN + "Click to vote");
        ItemStack item = MenuItems.action(Material.FILLED_MAP,
                voteCountLabel(ChatColor.WHITE + map.name(), voteCount),
                "vote-map", map.folder(), lore);
        return MenuItems.withAmount(item, Math.max(1, voteCount));
    }

    private static ItemStack buildRandomItem(int voteCount) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Let the server decide");
        lore.add("");
        lore.add(ChatColor.GRAY + "Votes: " + ChatColor.WHITE + voteCount);
        ItemStack item = MenuItems.action(Material.NETHER_STAR,
                voteCountLabel(ChatColor.LIGHT_PURPLE + "Random", voteCount),
                "vote-random", null, lore);
        return MenuItems.withAmount(item, Math.max(1, voteCount));
    }

    /** Suffix "  ×N" to a display name when N > 0 so the count is unmissable. */
    private static String voteCountLabel(String baseName, int voteCount) {
        if (voteCount <= 0) return baseName;
        return baseName + ChatColor.GRAY + "  ×" + ChatColor.YELLOW + voteCount;
    }

    public static void openVoteModes(Player player, String mapFolder) {
        MatchQueue q = RonLobby.matchQueue;
        MapOption map = q.getVoteMapOptions().stream()
                .filter(o -> o.folder().equals(mapFolder)).findFirst().orElse(null);
        if (map == null) {
            player.sendMessage(ChatColor.RED + "[RoN] That map is no longer available.");
            return;
        }

        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.VOTE_MODES, mapFolder);
        Inventory inv = holder.createInventory(27, ChatColor.GOLD + "Modes — " + map.name());
        fillBorder(inv);

        Map<MatchQueue.CombinedOption, Integer> counts = q.getVoteCounts();

        int slot = 10;
        for (ModeOption mode : map.modes()) {
            if (slot >= 17) break;
            inv.setItem(slot, buildModeItem(mapFolder, mode, voteCountForMode(mapFolder, mode.name(), counts)));
            slot++;
        }

        inv.setItem(18, MenuItems.back());

        player.openInventory(inv);
    }

    private static ItemStack buildModeItem(String mapFolder, ModeOption mode, int voteCount) {
        Material icon = MenuItems.modeIcon(mode.name());
        ItemStack item = MenuItems.action(icon,
                voteCountLabel(ChatColor.AQUA + mode.name(), voteCount),
                "vote-mode", mapFolder + "/" + mode.name(),
                ChatColor.GRAY + "" + mode.players() + " players",
                "",
                ChatColor.GRAY + "Votes: " + ChatColor.WHITE + voteCount,
                ChatColor.GREEN + "Click to vote");
        return MenuItems.withAmount(item, Math.max(1, voteCount));
    }

    // ---------- Vote tally helpers / live refresh ----------

    private static int sumVotes(Map<MatchQueue.CombinedOption, Integer> counts,
                                java.util.function.Predicate<MatchQueue.CombinedOption> match) {
        int total = 0;
        for (var e : counts.entrySet()) {
            if (match.test(e.getKey())) total += e.getValue();
        }
        return total;
    }

    private static int voteCountForMap(String mapFolder, Map<MatchQueue.CombinedOption, Integer> counts) {
        return sumVotes(counts, c -> mapFolder == null
                ? c.mapFolder() == null
                : mapFolder.equals(c.mapFolder()));
    }

    private static int voteCountForMode(String mapFolder, String modeName,
                                        Map<MatchQueue.CombinedOption, Integer> counts) {
        return sumVotes(counts, c -> mapFolder.equals(c.mapFolder()) && modeName.equals(c.modeName()));
    }

    /** Walks all open vote menus and rewrites their item stacks with current counts. */
    public static void refreshVoteMenus() {
        MatchQueue q = RonLobby.matchQueue;
        if (!q.isVoteActive()) return;
        Map<MatchQueue.CombinedOption, Integer> counts = q.getVoteCounts();
        List<MapOption> options = q.getVoteMapOptions();

        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof RonMenuHolder holder)) continue;
            switch (holder.menuId()) {
                case VOTE -> refreshVoteMain(top, options, counts);
                case VOTE_MODES -> refreshVoteModes(top, holder.payload(), options, counts);
                default -> { /* no-op */ }
            }
        }
    }

    private static void refreshVoteMain(Inventory inv, List<MapOption> options,
                                        Map<MatchQueue.CombinedOption, Integer> counts) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            String action = MenuItems.readAction(stack);
            if (action == null) continue;
            if (action.equals("vote-map")) {
                String folder = MenuItems.readPayload(stack);
                MapOption map = options.stream().filter(o -> o.folder().equals(folder)).findFirst().orElse(null);
                if (map != null) inv.setItem(i, buildMapItem(map, voteCountForMap(folder, counts)));
            } else if (action.equals("vote-random")) {
                inv.setItem(i, buildRandomItem(voteCountForMap(null, counts)));
            }
        }
    }

    private static void refreshVoteModes(Inventory inv, String mapFolder, List<MapOption> options,
                                         Map<MatchQueue.CombinedOption, Integer> counts) {
        if (mapFolder == null) return;
        MapOption map = options.stream().filter(o -> o.folder().equals(mapFolder)).findFirst().orElse(null);
        if (map == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!"vote-mode".equals(MenuItems.readAction(stack))) continue;
            String payload = MenuItems.readPayload(stack);
            if (payload == null) continue;
            int slash = payload.indexOf('/');
            if (slash < 0) continue;
            String modeName = payload.substring(slash + 1);
            ModeOption mode = map.modes().stream()
                    .filter(m -> m.name().equals(modeName)).findFirst().orElse(null);
            if (mode != null) inv.setItem(i, buildModeItem(mapFolder, mode, voteCountForMode(mapFolder, modeName, counts)));
        }
    }

    /** Opens the vote menu for every voter and starts the countdown bossbar. */
    public static void openVoteForAll(Set<UUID> voters, List<MapOption> options, int totalSeconds) {
        if (!settings.autoOpenVote() && !settings.bossbarVote()) return;

        if (settings.autoOpenVote()) {
            for (UUID uuid : voters) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) openVote(p, options);
            }
        }
        if (settings.bossbarVote()) {
            VoteBossBar.startVote(voters, totalSeconds, RonLobby.matchQueue::getVoteSecondsRemaining);
        }
        SoundEffects.voteStart(voters);
    }

    public static void clearVoteOverlays() {
        VoteBossBar.stopAll();
    }

    // ---------- Live refresh for Hub + Private Lobby ----------

    /**
     * Re-renders open Hub and Private Lobby inventories in place. Called after
     * any state mutation that could change what those menus display: queue
     * size, private lobby member list, instance status push.
     */
    public static void refreshLobbyMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof RonMenuHolder holder)) continue;
            switch (holder.menuId()) {
                case HUB -> rebuildHub(top, p);
                case PRIVATE_LOBBY -> rebuildPrivate(top, p);
                default -> { /* others use refreshVoteMenus or open on demand */ }
            }
        }
    }

    private static void rebuildHub(Inventory inv, Player player) {
        MatchQueue q = RonLobby.matchQueue;
        boolean inQueue = q.isInAnyQueue(player.getUniqueId());
        inv.setItem(10, MenuItems.action(Material.CLOCK,
                ChatColor.YELLOW + (inQueue ? "Leave Queue" : "Join Queue"),
                "queue-toggle", null,
                ChatColor.GRAY + "Public queue: " + ChatColor.WHITE + q.getPublicQueueSize() + " players",
                ChatColor.GRAY + "Click to " + (inQueue ? "leave" : "join")));
        fillStatusRow(inv, player);
    }

    private static void rebuildPrivate(Inventory inv, Player player) {
        PrivateLobbyView lobby = RonLobby.matchQueue.getPrivateLobbyView(player.getUniqueId());
        if (lobby == null || inv.getSize() != 36) {
            // Layout no longer matches (lobby disbanded or we joined one) —
            // re-open so the player gets the right inventory size.
            openPrivate(player);
            return;
        }
        renderPrivateLobby(inv, lobby, player);
    }

    // ---------- Matches ----------

    public static void openMatches(Player player) {
        LobbyMessaging.requestServerInfo(info -> renderMatches(player, info));
    }

    private static void renderMatches(Player player, JsonObject info) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.MATCHES);
        Inventory inv = holder.createInventory(27, ChatColor.AQUA + "Running Matches");
        fillBorder(inv);

        inv.setItem(18, MenuItems.back());

        if (info == null || !info.has("matches")) {
            inv.setItem(13, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "No matches running", "noop", null));
            player.openInventory(inv);
            return;
        }

        JsonObject matches = info.getAsJsonObject("matches");
        if (matches.size() == 0) {
            inv.setItem(13, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "No matches running", "noop", null));
            player.openInventory(inv);
            return;
        }

        int slot = 10;
        for (Map.Entry<String, JsonElement> entry : matches.entrySet()) {
            if (slot >= 17) break;
            JsonObject m = entry.getValue().getAsJsonObject();
            String instance = entry.getKey();
            String map = m.get("map").getAsString();
            int players = m.get("players").getAsInt();
            long seconds = m.get("elapsedSeconds").getAsLong();
            int spectators = m.has("spectatorCount") ? m.get("spectatorCount").getAsInt() : 0;
            int maxSpec = m.has("maxSpectators") ? m.get("maxSpectators").getAsInt() : 0;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Map: " + ChatColor.WHITE + map);
            lore.add(ChatColor.GRAY + "Players: " + ChatColor.WHITE + players);
            lore.add(ChatColor.GRAY + "Elapsed: " + ChatColor.WHITE + time);
            if (maxSpec > 0) {
                lore.add(ChatColor.GRAY + "Spectators: " + ChatColor.WHITE + spectators + "/" + maxSpec);
            }
            lore.add("");
            boolean full = maxSpec > 0 && spectators >= maxSpec;
            lore.add(full ? ChatColor.RED + "Spectator slots full" : ChatColor.GREEN + "Click to spectate");

            inv.setItem(slot, MenuItems.action(Material.FILLED_MAP,
                    ChatColor.YELLOW + instance, full ? "noop" : "spectate", instance, lore));
            slot++;
        }

        player.openInventory(inv);
    }

    // ---------- Leaderboard ----------

    public static void openLeaderboard(Player player) {
        if (!LobbyMessaging.isRankedEnabled()) {
            player.sendMessage(ChatColor.RED + "[RoN] Ranked is disabled on this network.");
            return;
        }
        LobbyMessaging.requestLeaderboard(10, response -> renderLeaderboard(player, response));
    }

    private static void renderLeaderboard(Player player, JsonObject response) {
        RonMenuHolder holder = new RonMenuHolder(RonMenuHolder.MenuId.LEADERBOARD);
        Inventory inv = holder.createInventory(36, ChatColor.GOLD + "Top Players");
        fillBorder(inv);

        inv.setItem(27, MenuItems.back());

        if (response == null || response.has("error") || !response.has("players")) {
            inv.setItem(13, MenuItems.action(Material.GRAY_DYE,
                    ChatColor.GRAY + "Leaderboard unavailable", "noop", null));
            player.openInventory(inv);
            return;
        }

        JsonArray players = response.getAsJsonArray("players");
        int[] slots = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
        for (int i = 0; i < players.size() && i < slots.length; i++) {
            JsonObject s = players.get(i).getAsJsonObject();
            String name = s.get("name").getAsString();
            int points = s.get("points").getAsInt();
            int wins = s.get("wins").getAsInt();
            int losses = s.get("losses").getAsInt();
            String rank = ScoringUtil.getRank(points);
            ChatColor color = LobbyUI.getRankChatColor(points);

            inv.setItem(slots[i], MenuItems.playerHead(uuidByName(name),
                    color + "#" + (i + 1) + " " + name,
                    "noop", null,
                    List.of(
                            ChatColor.GRAY + "Rank: " + color + rank,
                            ChatColor.GRAY + "Points: " + ChatColor.WHITE + points,
                            ChatColor.GRAY + "Record: " + ChatColor.WHITE + wins + "W / " + losses + "L"
                    )));
        }

        player.openInventory(inv);
    }

    // ---------- Rank ----------

    public static void openRank(Player player) {
        if (!LobbyMessaging.isRankedEnabled()) {
            player.sendMessage(ChatColor.RED + "[RoN] Ranked is disabled on this network.");
            return;
        }
        LobbyMessaging.requestRank(player.getUniqueId().toString(), player.getName(), response -> {
            if (response == null || response.has("error")) {
                player.sendMessage(ChatColor.RED + "[RoN] Failed to load your stats.");
                return;
            }
            int points = response.get("points").getAsInt();
            int wins = response.get("wins").getAsInt();
            int losses = response.get("losses").getAsInt();
            String rank = ScoringUtil.getRank(points);
            ChatColor color = LobbyUI.getRankChatColor(points);
            player.sendMessage("");
            player.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "--- Your Stats ---");
            player.sendMessage(" Rank: " + color + rank);
            player.sendMessage(" Points: " + points);
            player.sendMessage(String.format(" Record: %dW / %dL", wins, losses));
            player.sendMessage("");
        });
    }

    // ---------- Helpers ----------

    /** Fill a full border (top + bottom rows, left + right columns) for any inventory size. */
    private static void fillBorder(Inventory inv) {
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
    private static void fillBorder45(Inventory inv) {
        ItemStack pane = MenuItems.border();
        for (int i = 0; i < 9; i++) inv.setItem(i, pane);
        for (int i = 18; i < 27; i++) inv.setItem(i, pane);
        for (int i = 36; i < 45; i++) inv.setItem(i, pane);
        inv.setItem(9, pane);
        inv.setItem(17, pane);
        inv.setItem(27, pane);
        inv.setItem(35, pane);
    }

    private static String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    private static UUID uuidByName(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId();
    }
}
