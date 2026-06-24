package com.ron.lobby.queue;

import com.ron.lobby.messaging.LobbyMessaging;
import com.ron.lobby.queue.MatchQueue.MapOption;
import com.ron.lobby.queue.MatchQueue.ModeOption;
import com.ron.lobby.ui.menu.CustomLobbyMenu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the custom-lobby registry (code → lobby, player → code) and all host
 * configuration (map / mode selection, optional rules, visibility). The shared
 * phase/pending lock and the actual match start live in {@link MatchQueue}; this
 * class calls back into it for state publishing, and {@code MatchQueue} drives
 * the leave / transfer cleanup through the methods here.
 */
final class CustomLobbyManager {

    private final MatchQueue queue;
    private final Map<String, CustomLobby> lobbies = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLobby = new ConcurrentHashMap<>();

    // Host currently browsing maps for their custom lobby (awaiting MAP_OPTIONS).
    // Set by requestMapsForHost, consumed in consumeHostBrowse — keeps the host's
    // map fetch from being mistaken for a public vote.
    private volatile UUID pendingHostBrowse = null;

    CustomLobbyManager(MatchQueue queue) {
        this.queue = queue;
    }

    // --- Creation / join ---

    String createCustomLobby(UUID host, String name) {
        if (!queue.instancesAvailable() && !queue.waitingForInstance()) {
            LobbyChat.tellPlayer(host, ChatColor.RED + "[RoN] No game servers available right now.");
            return null;
        }
        String code = generateCode();
        CustomLobby lobby = new CustomLobby(code, host);
        lobby.players.add(host);
        lobbies.put(code, lobby);
        playerLobby.put(host, code);
        LobbyChat.broadcastToLobby(lobby, name + " created a custom lobby. Code: " + code);
        queue.publishState();
        return code;
    }

