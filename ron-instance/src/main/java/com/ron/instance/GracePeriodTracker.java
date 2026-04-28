package com.ron.instance;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the disconnect grace period for participants. A reconnect within the
 * window cancels the forfeit; the window expiring schedules an onExpire callback
 * onto the server thread.
 */
final class GracePeriodTracker {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RoN-MatchLifecycle");
        t.setDaemon(true);
        return t;
    });

    private final java.util.Map<UUID, ScheduledFuture<?>> gracePeriods = new ConcurrentHashMap<>();
    private final Set<UUID> leftPlayers = ConcurrentHashMap.newKeySet();

    void start(UUID uuid, long graceSeconds, Runnable onExpire) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            leftPlayers.add(uuid);
            gracePeriods.remove(uuid);
            onExpire.run();
        }, graceSeconds, TimeUnit.SECONDS);
        gracePeriods.put(uuid, future);
    }

    boolean cancel(UUID uuid) {
        ScheduledFuture<?> future = gracePeriods.remove(uuid);
        if (future != null) {
            future.cancel(false);
            return true;
        }
        return false;
    }

    boolean hasLeft(UUID uuid) {
        return leftPlayers.contains(uuid);
    }

    Set<UUID> getLeftPlayers() {
        return Set.copyOf(leftPlayers);
    }

    void scheduleAfter(long seconds, Runnable task) {
        scheduler.schedule(task, seconds, TimeUnit.SECONDS);
    }

    void reset() {
        gracePeriods.values().forEach(f -> f.cancel(false));
        gracePeriods.clear();
        leftPlayers.clear();
    }
}
