package com.ron.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class MatchDAO {

    private final Database database;

    public MatchDAO(Database database) {
        this.database = database;
    }

    public void upsert(Match match) throws SQLException {
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO matches (id, instance, map_folder, mode, ranked, is_private, state, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    map_folder = excluded.map_folder,
                    mode = excluded.mode,
                    ranked = excluded.ranked,
                    is_private = excluded.is_private,
                    state = excluded.state,
                    started_at = excluded.started_at,
                    finished_at = excluded.finished_at
                """)) {
            ps.setString(1, match.id());
            ps.setString(2, match.instance());
            ps.setString(3, match.mapFolder());
            ps.setString(4, match.mode());
            ps.setInt(5, match.ranked() ? 1 : 0);
            ps.setInt(6, match.isPrivate() ? 1 : 0);
            ps.setString(7, match.state().name());
            ps.setLong(8, match.startedAt());
            ps.setLong(9, match.finishedAt());
            ps.executeUpdate();
        }
    }

    public void replacePlayers(String matchId, List<MatchPlayer> players) throws SQLException {
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM match_players WHERE match_id = ?")) {
                    del.setString(1, matchId);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO match_players (match_id, uuid, name, was_winner, point_delta) VALUES (?, ?, ?, ?, ?)")) {
                    for (MatchPlayer p : players) {
                        ins.setString(1, p.matchId());
                        ins.setString(2, p.uuid());
                        ins.setString(3, p.name());
                        ins.setInt(4, p.wasWinner() ? 1 : 0);
                        ins.setInt(5, p.pointDelta());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public List<Match> findUnfinished() throws SQLException {
        List<Match> result = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM matches WHERE state IN ('QUEUED', 'ASSIGNED', 'STARTING', 'RUNNING', 'FINISHED')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(readMatch(rs));
            }
        }
        for (Match m : result) {
            m.players().addAll(playersFor(m.id()));
        }
        return result;
    }

    public List<MatchPlayer> playersFor(String matchId) throws SQLException {
        List<MatchPlayer> result = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM match_players WHERE match_id = ?")) {
            ps.setString(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MatchPlayer(
                            rs.getString("match_id"),
                            rs.getString("uuid"),
                            rs.getString("name"),
                            rs.getInt("was_winner") == 1,
                            rs.getInt("point_delta")
                    ));
                }
            }
        }
        return result;
    }

    private Match readMatch(ResultSet rs) throws SQLException {
        Match m = new Match(rs.getString("id"), rs.getString("instance"));
        m.setMapFolder(rs.getString("map_folder"));
        m.setMode(rs.getString("mode"));
        m.setRanked(rs.getInt("ranked") == 1);
        m.setPrivate(rs.getInt("is_private") == 1);
        m.setState(MatchState.valueOf(rs.getString("state")));
        m.setStartedAt(rs.getLong("started_at"));
        m.setFinishedAt(rs.getLong("finished_at"));
        return m;
    }
}