    void joinCustomLobby(UUID uuid, String name, String code) {
        CustomLobby lobby = lobbies.get(code);
        if (lobby == null) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] Invalid lobby code.");
            return;
        }
        addToLobby(lobby, uuid, name);
        queue.publishState();
    }

    /** Join a custom lobby by clicking the host's head — only works for public lobbies. */
    void joinPublicLobby(UUID uuid, String name, String code) {
        CustomLobby lobby = lobbies.get(code);
        if (lobby == null) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] That lobby no longer exists.");
            return;
        }
        if (!lobby.isPublic) {
            LobbyChat.tellPlayer(uuid, ChatColor.RED + "[RoN] That lobby is private — you need its code.");
            return;
        }
        addToLobby(lobby, uuid, name);
        queue.publishState();
    }

    private void addToLobby(CustomLobby lobby, UUID uuid, String name) {
        lobby.players.add(uuid);
        playerLobby.put(uuid, lobby.code);
        LobbyChat.broadcastToLobby(lobby, name + " joined the lobby! (" + lobby.players.size() + " players)");

        if (lobby.players.size() >= 2) {
            Player hostPlayer = Bukkit.getPlayer(lobby.host);
            if (hostPlayer != null) {
                hostPlayer.sendMessage(ChatColor.GREEN + "[RoN] Lobby is ready — click Start to begin.");
            }
        }
    }

    /** Toggle a lobby between private (code-only) and public (joinable from the menu). */
    void toggleVisibility(UUID host) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        lobby.isPublic = !lobby.isPublic;
        queue.publishState();
    }

    /** Snapshot of every public custom lobby, for the join-by-head browse menu. */
    List<CustomLobbyView> getPublicLobbies() {
        List<CustomLobbyView> out = new ArrayList<>();
        for (CustomLobby lobby : lobbies.values()) {
            if (lobby.isPublic) out.add(viewOf(lobby));
        }
        return out;
    }

    // --- Host configuration ---

    /** Host clicked "Select Map" — fetch maps compatible with the current lobby size. */
    void requestMapsForHost(UUID host) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) {
            LobbyChat.tellPlayer(host, ChatColor.RED + "[RoN] Only the lobby host can configure the match.");
            return;
        }
        if (!queue.instancesAvailable()) {
            LobbyChat.tellPlayer(host, ChatColor.RED + "[RoN] No game servers available right now.");
            return;
        }
        pendingHostBrowse = host;
        LobbyMessaging.sendGetMaps(lobby.players.size(), true, lobby.showCoop);
    }

    /** Toggle coop/PvE maps in the host picker, then re-fetch the list with the new filter. */
    void toggleHostShowCoop(UUID host) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        lobby.showCoop = !lobby.showCoop;
        requestMapsForHost(host);
    }

    void selectHostMap(UUID host, String folder) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        // "Random" is a sentinel, not a real folder — the proxy resolves it at match start.
        MapOption map = MatchQueue.RANDOM_MAP_FOLDER.equals(folder)
                ? MatchQueue.randomMapOption(lobby.cachedMapOptions)
                : lobby.cachedMapOptions.stream()
                        .filter(o -> o.folder().equals(folder)).findFirst().orElse(null);
        if (map == null || map.modes().isEmpty()) {
            LobbyChat.tellPlayer(host, ChatColor.RED + "[RoN] That map is no longer available.");
            return;
        }
        lobby.selectedMapFolder = map.folder();
        lobby.selectedMapName = map.name();
        // Mode depends on the map — reset the mode and its optional rules.
        lobby.selectedMode = null;
        lobby.selectedModePlayers = 0;
        lobby.allianceLock = true;
        lobby.fogOfWar = false;
        queue.publishState();
    }

    void selectHostMode(UUID host, String folder, String modeName) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        if (lobby.selectedMapFolder == null || !lobby.selectedMapFolder.equals(folder)) {
            LobbyChat.tellPlayer(host, ChatColor.RED + "[RoN] Pick a map first.");
            return;
        }
        MapOption map = MatchQueue.RANDOM_MAP_FOLDER.equals(folder)
                ? MatchQueue.randomMapOption(lobby.cachedMapOptions)
                : lobby.cachedMapOptions.stream()
                        .filter(o -> o.folder().equals(folder)).findFirst().orElse(null);
        ModeOption mode = map == null ? null : map.modes().stream()
                .filter(m -> m.name().equals(modeName)).findFirst().orElse(null);
        if (mode == null) {
            LobbyChat.tellPlayer(host, ChatColor.RED + "[RoN] That mode is no longer available.");
            return;
        }
        lobby.selectedMode = mode.name();
        lobby.selectedModePlayers = mode.players();
        // FFA starts unlocked (host may toggle on); all other modes stay locked.
        lobby.allianceLock = !mode.name().toLowerCase().startsWith("ffa");
        queue.publishState();
    }

    /** Toggle alliance locking — only meaningful for FFA (other modes are always locked). */
    void toggleHostAllianceLock(UUID host) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        if (lobby.selectedMode == null || !lobby.selectedMode.toLowerCase().startsWith("ffa")) return;
        lobby.allianceLock = !lobby.allianceLock;
        queue.publishState();
    }

    void toggleHostFog(UUID host) {
        CustomLobby lobby = lobbyOf(host);
        if (lobby == null || !lobby.host.equals(host)) return;
        if (lobby.selectedMode == null) return;
        lobby.fogOfWar = !lobby.fogOfWar;
        queue.publishState();
    }

    /**
     * If a host is awaiting their map list, cache the options and open the host's
     * map picker. Returns true if the options were consumed as a host browse
     * (so the caller should not treat them as a public vote).
     */
    boolean consumeHostBrowse(List<MapOption> options) {
        if (pendingHostBrowse == null) return false;
        UUID host = pendingHostBrowse;
        pendingHostBrowse = null;
        CustomLobby lobby = lobbyOf(host);
        if (lobby != null && lobby.host.equals(host)) {
            lobby.cachedMapOptions = options;
            Player p = Bukkit.getPlayer(host);
            if (p != null) CustomLobbyMenu.openHostMapSelect(p);
        }
        return true;
    }

    // --- Views / lookups ---

    CustomLobby lobbyOf(UUID uuid) {
        String code = playerLobby.get(uuid);
        if (code == null) return null;
        return lobbies.get(code);
    }

    CustomLobbyView getCustomLobbyView(UUID uuid) {
        CustomLobby lobby = lobbyOf(uuid);
        return lobby == null ? null : viewOf(lobby);
    }

    private static CustomLobbyView viewOf(CustomLobby lobby) {
        return new CustomLobbyView(lobby.code, lobby.host, List.copyOf(lobby.players),
                lobby.isPublic, lobby.selectedMapFolder, lobby.selectedMapName, lobby.selectedMode,
                lobby.selectedModePlayers, lobby.allianceLock, lobby.fogOfWar, lobby.showCoop,
                lobby.cachedMapOptions);
    }

    boolean isInLobby(UUID uuid) {
        return playerLobby.containsKey(uuid);
    }

    Collection<CustomLobby> lobbies() {
        return lobbies.values();
    }

    // --- Leave / cleanup (coordinated by MatchQueue) ---

    /**
     * Remove a leaving player from their lobby (with a broadcast). Returns the
     * lobby so {@code MatchQueue} can settle the shared queue state and decide
     * whether to disband; null if the player was not in a lobby.
     */
    CustomLobby removePlayer(UUID uuid, String name) {
        String code = playerLobby.remove(uuid);
        if (code == null) return null;
        CustomLobby lobby = lobbies.get(code);
        if (lobby == null) return null;
        lobby.players.remove(uuid);
        LobbyChat.broadcastToLobby(lobby, name + " left the lobby. (" + lobby.players.size() + " players)");
        return lobby;
    }

    /** Silent removal used when transferring a player into a started match. */
    void removeForTransfer(UUID uuid) {
        String code = playerLobby.remove(uuid);
        if (code != null) {
            CustomLobby lobby = lobbies.get(code);
            if (lobby != null) lobby.players.remove(uuid);
        }
    }

    void removeEmptyLobbies() {
        lobbies.values().removeIf(l -> l.players.isEmpty());
    }

    void disbandLobby(CustomLobby lobby) {
        if (pendingHostBrowse != null && lobby.players.contains(pendingHostBrowse)) {
            pendingHostBrowse = null;
        }
        LobbyChat.broadcastToLobby(lobby, "Lobby disbanded.");
        for (UUID uuid : lobby.players) {
            playerLobby.remove(uuid);
        }
        lobbies.remove(lobby.code);
    }

    void clearHostBrowse() {
        pendingHostBrowse = null;
    }

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
            }
            code = sb.toString();
        } while (lobbies.containsKey(code));
        return code;
    }
}
