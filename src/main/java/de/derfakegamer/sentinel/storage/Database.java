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
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
