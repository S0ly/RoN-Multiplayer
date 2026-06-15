package com.ron.proxy;

import com.ron.common.db.Match;
import com.ron.common.db.MatchDAO;
import com.ron.common.db.MatchPlayer;
import com.ron.common.db.MatchState;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle of every match. Replaces the scattered pending* maps
 * that used to live on InstanceTracker. One active match per instance at most.
 */
public class MatchService {

    public record ReadyPayload(String mode, boolean isPrivate, boolean ranked, boolean lockAlliances,
                               boolean fogOfWar, Map<String, Integer> scores) {}

    /**
     * Proxy-owned ranked policy. Ranked when: not private + not FFA + not coop.
     * Mode names that map to FFA/coop are detected by prefix to match instance-side logic.
     */
    public static boolean computeRanked(String mode, boolean isPrivate) {
        if (isPrivate) return false;
        if (mode == null) return true;
        String m = mode.toLowerCase();
        if (m.startsWith("ffa_")) return false;
        if (m.startsWith("coop_")) return false;
        return true;
    }

    private final Logger logger;
    private final MatchDAO matchDAO;
    private final Map<String, Match> activeByInstance = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> pendingScoresByMatch = new ConcurrentHashMap<>();
    private final Set<String> awaitingReady = ConcurrentHashMap.newKeySet();
    private final Set<String> handledFinished = ConcurrentHashMap.newKeySet();

    public MatchService(Logger logger, MatchDAO matchDAO) {
        this.logger = logger;
        this.matchDAO = matchDAO;
    }

    /** Called when a match is assigned to an instance (proxy picked it for a queue). */
    public Match prepareMatch(String instance, String mapFolder, String mode,
                              boolean lockAlliances, boolean fogOfWar) {
        Match m = Match.create(instance);
        m.setMapFolder(mapFolder);
        m.setMode(mode);
        m.setLockAlliances(lockAlliances);
        m.setFogOfWar(fogOfWar);
        m.setState(MatchState.ASSIGNED);
        activeByInstance.put(instance, m);
        persist(m);
        logger.info("[{}] Match prepared: id={} map={} mode={} lockAlliances={} fogOfWar={}",
                instance, m.id(), mapFolder, mode, lockAlliances, fogOfWar);
        return m;
    }

    /** Attach players + private flag + score snapshot. Called from TRANSFER handler. */
    public void attachPlayers(String instance,
                              List<MatchPlayer> players,
                              Map<String, Integer> scores,
                              boolean isPrivate) {
        Match m = activeByInstance.get(instance);
        if (m == null) {
            logger.warn("attachPlayers: no active match for {}", instance);
            return;
        }
        m.players().clear();
        m.players().addAll(players);
        m.setPrivate(isPrivate);
        m.setRanked(computeRanked(m.mode(), isPrivate));
        if (scores != null && !scores.isEmpty()) {
            pendingScoresByMatch.put(m.id(), scores);
        }
        persist(m);
    }

    /** Lobby confirmed; we'll send deferred data when the instance reaches READY. */
    public void awaitReady(String instance) {
        awaitingReady.add(instance);
    }

    /**
     * Drain pending data for an instance just transitioned to READY.
     * Returns empty if there's nothing to send (no active match, or already drained).
     */
    public Optional<ReadyPayload> consumeReadyPayload(String instance) {
        if (!awaitingReady.remove(instance)) return Optional.empty();
        Match m = activeByInstance.get(instance);
        if (m == null) return Optional.empty();
        Map<String, Integer> scores = pendingScoresByMatch.remove(m.id());
        return Optional.of(new ReadyPayload(m.mode(), m.isPrivate(), m.ranked(),
                m.lockAlliances(), m.fogOfWar(), scores));
    }

    public void onRunning(String instance) {
        Match m = activeByInstance.get(instance);
        if (m == null) return;
        if (m.state() == MatchState.RUNNING) return;
        m.setState(MatchState.RUNNING);
        if (m.startedAt() == 0) m.setStartedAt(System.currentTimeMillis());
        persist(m);
    }

    /** Idempotency gate for FINISHED handling. Returns true the first time per instance. */
    public boolean markFinished(String instance) {
        return handledFinished.add(instance);
    }

    /** Updates the Match record to FINISHED if one exists. Empty for orphan matches. */
    public Optional<Match> onFinished(String instance, boolean ranked) {
        Match m = activeByInstance.get(instance);
        if (m == null) return Optional.empty();
        m.setState(MatchState.FINISHED);
        m.setRanked(ranked);
        if (m.finishedAt() == 0) m.setFinishedAt(System.currentTimeMillis());
        persist(m);
        return Optional.of(m);
    }

    /** Update player results (winner flag + point delta) after a finished match. */
    public void recordResults(String instance, List<MatchPlayer> updatedPlayers) {
        Match m = activeByInstance.get(instance);
        if (m == null) return;
        m.players().clear();
        m.players().addAll(updatedPlayers);
        persistPlayers(m);
    }

    /** Lobby asked us to abandon a confirmed-but-not-yet-running match. */
    public void cancelMatch(String instance) {
        awaitingReady.remove(instance);
        handledFinished.remove(instance);
        Match m = activeByInstance.remove(instance);
        if (m == null) return;
        pendingScoresByMatch.remove(m.id());
        m.setState(MatchState.ABANDONED);
        if (m.finishedAt() == 0) m.setFinishedAt(System.currentTimeMillis());
        persist(m);
        logger.info("[{}] Match {} cancelled by lobby", instance, m.id());
    }

    /** Called when instance has been confirmed to return to IDLE. */
    public void onIdle(String instance) {
        handledFinished.remove(instance);
        awaitingReady.remove(instance);
        Match m = activeByInstance.remove(instance);
        if (m == null) return;
        if (!m.state().isTerminal()) {
            logger.info("[{}] Match {} abandoned (state was {})", instance, m.id(), m.state());
            m.setState(MatchState.ABANDONED);
            if (m.finishedAt() == 0) m.setFinishedAt(System.currentTimeMillis());
            persist(m);
        }
    }

    /** Instance dropped offline mid-match. Keep the Match around in case it comes back. */
    public void onOffline(String instance) {
        // intentional no-op for state changes; we only mark ABANDONED on confirmed IDLE
    }

    public Optional<Match> matchOn(String instance) {
        return Optional.ofNullable(activeByInstance.get(instance));
    }

    public Collection<Match> activeMatches() {
        return List.copyOf(activeByInstance.values());
    }

    /** For step 4 (restart recovery): inject a Match loaded from DB. */
    public void rehydrate(Match match) {
        activeByInstance.put(match.instance(), match);
    }

    private void persist(Match m) {
        if (matchDAO == null) return;
        try {
            matchDAO.upsert(m);
        } catch (SQLException e) {
            logger.error("Failed to persist match {}", m.id(), e);
        }
    }

    private void persistPlayers(Match m) {
        if (matchDAO == null) return;
        try {
            matchDAO.replacePlayers(m.id(), m.players());
        } catch (SQLException e) {
            logger.error("Failed to persist players for match {}", m.id(), e);
        }
    }
}
