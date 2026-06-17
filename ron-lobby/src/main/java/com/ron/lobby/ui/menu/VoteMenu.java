package com.ron.lobby.ui.menu;

import com.ron.lobby.RonLobby;
import com.ron.lobby.queue.MatchQueue;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;
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

/** Builds the map + mode vote menus, keeps live vote tallies in sync, and drives the vote countdown overlay. */
public final class VoteMenu {

    private VoteMenu() {}

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
        for (int i = 45; i < 53; i++) inv.setItem(i, MenuItems.border());

        Map<MatchQueue.CombinedOption, Integer> counts = RonLobby.matchQueue.getVoteCounts();

        int slot = 9;
        for (MapOption map : options) {
            if (slot > 44) break;             // 36 map slots (rows 2-5)
            inv.setItem(slot, buildMapItem(map, voteCountForMap(map.folder(), counts)));
            slot++;
        }

        inv.setItem(53, buildRandomItem(voteCountForMap(null, counts)));

        player.openInventory(inv);
    }

    private static ItemStack buildMapItem(MapOption map, int voteCount) {
        boolean available = map.instances() > 0;
        List<ModeOption> modes = MenuSupport.modesOf(map);
        List<String> lore = MenuSupport.modesLore(map);
        lore.add("");
        lore.add(ChatColor.GRAY + "Votes: " + ChatColor.WHITE + voteCount);
        lore.add(ChatColor.GRAY + "Available on " + ChatColor.WHITE + map.instances()
                + ChatColor.GRAY + " instance" + (map.instances() == 1 ? "" : "s"));
        if (!available) {
            lore.add(ChatColor.RED + "Unavailable — all instances busy");
        } else {
            lore.add(modes.size() > 1 ? ChatColor.YELLOW + "Click to pick a mode"
                                      : ChatColor.GREEN + "Click to vote");
        }
        Material icon = available ? Material.FILLED_MAP : Material.GRAY_DYE;
        String title = available ? ChatColor.WHITE + map.name()
                                 : ChatColor.GRAY + map.name() + ChatColor.RED + " (Unavailable)";
        ItemStack item = MenuItems.action(icon,
                voteCountLabel(title, voteCount),
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

        Map<MatchQueue.CombinedOption, Integer> counts = q.getVoteCounts();
        MenuSupport.openModeSelect(player, RonMenuHolder.MenuId.VOTE_MODES, map,
                mode -> buildModeItem(mapFolder, mode, voteCountForMode(mapFolder, mode.name(), counts)));
    }

    private static ItemStack buildModeItem(String mapFolder, ModeOption mode, int voteCount) {
        ItemStack item = MenuSupport.modeItem(mapFolder, mode, "vote-mode",
                voteCountLabel(ChatColor.AQUA + mode.name(), voteCount),
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
        if (!MenuSupport.settings().autoOpenVote() && !MenuSupport.settings().bossbarVote()) return;

        if (MenuSupport.settings().autoOpenVote()) {
            for (UUID uuid : voters) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) openVote(p, options);
            }
        }
        if (MenuSupport.settings().bossbarVote()) {
            VoteBossBar.startVote(voters, totalSeconds, RonLobby.matchQueue::getVoteSecondsRemaining);
        }
        SoundEffects.voteStart(voters);
    }

    public static void clearVoteOverlays() {
        VoteBossBar.stopAll();
    }
}
