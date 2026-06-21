package de.derfakegamer.sentinel.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public final class SqliteDatabase implements Database {
    private final Connection connection;

    public SqliteDatabase(File file) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 3000;");
            st.execute("PRAGMA journal_mode = WAL;");
        }
        createSchema();
    }

    @Override public Connection connection() {
        Connection bound = Database.ThreadLocalHolder.CURRENT.get();
        return bound != null ? bound : connection;
    }
    @Override public SqlDialect dialect() { return SqlDialect.SQLITE; }
    @Override public void ensureValid() { /* local file connection: always valid */ }

    @Override public Connection acquire() { return connection; }
    @Override public void release(Connection c) { /* single connection: nothing to return */ }
    @Override public boolean supportsConcurrentReads() { return false; }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : SqlDialect.SQLITE.schemaStatements()) st.executeUpdate(sql);
        }
        SchemaMigrator.migrate(this, Logger.getLogger("Sentinel"));
    }

    @Override public void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
