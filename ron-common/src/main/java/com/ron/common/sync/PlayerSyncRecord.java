package com.ron.common.sync;

public class PlayerSyncRecord {
    public String uuid;
    public String name;
    public int points;
    public int wins;
    public int losses;
    public long updatedAt;

    public PlayerSyncRecord() {}

    public PlayerSyncRecord(String uuid, String name, int points, int wins, int losses, long updatedAt) {
        this.uuid = uuid;
        this.name = name;
        this.points = points;
        this.wins = wins;
        this.losses = losses;
        this.updatedAt = updatedAt;
    }
}
