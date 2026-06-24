package com.ron.lobby.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.UiSettings;
import com.ron.lobby.ui.menu.MenuSupport;
import com.ron.lobby.ui.menu.SoundEffects;
import com.ron.lobby.ui.menu.VoteMenu;
import com.ron.lobby.ui.menu.VoteBossBar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class MatchQueue implements Listener {

    private static final int DEFAULT_FILL_SECONDS = 120;
    private static final int DEFAULT_VOTE_SECONDS = 60;
    /** Re-queue players if the assigned instance never reports ready (default 2 minutes, in ticks). */
    private long transferTimeoutTicks = 2400L;

    private final RonLobby plugin;
    private final VoteSession voteSession;

    // Phase machine — public queue is the primary driver, custom lobbies share the lock.
    private enum Phase { OPEN, FILLING, LOCKED_VOTING, WAITING_FOR_INSTANCE }
    private Phase phase = Phase.OPEN;

    // Public matchmaking queue can be disabled entirely (custom lobbies only).
    private boolean publicQueueEnabled = true;

    // Public queue: open → fill → lock → vote → transfer
    private final Set<UUID> publicQueue = new LinkedHashSet<>();
    // Late joiners during LOCKED_VOTING / WAITING_FOR_INSTANCE — promoted into publicQueue once free.
    private final Set<UUID> nextQueue = new LinkedHashSet<>();

    private int fillTask = -1;
    private int fillSecondsRemaining = 0;

    private int fillSeconds = DEFAULT_FILL_SECONDS;
    private int voteSeconds = DEFAULT_VOTE_SECONDS;

    // Custom lobbies (registry + host configuration) live in their own manager;
    // the shared phase/pending lock below coordinates their match starts.
    private final CustomLobbyManager customLobbies;

    // Pending match state (shared across public/custom — only one match at a time)
    private String pendingInstance = null;
    private Set<UUID> pendingPlayers = null;
    private boolean pendingCustomMatch = false;
    private int pendingMin = 0;
    private String pendingMapName = null;
    private String pendingMode = null;
    private int transferTimeoutTask = -1;

    public record ModeOption(String name, int players) {}
    public record MapOption(String folder, String name, List<ModeOption> modes, int instances) {}
    public record CombinedOption(String mapFolder, String mapName, String modeName, int players) {}

    /** Sentinel map folder meaning "let the server pick a map" — the proxy resolves it in findMatchForMap. */
    public static final String RANDOM_MAP_FOLDER = "random";
    public static final String RANDOM_MAP_NAME = "Random";

    /**
     * Synthetic "Random" map whose modes are the union (by name) of every mode
     * offered across {@code options}. Lets the host pick a mode for a random map
     * exactly as they would for a real one.
     */
    public static MapOption randomMapOption(List<MapOption> options) {
        java.util.LinkedHashMap<String, ModeOption> union = new java.util.LinkedHashMap<>();
        if (options != null) {
            for (MapOption map : options) {
                if (map.modes() == null) continue;
                for (ModeOption mode : map.modes()) union.putIfAbsent(mode.name(), mode);
            }
        }
        return new MapOption(RANDOM_MAP_FOLDER, RANDOM_MAP_NAME,
                new java.util.ArrayList<>(union.values()), 0);
    }

    // Server capabilities
    private int minPlayers = 2;
    private int availableInstances = 0;

    // UI settings — drives chat-message gating. Set by RonLobby on enable.
    private static UiSettings uiSettings = new UiSettings(true, true, true, true, false, true, true, false, false, true);
    public static void setUiSettings(UiSettings s) { uiSettings = s; }
    public static UiSettings uiSettings() { return uiSettings; }

    public MatchQueue(RonLobby plugin) {
        this.plugin = plugin;
        this.voteSession = new VoteSession(plugin);
        this.customLobbies = new CustomLobbyManager(this);
    }

    // --- Accessors used by CustomLobbyManager to consult the shared lock ---
    boolean instancesAvailable() { return availableInstances > 0; }
    boolean waitingForInstance() { return phase == Phase.WAITING_FOR_INSTANCE; }

    public void configureTimings(int fillSeconds, int voteSeconds, int minPlayers,
                                 int transferTimeoutSeconds, int voteFinalCountdownSeconds,
                                 int voteReminderIntervalSeconds) {
        if (fillSeconds > 0) this.fillSeconds = fillSeconds;
        if (voteSeconds > 0) this.voteSeconds = voteSeconds;
        if (minPlayers > 0) this.minPlayers = minPlayers;
        if (transferTimeoutSeconds > 0) this.transferTimeoutTicks = transferTimeoutSeconds * 20L;
        voteSession.configure(voteFinalCountdownSeconds, voteReminderIntervalSeconds);
    }

    public void setPublicQueueEnabled(boolean enabled) { this.publicQueueEnabled = enabled; }
    public boolean isPublicQueueEnabled() { return publicQueueEnabled; }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        leaveQueue(uuid, event.getPlayer().getName());
    }

    // --- Public Queue ---

    public void joinPublicQueue(UUID uuid, String name) {
        if (!publicQueueEnabled) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] Public matchmaking is disabled — create a custom lobby instead.");
            return;
        }
        if (availableInstances <= 0 && phase != Phase.WAITING_FOR_INSTANCE) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] No game servers available right now.");
            return;
        }

        if (publicQueue.contains(uuid) || nextQueue.contains(uuid) || customLobbies.isInLobby(uuid)) {
            LobbyChat.tellPlayer(uuid, ChatColor.YELLOW + "[RoN] You're already in a queue.");
            return;
        }

        if (phase == Phase.LOCKED_VOTING || phase == Phase.WAITING_FOR_INSTANCE) {
            nextQueue.add(uuid);
            LobbyChat.tellPlayer(uuid, ChatColor.YELLOW + "[RoN] Match in progress. Queued for the next match (" +
                    nextQueue.size() + " waiting).");
            publishState();
            return;
        }

        // OPEN or FILLING — queue is open, anyone can join.
        publicQueue.add(uuid);
        LobbyChat.broadcastToAll(ChatColor.GOLD + "[RoN] " + name + " joined the queue! (" + publicQueue.size() + " in queue)");
        SoundEffects.queueJoin(Bukkit.getPlayer(uuid));

        if (phase == Phase.OPEN && publicQueue.size() >= minPlayers) {
            enterFilling();
        } else if (phase == Phase.FILLING) {
            LobbyChat.tellPlayer(uuid, ChatColor.YELLOW + "[RoN] Match locks in " + fillSecondsRemaining + "s.");
            // Newcomer joining mid-fill — give them the same bossbar.
            if (uiSettings.bossbarQueue()) {
                VoteBossBar.startQueue(publicQueue, fillSeconds, this::getFillSecondsRemaining);
            }
        }
        publishState();
    }

    public int getFillSecondsRemaining() { return fillSecondsRemaining; }

    // --- Custom Lobbies (registry + host config live in CustomLobbyManager) ---

    public String createCustomLobby(UUID host, String name) { return customLobbies.createCustomLobby(host, name); }
    public void joinCustomLobby(UUID uuid, String name, String code) { customLobbies.joinCustomLobby(uuid, name, code); }
    public void joinPublicLobby(UUID uuid, String name, String code) { customLobbies.joinPublicLobby(uuid, name, code); }
    public void toggleVisibility(UUID host) { customLobbies.toggleVisibility(host); }
    public List<CustomLobbyView> getPublicLobbies() { return customLobbies.getPublicLobbies(); }
    public void requestMapsForHost(UUID host) { customLobbies.requestMapsForHost(host); }
    public void selectHostMap(UUID host, String folder) { customLobbies.selectHostMap(host, folder); }
    public void selectHostMode(UUID host, String folder, String modeName) { customLobbies.selectHostMode(host, folder, modeName); }
    public void toggleHostAllianceLock(UUID host) { customLobbies.toggleHostAllianceLock(host); }
    public void toggleHostFog(UUID host) { customLobbies.toggleHostFog(host); }
    public void toggleHostShowCoop(UUID host) { customLobbies.toggleHostShowCoop(host); }
    public CustomLobbyView getCustomLobbyView(UUID uuid) { return customLobbies.getCustomLobbyView(uuid); }

    public void startCustom(UUID uuid) {
        CustomLobby lobby = customLobbies.lobbyOf(uuid);
        if (lobby == null) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] You're not in a custom lobby.");
            return;
        }
        if (!lobby.host.equals(uuid)) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] Only the lobby host can start.");
            return;
        }
        if (lobby.players.size() < 2) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] Need at least 2 players to start.");
            return;
        }
        if (lobby.selectedMapFolder == null || lobby.selectedMode == null) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] Select a map and a game mode first.");
            return;
        }
        if (lobby.players.size() != lobby.selectedModePlayers) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] " + lobby.selectedMode + " needs "
                    + lobby.selectedModePlayers + " players — the lobby has " + lobby.players.size() + ".");
            return;
        }
        startCustomMatchDirect(lobby);
    }

    // --- Shared ---

    public void leaveQueue(UUID uuid, String name) {
        if (nextQueue.remove(uuid)) {
            LobbyChat.tellPlayer(uuid, ChatColor.GOLD + "[RoN] Removed from the next-match queue.");
            SoundEffects.queueLeave(Bukkit.getPlayer(uuid));
            publishState();
            return;
        }

        if (publicQueue.remove(uuid)) {
            VoteBossBar.remove(uuid);
            SoundEffects.queueLeave(Bukkit.getPlayer(uuid));
            LobbyChat.broadcastToAll(ChatColor.GOLD + "[RoN] " + name + " left the queue. (" + publicQueue.size() + " in queue)");
            if (pendingPlayers != null) pendingPlayers.remove(uuid);
            voteSession.removeVoter(uuid);
            checkCancelFillOrVote();
            checkPendingMatchViability();
            publishState();
            return;
        }

        CustomLobby lobby = customLobbies.removePlayer(uuid, name);
        if (lobby != null) {
            if (pendingPlayers != null && pendingCustomMatch) pendingPlayers.remove(uuid);
            voteSession.removeVoter(uuid);

            if (lobby.players.isEmpty() || lobby.host.equals(uuid)) {
                customLobbies.disbandLobby(lobby);
            } else {
                checkCancelFillOrVote();
                checkPendingMatchViability();
            }
        }
        publishState();
    }

    public boolean isInAnyQueue(UUID uuid) {
        return publicQueue.contains(uuid) || nextQueue.contains(uuid) || customLobbies.isInLobby(uuid);
    }

    public void updateServerInfo(int minPlayers, int availableInstances) {
        this.minPlayers = minPlayers;
        this.availableInstances = availableInstances;
    }

    // --- Match Found Callbacks ---

    public void onMatchFound(String instance) {
        if (phase == Phase.WAITING_FOR_INSTANCE) {
            plugin.getLogger().warning("onMatchFound for " + instance + " ignored — already waiting on " + pendingInstance);
            return;
        }
        if (transferTimeoutTask != -1) {
            Bukkit.getScheduler().cancelTask(transferTimeoutTask);
            transferTimeoutTask = -1;
        }
        pendingInstance = instance;
        phase = Phase.WAITING_FOR_INSTANCE;

        if (pendingPlayers != null) {
            // Always send this personally so chat-messages: false doesn't suppress
            // the only "we're transferring you" cue players get.
            String mapDesc = pendingMapName != null ? pendingMapName : "the selected map";
            String modeDesc = pendingMode != null ? " on mode " + pendingMode : "";
            String msg = ChatColor.GOLD + "[RoN] " + instance
                    + " preparing " + mapDesc + modeDesc + ", please wait...";
            for (UUID uuid : pendingPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendMessage(msg);
            }
            SoundEffects.matchFound(pendingPlayers);
        }
        LobbyMessaging.sendConfirmMatch(instance);

        // 2min timeout in case instance never becomes ready
        transferTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            transferTimeoutTask = -1;
            if (phase == Phase.WAITING_FOR_INSTANCE) {
                plugin.getLogger().warning("Transfer timeout for " + instance + " — re-queuing players");
                if (pendingPlayers != null) {
                    LobbyChat.broadcastToPlayers(pendingPlayers, ChatColor.RED + "Server failed to respond. You have been re-queued.");
                }
                cancelPendingMatch();
            }
        }, transferTimeoutTicks).getTaskId();
    }

    public void onNoMatchFound() {
        if (pendingPlayers != null) {
            LobbyChat.broadcastToPlayers(pendingPlayers, "No game servers available. Try again later.");
        }
        cancelPendingMatch();
    }

    public void onInstanceReady(String instanceName) {
        if (phase != Phase.WAITING_FOR_INSTANCE || !instanceName.equals(pendingInstance)) return;

        if (pendingPlayers != null && pendingMin > 0 && pendingPlayers.size() < pendingMin) {
            checkPendingMatchViability();
            return;
        }

        if (transferTimeoutTask != -1) {
            Bukkit.getScheduler().cancelTask(transferTimeoutTask);
            transferTimeoutTask = -1;
        }

        if (pendingPlayers != null) {
            LobbyChat.broadcastToPlayers(pendingPlayers, "Server ready! Transferring you now...");

            List<String> uuids = pendingPlayers.stream().map(UUID::toString).toList();
            LobbyMessaging.sendTransfer(uuids, pendingInstance, pendingCustomMatch);
            plugin.getLogger().info("Transferred " + uuids.size() + " players to " + pendingInstance +
                    (pendingCustomMatch ? " (custom)" : ""));

            for (UUID uuid : pendingPlayers) {
                publicQueue.remove(uuid);
                customLobbies.removeForTransfer(uuid);
            }
            customLobbies.removeEmptyLobbies();
        }

        pendingPlayers = null;
        pendingInstance = null;
        pendingCustomMatch = false;
        pendingMin = 0;
        pendingMapName = null;
        pendingMode = null;
        phase = Phase.OPEN;
        voteSession.clear();

        drainNextQueue();
        publishState();
    }

    // --- Combined map+mode voting ---

    public void onMapOptions(List<MapOption> options) {
        // Host browsing maps for a custom lobby — handled by the lobby manager,
        // which caches the options and opens the host's picker (not a public vote).
        if (customLobbies.consumeHostBrowse(options)) return;
        if (phase != Phase.LOCKED_VOTING || pendingPlayers == null) return;
        Set<UUID> voters = pendingPlayers;
        voteSession.start(voters, options, voteSeconds, this::proceedWithChoice);
        if (voteSession.isActive()) {
            VoteMenu.openVoteForAll(voters, voteSession.getMapOptions(), voteSeconds);
        }
    }

    public void castVote(UUID uuid, int choice, String mode) {
        if (phase != Phase.LOCKED_VOTING || pendingPlayers == null) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] No active vote.");
            return;
        }
        voteSession.cast(uuid, choice, mode, pendingPlayers);
    }

    private void proceedWithChoice(CombinedOption choice) {
        if (pendingPlayers == null) {
            cancelPendingMatch();
            return;
        }
        int size = pendingPlayers.size();
        if (choice.players() > 0 && size != choice.players()) {
            LobbyChat.broadcastToPlayers(pendingPlayers,
                    ChatColor.RED + "Player count changed (" + size + " queued, " + choice.players()
                            + " required for " + choice.modeName() + ") — match cancelled.");
            cancelPendingMatch();
            return;
        }
        pendingMin = choice.players();
        pendingMapName = choice.mapName();
        pendingMode = choice.modeName();
        String mapFolder = choice.mapFolder() != null ? choice.mapFolder() : "random";
        String mode = choice.modeName();
        voteSession.clear();
        // Public matches: alliances locked, fog disabled (host controls only apply to custom lobbies).
        LobbyMessaging.sendFindMatch(size, mapFolder, mode, true, false);
    }

    public int getMinPlayers() { return minPlayers; }
    public int getAvailableInstances() { return availableInstances; }
    public int getPublicQueueSize() { return publicQueue.size(); }

    public boolean isVoteActive() { return voteSession.isActive(); }
    public boolean hasVoted(UUID uuid) { return voteSession.hasVoted(uuid); }
    public List<MapOption> getVoteMapOptions() { return voteSession.getMapOptions(); }
    public int getVoteSecondsRemaining() { return voteSession.getSecondsRemaining(); }
    public int getVoteTotalSeconds() { return voteSeconds; }
    public Map<CombinedOption, Integer> getVoteCounts() { return voteSession.getVoteCounts(); }

    // --- Phase transitions ---

    private void enterFilling() {
        phase = Phase.FILLING;
        fillSecondsRemaining = fillSeconds;
        publishState();

        LobbyChat.broadcastToPlayers(publicQueue, ChatColor.GOLD + "Enough players! Match locks in " + fillSeconds +
                "s — invite friends with /queue.");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!publicQueue.contains(p.getUniqueId())) {
                p.sendMessage(ChatColor.YELLOW + "[RoN] A match is forming with " + publicQueue.size() +
                        " players! Type " + ChatColor.WHITE + "/queue" + ChatColor.YELLOW + " to join.");
            }
        }

        if (uiSettings.bossbarQueue()) {
            VoteBossBar.startQueue(publicQueue, fillSeconds, this::getFillSecondsRemaining);
        }

        fillTask = new BukkitRunnable() {
            @Override
            public void run() {
                fillSecondsRemaining--;
                if (fillSecondsRemaining > 0 && (fillSecondsRemaining <= 10 || fillSecondsRemaining % 30 == 0)) {
                    LobbyChat.broadcastToPlayers(publicQueue, "Locking in " + fillSecondsRemaining + "s... (" +
                            publicQueue.size() + " players)");
                }
                if (fillSecondsRemaining <= 0) {
                    cancel();
                    fillTask = -1;
                    enterLockedVoting();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L).getTaskId();
    }

    private void enterLockedVoting() {
        VoteBossBar.stopAll(); // wipe the queue-fill bar so the vote bar can take over
        if (publicQueue.size() < minPlayers) {
            LobbyChat.broadcastToPlayers(publicQueue, "Not enough players — match cancelled.");
            phase = Phase.OPEN;
            publishState();
            return;
        }

        pendingPlayers = new LinkedHashSet<>(publicQueue);
        pendingCustomMatch = false;
        phase = Phase.LOCKED_VOTING;

        LobbyChat.broadcastToPlayers(pendingPlayers, ChatColor.GOLD + "Room locked! Fetching map options...");
        LobbyMessaging.sendGetMaps(pendingPlayers.size(), false);
        publishState();
    }

    /**
     * Start a host-configured custom match: no voting — the host has already chosen
     * the map, mode and optional rules. Locks the room and asks the proxy for the
     * specific map+mode directly.
     */
    private void startCustomMatchDirect(CustomLobby lobby) {
        if (availableInstances <= 0) {
            LobbyChat.broadcastToLobby(lobby, ChatColor.RED + "No game servers available right now.");
            return;
        }
        if (phase != Phase.OPEN && phase != Phase.FILLING) {
            LobbyChat.broadcastToLobby(lobby, "Another match is starting — please wait. Click Start again to retry.");
            return;
        }

        // Cancel any public fill — the custom lobby is taking over the lock.
        if (phase == Phase.FILLING) {
            cancelFillTimer();
            LobbyChat.broadcastToPlayers(publicQueue, ChatColor.YELLOW + "Public match deferred — a custom match is starting.");
            phase = Phase.OPEN;
        }

        pendingPlayers = new LinkedHashSet<>(lobby.players);
        pendingCustomMatch = true;
        pendingMin = lobby.selectedModePlayers;
        pendingMapName = lobby.selectedMapName;
        pendingMode = lobby.selectedMode;
        phase = Phase.LOCKED_VOTING;

        LobbyChat.broadcastToPlayers(pendingPlayers, ChatColor.GOLD + "Lobby locked! Preparing "
                + lobby.selectedMapName + " (" + lobby.selectedMode + ")...");
        LobbyMessaging.sendFindMatch(pendingPlayers.size(), lobby.selectedMapFolder, lobby.selectedMode,
                lobby.allianceLock, lobby.fogOfWar);
        publishState();
    }

    // --- Cancellation / cleanup ---

    private void cancelPendingMatch() {
        phase = Phase.OPEN;
        customLobbies.clearHostBrowse();
        pendingInstance = null;
        pendingPlayers = null;
        pendingCustomMatch = false;
        pendingMin = 0;
        pendingMapName = null;
        pendingMode = null;
        if (transferTimeoutTask != -1) {
            Bukkit.getScheduler().cancelTask(transferTimeoutTask);
            transferTimeoutTask = -1;
        }
        voteSession.clear();
        drainNextQueue();
        publishState();
    }

    /**
     * Called after a player leaves {@code pendingPlayers} during WAITING_FOR_INSTANCE.
     * If the survivors no longer satisfy the chosen mode's minimum, tell the proxy
     * to stand the instance down, re-queue the survivors, and reopen.
     */
    private void checkPendingMatchViability() {
        if (phase != Phase.WAITING_FOR_INSTANCE || pendingPlayers == null) return;
        if (pendingMin <= 0 || pendingPlayers.size() >= pendingMin) return;
        LobbyChat.broadcastToPlayers(pendingPlayers,
                ChatColor.RED + "Not enough players — match cancelled. You have been re-queued.");
        Set<UUID> survivors = new LinkedHashSet<>(pendingPlayers);
        String instance = pendingInstance;
        cancelPendingMatch();
        if (instance != null) LobbyMessaging.sendCancelMatch(instance);
        for (UUID uuid : survivors) {
            if (!publicQueue.contains(uuid) && !nextQueue.contains(uuid) && !customLobbies.isInLobby(uuid)) {
                publicQueue.add(uuid);
            }
        }
        if (publicQueue.size() >= minPlayers && phase == Phase.OPEN) {
            enterFilling();
        }
        publishState();
    }

    private void cancelFillTimer() {
        if (fillTask != -1) {
            Bukkit.getScheduler().cancelTask(fillTask);
            fillTask = -1;
        }
        VoteBossBar.stopAll();
    }

    private void checkCancelFillOrVote() {
        if (phase == Phase.FILLING && publicQueue.size() < minPlayers) {
            cancelFillTimer();
            phase = Phase.OPEN;
            LobbyChat.broadcastToPlayers(publicQueue, "Not enough players — fill cancelled.");
        } else if (phase == Phase.LOCKED_VOTING && pendingPlayers != null && pendingPlayers.size() < minPlayers) {
            LobbyChat.broadcastToPlayers(pendingPlayers, ChatColor.RED + "Not enough players — match cancelled.");
            voteSession.clear();
            pendingPlayers = null;
            pendingCustomMatch = false;
            phase = Phase.OPEN;
            // Late joiners go back into the public queue and may trigger a new fill.
            publicQueue.addAll(nextQueue);
            nextQueue.clear();
            if (publicQueue.size() >= minPlayers) {
                enterFilling();
            }
        }
    }

    private void drainNextQueue() {
        if (nextQueue.isEmpty()) return;

        Set<UUID> promoted = new LinkedHashSet<>(nextQueue);
        nextQueue.clear();
        publicQueue.addAll(promoted);

        LobbyChat.broadcastToPlayers(promoted, "You're in the queue! (" + publicQueue.size() + " players)");

        if (phase == Phase.OPEN && publicQueue.size() >= minPlayers) {
            enterFilling();
        }
    }

    // --- Helpers ---

    /** Build a JSON snapshot of the current queue state and push it to the proxy. */
    void publishState() {
        try {
            JsonObject snap = new JsonObject();
            snap.addProperty("phase", phase.name());
            snap.add("publicQueue", uuidsToNamesArray(publicQueue));
            snap.add("nextQueue", uuidsToNamesArray(nextQueue));

            JsonArray lobbies = new JsonArray();
            for (CustomLobby lobby : customLobbies.lobbies()) {
                JsonObject l = new JsonObject();
                l.addProperty("code", lobby.code);
                Player host = Bukkit.getPlayer(lobby.host);
                l.addProperty("hostName", host != null ? host.getName() : lobby.host.toString());
                l.add("playerNames", uuidsToNamesArray(lobby.players));
                lobbies.add(l);
            }
            snap.add("customLobbies", lobbies);
            LobbyMessaging.sendQueueUpdate(snap);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish queue snapshot: " + e.getMessage());
        }
        // Any state change is also a UI change.
        MenuSupport.refreshLobbyMenus();
    }

    private static JsonArray uuidsToNamesArray(Collection<UUID> uuids) {
        JsonArray arr = new JsonArray();
        for (UUID uuid : uuids) {
            Player p = Bukkit.getPlayer(uuid);
            arr.add(p != null ? p.getName() : uuid.toString());
        }
        return arr;
    }
}
