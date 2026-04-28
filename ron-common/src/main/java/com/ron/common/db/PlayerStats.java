package com.ron.common.db;

public class PlayerStats {
    public final String uuid;
    public String name;
    public int points;
    public int wins;
    public int losses;
    public long lastPlayed;

    public PlayerStats(String uuid, String name, int points, int wins, int losses, long lastPlayed) {
        this.uuid = uuid;
        this.name = name;
        this.points = points;
        this.wins = wins;
        this.losses = losses;
        this.lastPlayed = lastPlayed;
    }

    public PlayerStats(String uuid, String name) {
        this(uuid, name, 0, 0, 0, System.currentTimeMillis());
    }
}
