package com.ron.instance;

import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.player.PlayerClientboundPacket;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosClientboundPacket;
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

/**
 * Unified match lifecycle.
 *
 * State machine: IDLE → READYING → STARTING → RUNNING → (FINISHED via InstanceState)
 *
 * Players are auto-flipped into the RTS camera on login. They become
 * participants by clicking a StartPos slot (which atomically picks slot + faction).
 * When everyone is ready or the deadline expires, RoN's own startGameCountdown
 * runs and at tick 0 promotes spectators to active RTS players, auto-allies
 * teammates by colorId, and locks new joiners.
 */
public class MatchLifecycle {

    private static final int READYING_SECONDS = 120;
    private static final int GRACE_PERIOD_SECONDS = 120;
    private static final int MAX_MATCH_SECONDS = 7200;
    private static final int READY_TIMEOUT_SECONDS = 300;
    private static final int STARTING_TIMEOUT_SECONDS = 15;
    private static final int EMPTY_MATCH_ABANDON_SECONDS = 60;
    private static final int WELCOME_DELAY_SECONDS = 5;

    enum Phase { IDLE, READYING, STARTING, RUNNING }

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
            RonInstance.LOGGER.info("MatchLifecycle: READYING phase ({} slots for mode)", expectedPlayers);
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
            case STARTING -> tickStarting();
            case RUNNING -> tickRunning();
        }
    }

    // ========================================================================
    // Phase: READYING
    // ========================================================================

    private static void tickReadying() {
        // Block premature starts; we drive the countdown ourselves.
        StartPosServerEvents.cancelStartGameCountdown(true);

        phaseTicks++;
        selections.refreshAll();

        int slotsClaimed = 0;
        int slotsReady = 0;
        for (StartPos pos : StartPosServerEvents.startPoses) {
            if (!pos.playerName.isEmpty()) {
                slotsClaimed++;
                if (pos.faction != Faction.NONE) slotsReady++;
            }
        }

        // Early start: everyone expected has claimed and picked.
        if (slotsReady >= expectedPlayers && slotsClaimed == slotsReady && slotsReady >= 2) {
            broadcast(ChatFormatting.GREEN + "All players ready! Starting game...");
            populateParticipants();
            beginStarting();
            return;
        }

        // Deadline.
        if (phaseTicks >= READYING_SECONDS * 20) {
            if (slotsClaimed < 2) {
                RonInstance.LOGGER.warn("MatchLifecycle: Readying timeout with {} slots claimed, cancelling", slotsClaimed);
                broadcast(ChatFormatting.RED + "Match cancelled — not enough players ready.");
                InstanceStateManager.setState(InstanceState.FINISHED);
                phase = Phase.IDLE;
                return;
            }
            RonInstance.LOGGER.info("MatchLifecycle: Readying timeout, auto-assigning + starting with {} players", slotsClaimed);
            populateParticipants();
            autoAssignFactions();
            beginStarting();
            return;
        }

        // Periodic status pings.
        int secondsLeft = (READYING_SECONDS * 20 - phaseTicks) / 20;
        if (phaseTicks % 20 == 0) {
            if (secondsLeft == 60 || secondsLeft == 30 || secondsLeft == 15 || secondsLeft == 10
                    || (secondsLeft <= 5 && secondsLeft > 0)) {
                broadcast(ChatFormatting.YELLOW + "" + secondsLeft + "s left — picks: " + slotsReady + "/" + expectedPlayers);
            }
        }
    }

    private static void populateParticipants() {
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
    // Phase: STARTING
    // ========================================================================

    private static void beginStarting() {
        phase = Phase.STARTING;
        phaseTicks = 0;
        RonInstance.LOGGER.info("MatchLifecycle: STARTING — mode={}, map={}, isCoop={}, isFFA={}, isRanked={}",
                InstanceStateManager.getCurrentMode(), InstanceStateManager.getCurrentMap(),
                isCoop(), isFFA(), isRanked());
        runCommand("gamerule coopMode " + isCoop());
        runCommand("gamerule lockAlliances " + !isFFA());
        StartPosServerEvents.startGameCountdown();
    }

    private static void tickStarting() {
        phaseTicks++;

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

        if (phaseTicks >= STARTING_TIMEOUT_SECONDS * 20) {
            RonInstance.LOGGER.error("MatchLifecycle: STARTING timeout — game never started");
            StartPosServerEvents.cancelStartGameCountdown(true);
            broadcast(ChatFormatting.RED + "Match cancelled — failed to start");
            InstanceStateManager.setState(InstanceState.FINISHED);
            phase = Phase.IDLE;
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
                // Only declare draw if an enemy team was eliminated. If the remaining
                // alliance group is the same size as initial participants, everyone
                // started on the same team (coop/single-team) and no enemy ever existed.
                if (allianceGroup != null
                        && remaining.equals(allianceGroup)
                        && allianceGroup.size() < initialParticipants.size()) {
                    victoryHandled = true;
                    RonInstance.LOGGER.info("MatchLifecycle: All remaining players allied — draw: {}", remaining);
                    MatchEndHandler.onDraw(new ArrayList<>(initialParticipants));
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

    private static void autoAssignFactions() {
        Faction[] factions = {Faction.VILLAGERS, Faction.MONSTERS, Faction.PIGLINS};
        Random random = new Random();

        for (StartPos pos : StartPosServerEvents.startPoses) {
            if (pos.playerName.isEmpty()) continue;
            if (pos.faction != Faction.NONE) continue;

            Faction randomFaction = factions[random.nextInt(factions.length)];
            pos.faction = randomFaction;
            StartPosClientboundPacket.reservePos(pos.pos, randomFaction, pos.playerName);

            ServerPlayer sp = serverInstance != null
                    ? serverInstance.getPlayerList().getPlayerByName(pos.playerName) : null;
            if (sp != null) {
                sp.sendSystemMessage(Component.literal(
                        "Time's up! You were assigned " + randomFaction.name() + ".").withStyle(ChatFormatting.YELLOW));
            }
            RonInstance.LOGGER.info("MatchLifecycle: Auto-assigned {} to {} at pos {}", pos.playerName, randomFaction.name(), pos.pos);
        }
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
            broadcast(ChatFormatting.GREEN + "Pick a start position + faction to ready up!");
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

        MatchResult.reset();
        PlayerTracker.reset();
        MatchEndHandler.resetMatchEnded();
    }
}
