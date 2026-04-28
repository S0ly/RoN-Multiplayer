package com.ron.lobby.queue;

import com.ron.lobby.RonLobby;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import com.ron.lobby.queue.MatchQueue.CombinedOption;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;

/**
 * Combined map+mode vote held over a fixed window. Owns its own timer and tally.
 * The winner is delivered via callback so the caller decides what to do with it.
 */
class VoteSession {

    private final RonLobby plugin;

    private List<CombinedOption> options = null;
    private final Map<UUID, Integer> votes = new HashMap<>();
    private int task = -1;
    private int secondsRemaining = 0;

    VoteSession(RonLobby plugin) {
        this.plugin = plugin;
    }

    /**
     * Build options from the proxy's map list, broadcast prompt + start a vote timer.
     * If only one (or zero) real option exists, calls {@code onWinner} immediately and skips the vote.
     */
    void start(Set<UUID> voters, List<MapOption> mapOptions, int voteSeconds, Consumer<CombinedOption> onWinner) {
        options = new ArrayList<>();
        for (MapOption opt : mapOptions) {
            if (opt.modes() == null || opt.modes().isEmpty()) {
                options.add(new CombinedOption(opt.folder(), opt.name(), null));
            } else {
                for (ModeOption mo : opt.modes()) {
                    options.add(new CombinedOption(opt.folder(), opt.name(), mo.name()));
                }
            }
        }
        options.add(new CombinedOption(null, "Random", null));

        if (options.size() <= 1) {
            broadcast(voters, "No maps available — picking random.");
            onWinner.accept(new CombinedOption(null, "Random", null));
            return;
        }

        if (options.size() == 2) {
            CombinedOption only = options.get(0);
            broadcast(voters, ChatColor.GREEN + "Selected: " + describe(only));
            onWinner.accept(only);
            return;
        }

        votes.clear();
        secondsRemaining = voteSeconds;

        broadcast(voters, ChatColor.YELLOW + "Vote for map + mode! Type /vote <number>  (" + voteSeconds + "s)");
        for (int i = 0; i < options.size(); i++) {
            broadcast(voters, ChatColor.WHITE + "  " + (i + 1) + ". " + describe(options.get(i)));
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                secondsRemaining--;
                if (secondsRemaining > 0 && (secondsRemaining <= 10 || secondsRemaining % 15 == 0)) {
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

    void cast(UUID uuid, int choice, Set<UUID> participants) {
        if (options == null) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] No active vote.");
            return;
        }
        if (!participants.contains(uuid)) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] You're not part of the current match.");
            return;
        }
        if (choice < 1 || choice > options.size()) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] Invalid choice. Pick 1-" + options.size());
            return;
        }
        votes.put(uuid, choice);
        Player player = Bukkit.getPlayer(uuid);
        String name = player != null ? player.getName() : "?";
        broadcast(participants, name + " voted for " + describe(options.get(choice - 1)));
    }

    boolean isActive() {
        return options != null;
    }

    void clear() {
        options = null;
        votes.clear();
        if (task != -1) {
            Bukkit.getScheduler().cancelTask(task);
            task = -1;
        }
    }

    void removeVoter(UUID uuid) {
        votes.remove(uuid);
    }

    private void tally(Set<UUID> voters, Consumer<CombinedOption> onWinner) {
        if (options == null || options.isEmpty()) return;

        int winnerIdx;
        if (votes.isEmpty()) {
            // Skip the explicit Random slot when picking unanimously-untyped default.
            int realCount = Math.max(1, options.size() - 1);
            winnerIdx = ThreadLocalRandom.current().nextInt(realCount);
        } else {
            Map<Integer, Integer> counts = new HashMap<>();
            for (int v : votes.values()) counts.merge(v, 1, Integer::sum);
            int max = counts.values().stream().mapToInt(i -> i).max().orElse(0);
            List<Integer> tied = counts.entrySet().stream()
                    .filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
            winnerIdx = tied.get(ThreadLocalRandom.current().nextInt(tied.size())) - 1;
        }

        CombinedOption winner = options.get(winnerIdx);
        broadcast(voters, ChatColor.GREEN + "Selected: " + describe(winner));
        onWinner.accept(winner);
    }

    private static String describe(CombinedOption opt) {
        if (opt.mapFolder() == null) return "Random";
        if (opt.modeName() == null) return opt.mapName();
        return opt.mapName() + " — " + opt.modeName();
    }

    private static void broadcast(Set<UUID> voters, String message) {
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
