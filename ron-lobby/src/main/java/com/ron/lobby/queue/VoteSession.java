package com.ron.lobby.queue;

import com.ron.lobby.RonLobby;
import com.ron.lobby.ui.menu.MenuService;
import com.ron.lobby.ui.menu.SoundEffects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.ron.lobby.queue.MatchQueue.CombinedOption;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;

/**
 * Combined map+mode vote held over a fixed window. Owns its own timer and tally.
 * Players type {@code /vote <number> [mode]} — the number picks a map (or the
 * Random sentinel), the mode picks one of that map's modes when it has more
 * than one. The winner is delivered via callback so the caller decides what
 * to do with it.
 */
class VoteSession {

    /** Under this many seconds, the timer announces every second; it also caps the early wrap-up. */
    private static final int FINAL_COUNTDOWN_SECONDS = 10;
    /** Above the final countdown, announce remaining time on this cadence. */
    private static final int REMINDER_INTERVAL_SECONDS = 15;

    private final RonLobby plugin;

    private List<MapOption> mapOptions = null;
    private final Map<UUID, CombinedOption> votes = new HashMap<>();
    private int task = -1;
    private int secondsRemaining = 0;

    VoteSession(RonLobby plugin) {
        this.plugin = plugin;
    }

    /**
     * Show the prompt + start a vote timer. Auto-resolves trivially when there
     * is no real choice to make (no maps, or one map with at most one mode).
     */
    void start(Set<UUID> voters, List<MapOption> options, int voteSeconds, Consumer<CombinedOption> onWinner) {
        mapOptions = new ArrayList<>(options);

        if (mapOptions.isEmpty()) {
            broadcast(voters, "No maps available — picking random.");
            onWinner.accept(new CombinedOption(null, "Random", null, 0));
            return;
        }

        if (mapOptions.size() == 1) {
            MapOption only = mapOptions.get(0);
            List<ModeOption> modes = only.modes() != null ? only.modes() : List.of();
            if (modes.size() <= 1) {
                ModeOption picked = modes.isEmpty() ? null : modes.get(0);
                String mode = picked != null ? picked.name() : null;
                int players = picked != null ? picked.players() : 0;
                CombinedOption chosen = new CombinedOption(only.folder(), only.name(), mode, players);
                broadcast(voters, ChatColor.GREEN + "Selected: " + describe(chosen));
                onWinner.accept(chosen);
                return;
            }
        }

        votes.clear();
        secondsRemaining = voteSeconds;
        int randomIdx = mapOptions.size() + 1;

        broadcast(voters, ChatColor.YELLOW + "Vote for a map! Type "
                + ChatColor.WHITE + "/vote <number> [mode]"
                + ChatColor.YELLOW + "  (" + voteSeconds + "s)");
        broadcast(voters, ChatColor.GRAY + "Example: /vote 1 ffa_4  — pick map #1 with mode ffa_4");
        for (int i = 0; i < mapOptions.size(); i++) {
            broadcast(voters, ChatColor.WHITE + "  " + (i + 1) + ". " + describeMap(mapOptions.get(i)));
        }
        broadcast(voters, ChatColor.WHITE + "  " + randomIdx + ". Random");

        task = new BukkitRunnable() {
            @Override
            public void run() {
                secondsRemaining--;
                if (secondsRemaining > 0 && (secondsRemaining <= FINAL_COUNTDOWN_SECONDS
                        || secondsRemaining % REMINDER_INTERVAL_SECONDS == 0)) {
                    broadcast(voters, "Vote: " + secondsRemaining + "s remaining...");
                }
                if (secondsRemaining <= 0) {
                    cancel();
                    task = -1;
                    tally(voters, onWinner);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L).getTaskId();
    }

    void cast(UUID uuid, int choice, String mode, Set<UUID> participants) {
        if (mapOptions == null) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] No active vote.");
            return;
        }
        if (!participants.contains(uuid)) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] You're not part of the current match.");
            return;
        }
        int randomIdx = mapOptions.size() + 1;
        if (choice < 1 || choice > randomIdx) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] Invalid choice. Pick 1-" + randomIdx);
            return;
        }

        CombinedOption resolved;
        if (choice == randomIdx) {
            resolved = new CombinedOption(null, "Random", null, 0);
        } else {
            Optional<CombinedOption> picked = resolveMapVote(uuid, mapOptions.get(choice - 1), mode);
            if (picked.isEmpty()) return; // resolveMapVote already told the player why
            resolved = picked.get();
        }

        votes.put(uuid, resolved);
        Player player = Bukkit.getPlayer(uuid);
        String name = player != null ? player.getName() : "?";
        broadcast(participants, name + " voted.");
        MenuService.refreshVoteMenus();
        SoundEffects.voteCast(player);

        if (votes.size() >= participants.size() && secondsRemaining > FINAL_COUNTDOWN_SECONDS) {
            secondsRemaining = FINAL_COUNTDOWN_SECONDS;
            broadcast(participants, ChatColor.YELLOW + "Everyone voted — wrapping up in "
                    + FINAL_COUNTDOWN_SECONDS + "s.");
        }
    }

    /**
     * Resolve a map-number vote into a CombinedOption, picking/validating the mode.
     * Returns empty (after messaging the player) when the mode is ambiguous or invalid.
     */
    private Optional<CombinedOption> resolveMapVote(UUID uuid, MapOption map, String mode) {
        List<ModeOption> modes = map.modes() != null ? map.modes() : List.of();
        ModeOption matched;
        if (modes.isEmpty()) {
            matched = null;
        } else if (mode == null || mode.isBlank()) {
            if (modes.size() == 1) {
                matched = modes.get(0);
            } else {
                tellPlayer(uuid, ChatColor.RED + "[RoN] " + map.name()
                        + " has multiple modes — specify one: " + modeList(modes));
                return Optional.empty();
            }
        } else {
            matched = modes.stream()
                    .filter(m -> m.name().equalsIgnoreCase(mode))
                    .findFirst().orElse(null);
            if (matched == null) {
                tellPlayer(uuid, ChatColor.RED + "[RoN] Mode '" + mode + "' not available for "
                        + map.name() + ". Available: " + modeList(modes));
                return Optional.empty();
            }
        }
        String chosenMode = matched != null ? matched.name() : null;
        int players = matched != null ? matched.players() : 0;
        return Optional.of(new CombinedOption(map.folder(), map.name(), chosenMode, players));
    }

    boolean isActive() {
        return mapOptions != null;
    }

    boolean hasVoted(UUID uuid) {
        return votes.containsKey(uuid);
    }

    List<MapOption> getMapOptions() {
        return mapOptions == null ? List.of() : List.copyOf(mapOptions);
    }

    int getSecondsRemaining() {
        return secondsRemaining;
    }

    Map<CombinedOption, Integer> getVoteCounts() {
        Map<CombinedOption, Integer> counts = new HashMap<>();
        for (CombinedOption v : votes.values()) counts.merge(v, 1, Integer::sum);
        return counts;
    }

    void clear() {
        mapOptions = null;
        votes.clear();
        if (task != -1) {
            Bukkit.getScheduler().cancelTask(task);
            task = -1;
        }
        MenuService.clearVoteOverlays();
    }

    void removeVoter(UUID uuid) {
        if (votes.remove(uuid) != null) MenuService.refreshVoteMenus();
    }

    private void tally(Set<UUID> voters, Consumer<CombinedOption> onWinner) {
        if (mapOptions == null || mapOptions.isEmpty()) return;

        CombinedOption winner;
        if (votes.isEmpty()) {
            MapOption map = mapOptions.get(ThreadLocalRandom.current().nextInt(mapOptions.size()));
            List<ModeOption> modes = map.modes() != null ? map.modes() : List.of();
            ModeOption picked = modes.isEmpty() ? null
                    : modes.get(ThreadLocalRandom.current().nextInt(modes.size()));
            String mode = picked != null ? picked.name() : null;
            int players = picked != null ? picked.players() : 0;
            winner = new CombinedOption(map.folder(), map.name(), mode, players);
        } else {
            Map<CombinedOption, Integer> counts = new HashMap<>();
            for (CombinedOption v : votes.values()) counts.merge(v, 1, Integer::sum);
            int max = counts.values().stream().mapToInt(i -> i).max().orElse(0);
            List<CombinedOption> tied = counts.entrySet().stream()
                    .filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
            winner = tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
        }

        broadcast(voters, ChatColor.GREEN + "Selected: " + describe(winner));
        onWinner.accept(winner);
    }

    private static String describeMap(MapOption opt) {
        List<ModeOption> modes = opt.modes() != null ? opt.modes() : List.of();
        if (modes.isEmpty()) return opt.name();
        return opt.name() + ChatColor.GRAY + " — " + ChatColor.AQUA + modeList(modes);
    }

    private static String modeList(List<ModeOption> modes) {
        return modes.stream().map(ModeOption::name).collect(Collectors.joining(", "));
    }

    private static String describe(CombinedOption opt) {
        if (opt.mapFolder() == null) return "Random";
        if (opt.modeName() == null) return opt.mapName();
        return opt.mapName() + " — " + opt.modeName();
    }

    private static void broadcast(Set<UUID> voters, String message) {
        if (!MatchQueue.uiSettings().chatMessages()) return;
        for (UUID uuid : voters) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(ChatColor.GREEN + "[RoN] " + message);
        }
    }

    private static void tellPlayer(UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }
}
