package com.ron.instance;

import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.player.PlayerClientboundPacket;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosServerEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// IDLE → READYING → RUNNING → (FINISHED via InstanceState).
// The mod owns the countdown; we just watch rtsPlayers and cancel if it
// never starts.
public class MatchLifecycle {

    private static final int READYING_SECONDS = 120;
    private static final int GRACE_PERIOD_SECONDS = 120;
    private static final int MAX_MATCH_SECONDS = 7200;
    private static final int READY_TIMEOUT_SECONDS = 300;
    private static final int EMPTY_MATCH_ABANDON_SECONDS = 60;
    private static final int WELCOME_DELAY_SECONDS = 5;

    enum Phase { IDLE, READYING, RUNNING }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile int expectedPlayers = 0;
    private static volatile int phaseTicks = 0;
    private static volatile int matchTicks = 0;
    private static volatile int readyTicks = 0;
    private static volatile int emptyTicks = 0;

    private static volatile MinecraftServer serverInstance;
    private static volatile boolean victoryHandled = false;
    private static final AtomicBoolean welcomeShown = new AtomicBoolean(false);
    private static final Set<String> initialParticipants = ConcurrentHashMap.newKeySet();

    private static volatile boolean privateMatch = false;

    private static final GracePeriodTracker gracePeriods = new GracePeriodTracker();
    private static final SelectionStore selections = new SelectionStore();

    // ========================================================================
    // Server start
    // ========================================================================

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        serverInstance = event.getServer();
        reset();

        InstanceStateManager.scanMaps();
        String mapFolder = MapSwapper.didSwapMap()
                ? MapSwapper.getSwappedMapFolder()
                : "none";
        InstanceStateManager.setCurrentMap(mapFolder);

