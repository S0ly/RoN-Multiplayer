package com.ron.common.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private final String url;

    public Database(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public void initialize() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    points INTEGER DEFAULT 0,
                    wins INTEGER DEFAULT 0,
                    losses INTEGER DEFAULT 0,
                    last_played INTEGER DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS matches (
                    id TEXT PRIMARY KEY,
                    instance TEXT NOT NULL,
                    map_folder TEXT,
                    mode TEXT,
                    ranked INTEGER DEFAULT 0,
                    is_private INTEGER DEFAULT 0,
                    state TEXT NOT NULL,
                    started_at INTEGER DEFAULT 0,
                    finished_at INTEGER DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS match_players (
                    match_id TEXT NOT NULL,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    was_winner INTEGER DEFAULT 0,
                    point_delta INTEGER DEFAULT 0,
                    PRIMARY KEY (match_id, uuid),
                    FOREIGN KEY (match_id) REFERENCES matches(id)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_matches_state ON matches(state)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_matches_started_at ON matches(started_at)");
        }
    }
}
