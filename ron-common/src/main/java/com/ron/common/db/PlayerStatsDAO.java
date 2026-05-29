package com.ron.common.db;

import com.ron.common.scoring.ScoringUtil;
import com.ron.common.sync.PlayerSyncRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PlayerStatsDAO {

    private final Database database;

    public PlayerStatsDAO(Database database) {
        this.database = database;
    }

    public PlayerStats getOrCreate(String uuid, String name) throws SQLException {
        try (Connection conn = database.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (!rs.getString("name").equals(name)) {
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE players SET name = ? WHERE uuid = ?")) {
                                update.setString(1, name);
                                update.setString(2, uuid);
                                update.executeUpdate();
                            }
                        }
                        return new PlayerStats(
                            rs.getString("uuid"),
                            name,
                            rs.getInt("points"),
                            rs.getInt("wins"),
                            rs.getInt("losses"),
                            rs.getLong("last_played")
                        );
                    }
                }
            }

            PlayerStats stats = new PlayerStats(uuid, name);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (uuid, name, points, wins, losses, last_played) VALUES (?, ?, 0, 0, 0, ?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return stats;
        }
    }

    public void recordWin(String uuid, String name, int opponentAvgScore) throws SQLException {
        PlayerStats stats = getOrCreate(uuid, name);
        int gained = ScoringUtil.calculateWinPoints(stats.points, opponentAvgScore);
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET points = points + ?, wins = wins + 1, last_played = ? WHERE uuid = ?")) {
            ps.setInt(1, gained);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid);
            ps.executeUpdate();
        }
    }

    public void recordLoss(String uuid, String name, int opponentAvgScore) throws SQLException {
        PlayerStats stats = getOrCreate(uuid, name);
        int lost = ScoringUtil.calculateLossPoints(stats.points, opponentAvgScore);
        int newPoints = Math.max(0, stats.points - lost);
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET points = ?, losses = losses + 1, last_played = ? WHERE uuid = ?")) {
            ps.setInt(1, newPoints);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid);
            ps.executeUpdate();
        }
    }

    public List<PlayerStats> getUpdatedSince(long timestamp) throws SQLException {
        List<PlayerStats> rows = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM players WHERE last_played > ? ORDER BY last_played ASC")) {
            ps.setLong(1, timestamp);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PlayerStats(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getInt("points"),
                        rs.getInt("wins"),
                        rs.getInt("losses"),
                        rs.getLong("last_played")
                    ));
                }
            }
        }
        return rows;
    }

    public List<PlayerStats> getByUuids(Collection<String> uuids) throws SQLException {
        List<PlayerStats> rows = new ArrayList<>();
        if (uuids.isEmpty()) return rows;
        StringBuilder sql = new StringBuilder("SELECT * FROM players WHERE uuid IN (");
        for (int i = 0; i < uuids.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(")");
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            for (String uuid : uuids) ps.setString(i++, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PlayerStats(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getInt("points"),
                        rs.getInt("wins"),
                        rs.getInt("losses"),
                        rs.getLong("last_played")
                    ));
                }
            }
        }
        return rows;
    }

    public boolean upsertFromSync(PlayerSyncRecord r) throws SQLException {
        try (Connection conn = database.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO players (uuid, name, points, wins, losses, last_played) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, r.uuid);
                ps.setString(2, r.name);
                ps.setInt(3, r.points);
                ps.setInt(4, r.wins);
                ps.setInt(5, r.losses);
                ps.setLong(6, r.updatedAt);
                if (ps.executeUpdate() > 0) return true;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET name = ?, points = ?, wins = ?, losses = ?, last_played = ? WHERE uuid = ? AND last_played < ?")) {
                ps.setString(1, r.name);
                ps.setInt(2, r.points);
                ps.setInt(3, r.wins);
                ps.setInt(4, r.losses);
                ps.setLong(5, r.updatedAt);
                ps.setString(6, r.uuid);
                ps.setLong(7, r.updatedAt);
                return ps.executeUpdate() > 0;
            }
        }
    }

    public List<PlayerStats> getTopPlayers(int limit) throws SQLException {
        List<PlayerStats> top = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM players ORDER BY points DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    top.add(new PlayerStats(
                        rs.getString("uuid"),
                        rs.getString("name"),
                        rs.getInt("points"),
                        rs.getInt("wins"),
                        rs.getInt("losses"),
                        rs.getLong("last_played")
                    ));
                }
            }
        }
        return top;
    }
}
