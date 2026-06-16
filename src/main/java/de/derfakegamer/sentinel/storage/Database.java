package de.derfakegamer.sentinel.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(File file) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 3000;");
            st.execute("PRAGMA journal_mode = WAL;");
        }
        createSchema();
    }

    public Connection connection() { return connection; }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS punishments (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  type TEXT NOT NULL,
                  target_uuid TEXT NOT NULL,
                  target_name TEXT NOT NULL,
                  target_ip TEXT,
                  reason TEXT NOT NULL,
                  issuer_uuid TEXT NOT NULL,
                  issuer_name TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL DEFAULT 0,
                  active INTEGER NOT NULL DEFAULT 1,
                  removed_by TEXT,
                  removed_at INTEGER NOT NULL DEFAULT 0
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pun_target ON punishments(target_uuid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pun_ip ON punishments(target_ip)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reports (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  reporter_uuid TEXT NOT NULL,
                  reporter_name TEXT NOT NULL,
                  target_uuid TEXT NOT NULL,
                  target_name TEXT NOT NULL,
                  reason TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  handled INTEGER NOT NULL DEFAULT 0,
                  handled_by TEXT
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_report_open ON reports(handled)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS appeals (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  punishment_id INTEGER,
                  target_uuid TEXT NOT NULL,
                  target_name TEXT NOT NULL,
                  type TEXT NOT NULL,
                  text TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'OPEN',
                  created_at INTEGER NOT NULL,
                  handled_by TEXT,
                  handled_at INTEGER NOT NULL DEFAULT 0
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appeal_open ON appeals(status)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appeal_target ON appeals(target_uuid)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                  uuid TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  last_ip TEXT,
                  first_seen INTEGER NOT NULL,
                  last_seen INTEGER NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_ip ON players(last_ip)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS notes (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  target_uuid TEXT NOT NULL,
                  author TEXT NOT NULL,
                  text TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_notes_target ON notes(target_uuid)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS chatlog (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uuid TEXT NOT NULL,
                  name TEXT NOT NULL,
                  kind TEXT NOT NULL,      -- CHAT or COMMAND
                  text TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chatlog_uuid ON chatlog(uuid)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS orbital_allowed (
                  uuid TEXT PRIMARY KEY,
                  name TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS scheduled_strikes (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  world TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL,
                  payload TEXT NOT NULL, fire_at INTEGER NOT NULL
                )""");
        }
        try (Statement alter = connection.createStatement()) {
            alter.executeUpdate("ALTER TABLE players ADD COLUMN playtime INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) { /* column already exists */ }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
