package com.ron.common.db;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Match {

    private final String id;
    private final String instance;
    private String mapFolder;
    private String mode;
    private boolean ranked;
    private boolean isPrivate;
    private MatchState state;
    private long startedAt;
    private long finishedAt;
    private final List<MatchPlayer> players = new ArrayList<>();

    public Match(String id, String instance) {
        this.id = id;
        this.instance = instance;
        this.state = MatchState.QUEUED;
    }

    public static Match create(String instance) {
        return new Match(UUID.randomUUID().toString(), instance);
    }

    public String id() { return id; }
    public String instance() { return instance; }
    public String mapFolder() { return mapFolder; }
    public String mode() { return mode; }
    public boolean ranked() { return ranked; }
    public boolean isPrivate() { return isPrivate; }
    public MatchState state() { return state; }
    public long startedAt() { return startedAt; }
    public long finishedAt() { return finishedAt; }
    public List<MatchPlayer> players() { return players; }

    public void setMapFolder(String mapFolder) { this.mapFolder = mapFolder; }
    public void setMode(String mode) { this.mode = mode; }
    public void setRanked(boolean ranked) { this.ranked = ranked; }
    public void setPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }
    public void setState(MatchState state) { this.state = state; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
}
