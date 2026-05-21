package com.ron.common.db;

public enum MatchState {
    QUEUED,
    ASSIGNED,
    STARTING,
    RUNNING,
    FINISHED,
    ABANDONED;

    public boolean isTerminal() {
        return this == FINISHED || this == ABANDONED;
    }

    public boolean isActive() {
        return this == ASSIGNED || this == STARTING || this == RUNNING;
    }
}
