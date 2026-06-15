package com.ron.lobby.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ron.lobby.RonLobby;
import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.ui.UiSettings;
import com.ron.lobby.ui.menu.MenuService;
import com.ron.lobby.ui.menu.SoundEffects;
import com.ron.lobby.ui.menu.VoteBossBar;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MatchQueue implements Listener {

    private static final int DEFAULT_FILL_SECONDS = 120;
    private static final int DEFAULT_VOTE_SECONDS = 60;
    /** Re-queue players if the assigned instance never reports ready (2 minutes, in ticks). */
    private static final long TRANSFER_TIMEOUT_TICKS = 2400L;

    private final RonLobby plugin;
    private final VoteSession voteSession;

    // Phase machine — public queue is the primary driver, private lobbies share the lock.
    private enum Phase { OPEN, FILLING, LOCKED_VOTING, WAITING_FOR_INSTANCE }
    private Phase phase = Phase.OPEN;

    // Public queue: open → fill → lock → vote → transfer
    private final Set<UUID> publicQueue = new LinkedHashSet<>();
    // Late joiners during LOCKED_VOTING / WAITING_FOR_INSTANCE — promoted into publicQueue once free.
    private final Set<UUID> nextQueue = new LinkedHashSet<>();

    private int fillTask = -1;
    private int fillSecondsRemaining = 0;

    private int fillSeconds = DEFAULT_FILL_SECONDS;
    private int voteSeconds = DEFAULT_VOTE_SECONDS;

    // Private lobbies: code -> lobby
    private final Map<String, PrivateLobby> privateLobbies = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLobby = new ConcurrentHashMap<>();

    // Host currently browsing maps for their private lobby (awaiting MAP_OPTIONS).
    // Set by requestMapsForHost, consumed in onMapOptions — keeps the host's map
    // fetch from being mistaken for a public vote.
    private volatile UUID pendingHostBrowse = null;

    // Pending match state (shared across public/private — only one match at a time)
    private String pendingInstance = null;
    private Set<UUID> pendingPlayers = null;
    private boolean pendingPrivateMatch = false;
    private int pendingMin = 0;
    private String pendingMapName = null;
    private String pendingMode = null;
    private int transferTimeoutTask = -1;

    public record ModeOption(String name, int players) {}
    public record MapOption(String folder, String name, List<ModeOption> modes) {}
    public record CombinedOption(String mapFolder, String mapName, String modeName, int players) {}

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
    }

    public void configureTimings(int fillSeconds, int voteSeconds) {
        if (fillSeconds > 0) this.fillSeconds = fillSeconds;
        if (voteSeconds > 0) this.voteSeconds = voteSeconds;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        leaveQueue(uuid, event.getPlayer().getName());
    }

    // --- Public Queue ---

    public void joinPublicQueue(UUID uuid, String name) {
        if (availableInstances <= 0 && phase != Phase.WAITING_FOR_INSTANCE) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] No game servers available right now.");
            return;
        }

        if (publicQueue.contains(uuid) || nextQueue.contains(uuid) || playerLobby.containsKey(uuid)) {
            tellPlayer(uuid, ChatColor.YELLOW + "[RoN] You're already in a queue.");
            return;
        }

        if (phase == Phase.LOCKED_VOTING || phase == Phase.WAITING_FOR_INSTANCE) {
            nextQueue.add(uuid);
            tellPlayer(uuid, ChatColor.YELLOW + "[RoN] Match in progress. Queued for the next match (" +
                    nextQueue.size() + " waiting).");
            publishState();
            return;
        }

        // OPEN or FILLING — queue is open, anyone can join.
        publicQueue.add(uuid);
        broadcastToAll(ChatColor.GOLD + "[RoN] " + name + " joined the queue! (" + publicQueue.size() + " in queue)");
        SoundEffects.queueJoin(Bukkit.getPlayer(uuid));

        if (phase == Phase.OPEN && publicQueue.size() >= minPlayers) {
            enterFilling();
        } else if (phase == Phase.FILLING) {
            tellPlayer(uuid, ChatColor.YELLOW + "[RoN] Match locks in " + fillSecondsRemaining + "s.");
            // Newcomer joining mid-fill — give them the same bossbar.
            if (uiSettings.bossbarQueue()) {
                VoteBossBar.startQueue(publicQueue, fillSeconds, this::getFillSecondsRemaining);
            }
        }
        publishState();
    }

    public int getFillSecondsRemaining() { return fillSecondsRemaining; }

    // --- Private Lobbies ---

    public String createPrivateLobby(UUID host, String name) {
        if (availableInstances <= 0 && phase != Phase.WAITING_FOR_INSTANCE) {
            tellPlayer(host, ChatColor.RED + "[RoN] No game servers available right now.");
            return null;
        }
        String code = generateCode();
        PrivateLobby lobby = new PrivateLobby(code, host);
        lobby.players.add(host);
        privateLobbies.put(code, lobby);
        playerLobby.put(host, code);
        broadcastToLobby(lobby, name + " created a private lobby. Code: " + code);
        publishState();
        return code;
    }

    public void joinPrivateLobby(UUID uuid, String name, String code) {
        PrivateLobby lobby = privateLobbies.get(code);
        if (lobby == null) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] Invalid lobby code.");
            return;
        }
        lobby.players.add(uuid);
        playerLobby.put(uuid, code);
        broadcastToLobby(lobby, name + " joined the lobby! (" + lobby.players.size() + " players)");

        if (lobby.players.size() >= 2) {
            Player hostPlayer = Bukkit.getPlayer(lobby.host);
            if (hostPlayer != null) {
                hostPlayer.sendMessage(ChatColor.GREEN + "[RoN] Lobby is ready — click Start to begin.");
            }
        }
        publishState();
    }

    /** Host clicked "Select Map" — fetch maps compatible with the current lobby size. */
    public void requestMapsForHost(UUID host) {
        PrivateLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) {
            tellPlayer(host, ChatColor.RED + "[RoN] Only the lobby host can configure the match.");
            return;
        }
        if (availableInstances <= 0) {
            tellPlayer(host, ChatColor.RED + "[RoN] No game servers available right now.");
            return;
        }
        pendingHostBrowse = host;
        LobbyMessaging.sendGetMaps(lobby.players.size());
    }

    public void selectHostMap(UUID host, String folder) {
        PrivateLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        MapOption map = lobby.cachedMapOptions.stream()
                .filter(o -> o.folder().equals(folder)).findFirst().orElse(null);
        if (map == null) {
            tellPlayer(host, ChatColor.RED + "[RoN] That map is no longer available.");
            return;
        }
        lobby.selectedMapFolder = map.folder();
        lobby.selectedMapName = map.name();
        // Mode depends on the map — reset the mode and its optional rules.
        lobby.selectedMode = null;
        lobby.selectedModePlayers = 0;
        lobby.allianceLock = Boolean.TRUE;
        lobby.fogOfWar = Boolean.FALSE;
        publishState();
    }

    public void selectHostMode(UUID host, String folder, String modeName) {
        PrivateLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        if (lobby.selectedMapFolder == null || !lobby.selectedMapFolder.equals(folder)) {
            tellPlayer(host, ChatColor.RED + "[RoN] Pick a map first.");
            return;
        }
        MapOption map = lobby.cachedMapOptions.stream()
                .filter(o -> o.folder().equals(folder)).findFirst().orElse(null);
        ModeOption mode = map == null ? null : map.modes().stream()
                .filter(m -> m.name().equals(modeName)).findFirst().orElse(null);
        if (mode == null) {
            tellPlayer(host, ChatColor.RED + "[RoN] That mode is no longer available.");
            return;
        }
        lobby.selectedMode = mode.name();
        lobby.selectedModePlayers = mode.players();
        publishState();
    }

    /** Toggle alliance locking — only meaningful for FFA (other modes are always locked). */
    public void toggleHostAllianceLock(UUID host) {
        PrivateLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        if (lobby.selectedMode == null || !lobby.selectedMode.toLowerCase().startsWith("ffa")) return;
        lobby.allianceLock = !lobby.allianceLock;
        publishState();
    }

    public void toggleHostFog(UUID host) {
        PrivateLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        if (lobby.selectedMode == null) return;
        lobby.fogOfWar = !lobby.fogOfWar;
        publishState();
    }

    public void startPrivate(UUID uuid) {
        PrivateLobby lobby = lobbyOf(uuid);
        if (lobby == null) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] You're not in a private lobby.");
            return;
        }
        if (!lobby.host.equals(uuid)) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] Only the lobby host can start.");
            return;
        }
        if (lobby.players.size() < 2) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] Need at least 2 players to start.");
            return;
        }
        if (lobby.selectedMapFolder == null || lobby.selectedMode == null) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] Select a map and a game mode first.");
            return;
        }
        if (lobby.players.size() != lobby.selectedModePlayers) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] " + lobby.selectedMode + " needs "
                    + lobby.selectedModePlayers + " players — the lobby has " + lobby.players.size() + ".");
            return;
        }
        startPrivateMatchDirect(lobby);
    }

    private PrivateLobby lobbyOf(UUID uuid) {
        String code = playerLobby.get(uuid);
        if (code == null) return null;
        return privateLobbies.get(code);
    }

    // --- Shared ---

    public void leaveQueue(UUID uuid, String name) {
        if (nextQueue.remove(uuid)) {
            tellPlayer(uuid, ChatColor.GOLD + "[RoN] Removed from the next-match queue.");
            SoundEffects.queueLeave(Bukkit.getPlayer(uuid));
            publishState();
            return;
        }

        if (publicQueue.remove(uuid)) {
            VoteBossBar.remove(uuid);
            SoundEffects.queueLeave(Bukkit.getPlayer(uuid));
            broadcastToAll(ChatColor.GOLD + "[RoN] " + name + " left the queue. (" + publicQueue.size() + " in queue)");
            if (pendingPlayers != null) pendingPlayers.remove(uuid);
            voteSession.removeVoter(uuid);
            checkCancelFillOrVote();
            checkPendingMatchViability();
            publishState();
            return;
        }

        String code = playerLobby.remove(uuid);
        if (code != null) {
            PrivateLobby lobby = privateLobbies.get(code);
            if (lobby != null) {
                lobby.players.remove(uuid);
                broadcastToLobby(lobby, name + " left the lobby. (" + lobby.players.size() + " players)");

                if (pendingPlayers != null && pendingPrivateMatch) pendingPlayers.remove(uuid);
                voteSession.removeVoter(uuid);

                if (lobby.players.isEmpty() || lobby.host.equals(uuid)) {
                    disbandLobby(lobby);
                } else {
                    checkCancelFillOrVote();
                    checkPendingMatchViability();
                }
            }
        }
        publishState();
    }

    public boolean isInAnyQueue(UUID uuid) {
        return publicQueue.contains(uuid) || nextQueue.contains(uuid) || playerLobby.containsKey(uuid);
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
                    broadcastToPlayers(pendingPlayers, ChatColor.RED + "Server failed to respond. You have been re-queued.");
                }
                cancelPendingMatch();
            }
        }, TRANSFER_TIMEOUT_TICKS).getTaskId();
    }

    public void onNoMatchFound() {
        if (pendingPlayers != null) {
            broadcastToPlayers(pendingPlayers, "No game servers available. Try again later.");
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
            broadcastToPlayers(pendingPlayers, "Server ready! Transferring you now...");

            List<String> uuids = pendingPlayers.stream().map(UUID::toString).toList();
            LobbyMessaging.sendTransfer(uuids, pendingInstance, pendingPrivateMatch);
            plugin.getLogger().info("Transferred " + uuids.size() + " players to " + pendingInstance +
                    (pendingPrivateMatch ? " (private)" : ""));

            for (UUID uuid : pendingPlayers) {
                publicQueue.remove(uuid);
                String code = playerLobby.remove(uuid);
                if (code != null) {
                    PrivateLobby lobby = privateLobbies.get(code);
                    if (lobby != null) lobby.players.remove(uuid);
                }
            }
            privateLobbies.values().removeIf(l -> l.players.isEmpty());
        }

        pendingPlayers = null;
        pendingInstance = null;
        pendingPrivateMatch = false;
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
        // Host browsing maps for a private lobby — cache the options and open the
        // host's map picker instead of starting a public vote.
        if (pendingHostBrowse != null) {
            UUID host = pendingHostBrowse;
            pendingHostBrowse = null;
            PrivateLobby lobby = lobbyOf(host);
            if (lobby != null && lobby.host.equals(host)) {
                lobby.cachedMapOptions = options;
                Player p = Bukkit.getPlayer(host);
                if (p != null) MenuService.openHostMapSelect(p);
            }
            return;
        }
        if (phase != Phase.LOCKED_VOTING || pendingPlayers == null) return;
        Set<UUID> voters = pendingPlayers;
        voteSession.start(voters, options, voteSeconds, this::proceedWithChoice);
        if (voteSession.isActive()) {
            MenuService.openVoteForAll(voters, voteSession.getMapOptions(), voteSeconds);
        }
    }

    public void castVote(UUID uuid, int choice, String mode) {
        if (phase != Phase.LOCKED_VOTING || pendingPlayers == null) {
            tellPlayer(uuid, ChatColor.RED + "[RoN] No active vote.");
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
            broadcastToPlayers(pendingPlayers,
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
        // Public matches: alliances locked, fog disabled (host controls only apply to private lobbies).
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

    public PrivateLobbyView getPrivateLobbyView(UUID uuid) {
        String code = playerLobby.get(uuid);
        if (code == null) return null;
        PrivateLobby lobby = privateLobbies.get(code);
        if (lobby == null) return null;
        return new PrivateLobbyView(lobby.code, lobby.host, List.copyOf(lobby.players),
                lobby.selectedMapFolder, lobby.selectedMapName, lobby.selectedMode,
                lobby.selectedModePlayers, lobby.allianceLock, lobby.fogOfWar, lobby.cachedMapOptions);
    }

    // --- Phase transitions ---

    private void enterFilling() {
        phase = Phase.FILLING;
        fillSecondsRemaining = fillSeconds;
        publishState();

        broadcastToPlayers(publicQueue, ChatColor.GOLD + "Enough players! Match locks in " + fillSeconds +
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
                    broadcastToPlayers(publicQueue, "Locking in " + fillSecondsRemaining + "s... (" +
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
            broadcastToPlayers(publicQueue, "Not enough players — match cancelled.");
            phase = Phase.OPEN;
            publishState();
            return;
        }

        pendingPlayers = new LinkedHashSet<>(publicQueue);
        pendingPrivateMatch = false;
        phase = Phase.LOCKED_VOTING;

        broadcastToPlayers(pendingPlayers, ChatColor.GOLD + "Room locked! Fetching map options...");
        LobbyMessaging.sendGetMaps(pendingPlayers.size());
        publishState();
    }

    /**
     * Start a host-configured private match: no voting — the host has already chosen
     * the map, mode and optional rules. Locks the room and asks the proxy for the
     * specific map+mode directly.
     */
    private void startPrivateMatchDirect(PrivateLobby lobby) {
        if (availableInstances <= 0) {
            broadcastToLobby(lobby, ChatColor.RED + "No game servers available right now.");
            return;
        }
        if (phase != Phase.OPEN && phase != Phase.FILLING) {
            broadcastToLobby(lobby, "Another match is starting — please wait. Click Start again to retry.");
            return;
        }

        // Cancel any public fill — the private lobby is taking over the lock.
        if (phase == Phase.FILLING) {
            cancelFillTimer();
            broadcastToPlayers(publicQueue, ChatColor.YELLOW + "Public match deferred — a private match is starting.");
            phase = Phase.OPEN;
        }

        pendingPlayers = new LinkedHashSet<>(lobby.players);
        pendingPrivateMatch = true;
        pendingMin = lobby.selectedModePlayers;
        pendingMapName = lobby.selectedMapName;
        pendingMode = lobby.selectedMode;
        phase = Phase.LOCKED_VOTING;

        broadcastToPlayers(pendingPlayers, ChatColor.GOLD + "Lobby locked! Preparing "
                + lobby.selectedMapName + " (" + lobby.selectedMode + ")...");
        LobbyMessaging.sendFindMatch(pendingPlayers.size(), lobby.selectedMapFolder, lobby.selectedMode,
                lobby.allianceLock, lobby.fogOfWar);
        publishState();
    }

    // --- Cancellation / cleanup ---

    private void cancelPendingMatch() {
        phase = Phase.OPEN;
        pendingHostBrowse = null;
        pendingInstance = null;
        pendingPlayers = null;
        pendingPrivateMatch = false;
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
        broadcastToPlayers(pendingPlayers,
                ChatColor.RED + "Not enough players — match cancelled. You have been re-queued.");
        Set<UUID> survivors = new LinkedHashSet<>(pendingPlayers);
        String instance = pendingInstance;
        cancelPendingMatch();
        if (instance != null) LobbyMessaging.sendCancelMatch(instance);
        for (UUID uuid : survivors) {
            if (!publicQueue.contains(uuid) && !nextQueue.contains(uuid) && !playerLobby.containsKey(uuid)) {
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
            broadcastToPlayers(publicQueue, "Not enough players — fill cancelled.");
        } else if (phase == Phase.LOCKED_VOTING && pendingPlayers != null && pendingPlayers.size() < minPlayers) {
            broadcastToPlayers(pendingPlayers, ChatColor.RED + "Not enough players — match cancelled.");
            voteSession.clear();
            pendingPlayers = null;
            pendingPrivateMatch = false;
            phase = Phase.OPEN;
            // Late joiners go back into the public queue and may trigger a new fill.
            publicQueue.addAll(nextQueue);
            nextQueue.clear();
            if (publicQueue.size() >= minPlayers) {
                enterFilling();
            }
        }
    }

    private void disbandLobby(PrivateLobby lobby) {
        if (pendingHostBrowse != null && lobby.players.contains(pendingHostBrowse)) {
            pendingHostBrowse = null;
        }
        broadcastToLobby(lobby, "Lobby disbanded.");
        for (UUID uuid : lobby.players) {
            playerLobby.remove(uuid);
        }
        privateLobbies.remove(lobby.code);
    }

    private void drainNextQueue() {
        if (nextQueue.isEmpty()) return;

        Set<UUID> promoted = new LinkedHashSet<>(nextQueue);
        nextQueue.clear();
        publicQueue.addAll(promoted);

        broadcastToPlayers(promoted, "You're in the queue! (" + publicQueue.size() + " players)");

        if (phase == Phase.OPEN && publicQueue.size() >= minPlayers) {
            enterFilling();
        }
    }

    // --- Helpers ---

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
            }
            code = sb.toString();
        } while (privateLobbies.containsKey(code));
        return code;
    }

    private static void tellPlayer(UUID uuid, String message) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) player.sendMessage(message);
    }

    private static void broadcastToPlayers(Set<UUID> players, String message) {
        if (!uiSettings.chatMessages()) return;
        for (UUID uuid : players) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(ChatColor.GREEN + "[RoN] " + message);
            }
        }
    }

    private static void broadcastToLobby(PrivateLobby lobby, String message) {
        broadcastToPlayers(lobby.players, "[Private] " + message);
    }

    private static void broadcastToAll(String message) {
        if (!uiSettings.chatMessages()) return;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            player.sendMessage(message);
        }
    }

    /** Build a JSON snapshot of the current queue state and push it to the proxy. */
    private void publishState() {
        try {
            JsonObject snap = new JsonObject();
            snap.addProperty("phase", phase.name());
            snap.add("publicQueue", uuidsToNamesArray(publicQueue));
            snap.add("nextQueue", uuidsToNamesArray(nextQueue));

            JsonArray lobbies = new JsonArray();
            for (PrivateLobby lobby : privateLobbies.values()) {
                JsonObject l = new JsonObject();
                l.addProperty("code", lobby.code);
                Player host = Bukkit.getPlayer(lobby.host);
                l.addProperty("hostName", host != null ? host.getName() : lobby.host.toString());
                l.add("playerNames", uuidsToNamesArray(lobby.players));
                lobbies.add(l);
            }
            snap.add("privateLobbies", lobbies);
            LobbyMessaging.sendQueueUpdate(snap);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to publish queue snapshot: " + e.getMessage());
        }
        // Any state change is also a UI change.
        MenuService.refreshLobbyMenus();
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
