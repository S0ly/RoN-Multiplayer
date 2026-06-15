package com.ron.proxy;

public enum InstanceState {
    OFFLINE,
    IDLE,
    PREPARING,
    READY,
    RUNNING,
    FINISHED;

    public static InstanceState parse(String raw) {
        if (raw == null) return OFFLINE;
        try {
            return InstanceState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return OFFLINE;
        }
    }

    public boolean isAvailableForMatch() {
        return this == IDLE || this == READY;
    }

    /** True if a player may (re)join a match on an instance in this state — it is READY or RUNNING. */
    public boolean isJoinable() {
        return this == READY || this == RUNNING;
    }
}