        if (MapSwapper.didSwapMap()) {
            RonInstance.LOGGER.info("Map swapped, waiting 10s before setting READY...");
            gracePeriods.scheduleAfter(10, () -> {
                InstanceStateManager.setState(InstanceState.READY);
                RonInstance.LOGGER.info("Instance READY with map: {}", mapFolder);
            });
        } else {
            InstanceStateManager.setState(InstanceState.IDLE);
            RonInstance.LOGGER.info("Instance IDLE — no map was swapped");
        }
    }

    // ========================================================================
    // Player join/leave
    // ========================================================================

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID uuid = sp.getUUID();
        String name = sp.getName().getString();

        if (gracePeriods.cancel(uuid)) {
            RonInstance.LOGGER.info("Player {} reconnected, grace period cancelled", name);
            selections.restore(uuid, name);
        }

        InstanceState state = InstanceStateManager.getState();
        if (state != InstanceState.READY && state != InstanceState.RUNNING) return;

        // Auto-flip into RTS camera so they don't have to discover /rts-camera.
        PlayerClientboundPacket.setRTSCamera(name, true);

        if (phase == Phase.IDLE && state == InstanceState.READY) {
            serverInstance = sp.getServer();
            expectedPlayers = getExpectedPlayerCount();
            phaseTicks = 0;
            phase = Phase.READYING;
            welcomeShown.set(false);
            RonInstance.LOGGER.info("MatchLifecycle: READYING phase ({} slots for mode={}, isCoop={}, isFFA={}, isRanked={}, lockAlliances={}, fogOfWar={})",
                    expectedPlayers, InstanceStateManager.getCurrentMode(), isCoop(), isFFA(), isRanked(),
                    effectiveAllianceLock(), effectiveFogOfWar());
            // Wipe any alliances left over from a previous match on this reused
            // instance JVM. Otherwise a 2v2's pairings carry into the next match
            // (e.g. a 1v1), and since team modes lock alliances they can't be
            // disbanded. StartPos re-applies the correct alliances at game start.
            AlliancesServerEvents.resetAllAlliances();
            runCommand("gamerule coopMode " + isCoop());
            runCommand("gamerule lockAlliances " + effectiveAllianceLock());
            runCommand("rts-fog " + (effectiveFogOfWar() ? "enable" : "disable"));
            gracePeriods.scheduleAfter(WELCOME_DELAY_SECONDS, MatchLifecycle::showWelcomeIfNeeded);
        } else if (phase == Phase.READYING) {
            broadcast(ChatFormatting.GRAY + name + " joined.");
            if (serverInstance.getPlayerList().getPlayerCount() >= expectedPlayers) {
                showWelcomeIfNeeded();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID uuid = sp.getUUID();
        String name = sp.getName().getString();

        if (!PlayerTracker.isParticipant(uuid) && !hasClaimedSlot(name)) return;

        selections.capture(uuid, name);

        if (phase == Phase.RUNNING || phase == Phase.READYING) {
            startGracePeriod(uuid, name);
        }
    }

    // ========================================================================
    // Main tick handler
    // ========================================================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        InstanceState currentState = InstanceStateManager.getState();

        if (currentState == InstanceState.READY) {
            readyTicks++;
            if (READY_TIMEOUT_SECONDS > 0 && readyTicks >= READY_TIMEOUT_SECONDS * 20) {
                RonInstance.LOGGER.warn("READY timeout ({}s) — resetting to IDLE", READY_TIMEOUT_SECONDS);
                InstanceStateManager.setState(InstanceState.IDLE);
                readyTicks = 0;
                return;
            }
        } else {
            readyTicks = 0;
        }

        switch (phase) {
            case IDLE -> {}
            case READYING -> tickReadying();
            case RUNNING -> tickRunning();
        }
    }

    // ========================================================================
    // Phase: READYING
    // ========================================================================

    private static void tickReadying() {
        phaseTicks++;
        selections.refreshAll();
        // startPoses gets cleared the moment the mod's countdown ends, so
        // capture participants every tick while we still can.
        trackParticipants();

        List<RTSPlayer> rtsPlayers = PlayerServerEvents.rtsPlayers;
        synchronized (rtsPlayers) {
            if (rtsPlayers.size() >= 2) {
                phase = Phase.RUNNING;
                matchTicks = 0;
                emptyTicks = 0;
                victoryHandled = false;
                initialParticipants.clear();
                for (RTSPlayer p : rtsPlayers) {
                    initialParticipants.add(p.name);
                }
                InstanceStateManager.setState(InstanceState.RUNNING);
                RonInstance.LOGGER.info("MatchLifecycle: RUNNING with {} players: {}", rtsPlayers.size(), initialParticipants);
                return;
            }
        }

        if (phaseTicks >= READYING_SECONDS * 20) {
            RonInstance.LOGGER.warn("MatchLifecycle: Readying timeout — cancelling match");
            broadcast(ChatFormatting.RED + "Match cancelled — players took too long to ready up.");
            InstanceStateManager.setState(InstanceState.FINISHED);
            phase = Phase.IDLE;
            return;
        }

        // Periodic status pings.
        int secondsLeft = (READYING_SECONDS * 20 - phaseTicks) / 20;
        if (phaseTicks % 20 == 0) {
            if (secondsLeft == 60 || secondsLeft == 30 || secondsLeft == 15 || secondsLeft == 10
                    || (secondsLeft <= 5 && secondsLeft > 0)) {
                broadcast(ChatFormatting.YELLOW + "" + secondsLeft + "s left to ready up");
            }
        }
    }

    private static void trackParticipants() {
        if (serverInstance == null) return;
        for (StartPos pos : StartPosServerEvents.startPoses) {
            if (pos.playerName.isEmpty()) continue;
            ServerPlayer sp = serverInstance.getPlayerList().getPlayerByName(pos.playerName);
            if (sp != null) {
                PlayerTracker.addParticipant(sp.getUUID(), pos.playerName);
            }
        }
    }

    // ========================================================================
    // Phase: RUNNING
    // ========================================================================

    private static void tickRunning() {
        if (victoryHandled || MatchEndHandler.hasMatchEnded()) return;

        matchTicks++;

        if (MAX_MATCH_SECONDS > 0 && matchTicks >= MAX_MATCH_SECONDS * 20) {
            RonInstance.LOGGER.warn("MatchLifecycle: Match duration timeout ({}s) — forced draw", MAX_MATCH_SECONDS);
            broadcast(ChatFormatting.RED + "Match time limit reached — forced draw");
            victoryHandled = true;
            MatchEndHandler.onDraw(new ArrayList<>(initialParticipants));
            return;
        }

        if (matchTicks % 20 != 0) return;

        List<RTSPlayer> rtsPlayers = PlayerServerEvents.rtsPlayers;
        synchronized (rtsPlayers) {
            int playerCount = rtsPlayers.size();

            if (playerCount == 0) {
                emptyTicks += 20;
                if (emptyTicks >= EMPTY_MATCH_ABANDON_SECONDS * 20) {
                    RonInstance.LOGGER.warn("MatchLifecycle: Match empty for {}s — forced draw", EMPTY_MATCH_ABANDON_SECONDS);
                    broadcast(ChatFormatting.RED + "All players gone — match abandoned.");
                    victoryHandled = true;
                    MatchEndHandler.onDraw(new ArrayList<>(initialParticipants));
                }
                return;
            }
            emptyTicks = 0;

            if (initialParticipants.size() < 2) return;

            if (playerCount == 1 && !isCoop()) {
                victoryHandled = true;
                Set<String> winners = new HashSet<>();
                winners.add(rtsPlayers.get(0).name);
                Set<String> losers = new HashSet<>(initialParticipants);
                losers.removeAll(winners);
                RonInstance.LOGGER.info("MatchLifecycle: Victory! Winners: {}, Losers: {}", winners, losers);
                MatchEndHandler.onVictory(new ArrayList<>(winners), new ArrayList<>(losers));
            } else if (playerCount > 1 && !isCoop()) {
                String reference = rtsPlayers.get(0).name;
                Set<String> allianceGroup = AlliancesServerEvents.getAllConnectedAllies(reference);
                Set<String> remaining = new HashSet<>();
                for (RTSPlayer p : rtsPlayers) {
                    remaining.add(p.name);
                }
                // Enemy team eliminated: the only remaining players are a single
                // alliance smaller than the initial roster. In team modes that
                // alliance wins; in FFA a coalition surviving together is a stalemate.
                if (allianceGroup != null
                        && remaining.equals(allianceGroup)
                        && allianceGroup.size() < initialParticipants.size()) {
                    victoryHandled = true;
                    if (isFFA()) {
                        RonInstance.LOGGER.info("MatchLifecycle: FFA survivors all allied — draw: {}", remaining);
                        MatchEndHandler.onDraw(new ArrayList<>(initialParticipants));
                    } else {
                        Set<String> losers = new HashSet<>(initialParticipants);
                        losers.removeAll(allianceGroup);
                        RonInstance.LOGGER.info("MatchLifecycle: Enemy team eliminated — Winners: {}, Losers: {}", allianceGroup, losers);
                        MatchEndHandler.onVictory(new ArrayList<>(allianceGroup), new ArrayList<>(losers));
                    }
                }
            }
        }
    }

    // ========================================================================
    // Disconnect grace period
    // ========================================================================

    private static void startGracePeriod(UUID uuid, String name) {
        broadcast(ChatFormatting.YELLOW + name + " has " + GRACE_PERIOD_SECONDS + "s to reconnect or they forfeit");
        RonInstance.LOGGER.info("Player {} disconnected, starting {}s grace period", name, GRACE_PERIOD_SECONDS);

        gracePeriods.start(uuid, GRACE_PERIOD_SECONDS, () -> {
            RonInstance.LOGGER.info("Player {} grace period expired — forfeited", name);
            if (serverInstance == null) return;
            serverInstance.execute(() -> {
                broadcast(ChatFormatting.RED + name + " forfeited (disconnected)");
                if (phase == Phase.RUNNING) {
                    PlayerServerEvents.defeat(name, "disconnected");
                } else if (phase == Phase.READYING) {
                    int participants = countOnlineParticipants();
                    if (participants < 2) {
                        RonInstance.LOGGER.info("MatchLifecycle: Not enough participants after disconnect, cancelling");
                        broadcast(ChatFormatting.RED + "Match cancelled — not enough players");
                        InstanceStateManager.setState(InstanceState.FINISHED);
                        phase = Phase.IDLE;
                    }
                }
            });
        });
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static boolean hasClaimedSlot(String name) {
        for (StartPos pos : StartPosServerEvents.startPoses) {
            if (name.equals(pos.playerName)) return true;
        }
        return false;
    }

    private static int countOnlineParticipants() {
        if (serverInstance == null) return 0;
        int count = 0;
        for (UUID uuid : PlayerTracker.getAllParticipants()) {
            if (gracePeriods.hasLeft(uuid)) continue;
            if (serverInstance.getPlayerList().getPlayer(uuid) != null) {
                count++;
            }
        }
        return count;
    }

    private static int getExpectedPlayerCount() {
        String currentMap = InstanceStateManager.getCurrentMap();
        String currentMode = InstanceStateManager.getCurrentMode();

        for (InstanceStateManager.MapInfo map : InstanceStateManager.getAvailableMaps()) {
            if (map.folder().equals(currentMap) || map.name().equals(currentMap)) {
                String mode = currentMode != null ? currentMode : map.defaultMode();
                int expected = InstanceStateManager.getExpectedPlayersForMode(currentMap, mode);
                return Math.max(2, expected);
            }
        }
        return 2;
    }

    private static void showWelcomeIfNeeded() {
        if (!welcomeShown.compareAndSet(false, true)) return;
        if (serverInstance == null) return;
        serverInstance.execute(() -> {
            if (phase != Phase.READYING) return;
            broadcastMapCredits();
            broadcast(ChatFormatting.GREEN + "Pick a start position + faction, then click Ready!");
            broadcast(ChatFormatting.YELLOW + "You have " + READYING_SECONDS + "s. Slot = team in this mode.");
        });
    }

    private static void broadcastMapCredits() {
        String currentMap = InstanceStateManager.getCurrentMap();
        for (InstanceStateManager.MapInfo map : InstanceStateManager.getAvailableMaps()) {
            if (map.folder().equals(currentMap) || map.name().equals(currentMap)) {
                if (!map.author().isEmpty()) {
                    String authors = String.join(", ", map.author());
                    broadcast(ChatFormatting.GOLD + "By: " + ChatFormatting.WHITE + authors);
                }
                String mode = InstanceStateManager.getCurrentMode();
                if (mode != null) {
                    broadcast(ChatFormatting.GOLD + "Mode: " + ChatFormatting.WHITE + mode);
                }
                return;
            }
        }
    }

    private static void broadcast(String message) {
        if (serverInstance == null) return;
        serverInstance.getPlayerList().broadcastSystemMessage(
                Component.literal(message), false);
    }

    private static void runCommand(String command) {
        if (serverInstance == null) return;
        serverInstance.getCommands().performPrefixedCommand(
                serverInstance.createCommandSourceStack(), command);
        RonInstance.LOGGER.info("MatchLifecycle: ran /{}", command);
    }

    public static boolean hasLeft(UUID uuid) {
        return gracePeriods.hasLeft(uuid);
    }

    public static Set<UUID> getLeftPlayers() {
        return gracePeriods.getLeftPlayers();
    }

    public static boolean isPrivateMatch() {
        return privateMatch;
    }

    public static void setPrivateMatch(boolean value) {
        privateMatch = value;
        RonInstance.LOGGER.info("Private match: {}", value);
    }

    public static boolean isFFA() {
        return effectiveMode().startsWith("ffa_");
    }

    public static boolean isCoop() {
        return effectiveMode().startsWith("coop_");
    }

    private static String effectiveMode() {
        String currentMode = InstanceStateManager.getCurrentMode();
        if (currentMode != null) return currentMode.toLowerCase();

        String currentMap = InstanceStateManager.getCurrentMap();
        for (InstanceStateManager.MapInfo map : InstanceStateManager.getAvailableMaps()) {
            if (map.folder().equals(currentMap) || map.name().equals(currentMap)) {
                return map.defaultMode().toLowerCase();
            }
        }
        return "";
    }

    // Ranked is decided by the proxy (it knows the queue type + mode policy) and
    // pushed via /ron-setranked. The local default mirrors the old logic so that
    // a proxy that doesn't send the override still gets sensible behavior.
    private static Boolean rankedOverride = null;

    public static void setRankedOverride(Boolean value) {
        rankedOverride = value;
        RonInstance.LOGGER.info("Ranked override set to: {}", value);
    }

    public static boolean isRanked() {
        if (rankedOverride != null) return rankedOverride;
        return !privateMatch && !isFFA() && !isCoop();
    }

    // Alliance locking and fog of war are decided by the lobby/proxy (host choice
    // in private lobbies; defaults otherwise) and pushed via /ron-setalliancelock
    // and /ron-setfog. Defaults when no override arrives: alliances locked except
    // in FFA (which defaults unlocked), fog of war disabled.
    private static Boolean allianceLockOverride = null;
    private static Boolean fogOfWarOverride = null;

    public static void setAllianceLockOverride(Boolean value) {
        allianceLockOverride = value;
        RonInstance.LOGGER.info("Alliance lock override set to: {}", value);
    }

    public static boolean effectiveAllianceLock() {
        return allianceLockOverride != null ? allianceLockOverride : !isFFA();
    }

    public static void setFogOfWarOverride(Boolean value) {
        fogOfWarOverride = value;
        RonInstance.LOGGER.info("Fog of war override set to: {}", value);
    }

    public static boolean effectiveFogOfWar() {
        return fogOfWarOverride != null ? fogOfWarOverride : false;
    }

    // ========================================================================
    // Reset
    // ========================================================================

    public static void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        matchTicks = 0;
        readyTicks = 0;
        emptyTicks = 0;
        expectedPlayers = 0;
        victoryHandled = false;
        privateMatch = false;
        welcomeShown.set(false);
        initialParticipants.clear();

        gracePeriods.reset();
        selections.clear();
        rankedOverride = null;
        allianceLockOverride = null;
        fogOfWarOverride = null;
        AlliancesServerEvents.resetAllAlliances();

        MatchResult.reset();
        PlayerTracker.reset();
        MatchEndHandler.resetMatchEnded();
    }
}
