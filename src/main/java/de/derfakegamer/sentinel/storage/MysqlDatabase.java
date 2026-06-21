package de.derfakegamer.sentinel.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

public final class MysqlDatabase implements Database {
    /** Size of the in-house reader pool. Small on purpose: lean, no HikariCP. */
    private static final int READER_POOL_SIZE = 4;

    private final String url;
    private final String user;
    private final String password;

    /** The single writer connection. Writes (and schema) run on this; reads use the pool. */
    private volatile Connection connection;

    /** Fixed-size in-house reader pool. acquire() borrows; release() returns. */
    private final BlockingQueue<Connection> readers = new ArrayBlockingQueue<>(READER_POOL_SIZE);

    public MysqlDatabase(String host, int port, String database, String user,
                         String password, String properties) throws SQLException {
        String props = (properties == null || properties.isBlank()) ? "" : "?" + properties;
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + props;
        this.user = user;
        this.password = password;
        this.connection = DriverManager.getConnection(url, user, password);
        createSchema();
        for (int i = 0; i < READER_POOL_SIZE; i++) {
            readers.add(DriverManager.getConnection(url, user, password));
        }
    }

    @Override public Connection connection() {
        Connection bound = Database.ThreadLocalHolder.CURRENT.get();
        return bound != null ? bound : connection;
    }

    @Override public SqlDialect dialect() { return SqlDialect.MYSQL; }

    @Override public boolean supportsConcurrentReads() { return true; }

    /**
     * Borrow a reader connection from the pool. The executor calls this for read tasks and
     * binds the result for the task's whole duration. If a borrowed reader has gone stale it is
     * transparently replaced. Always paired with {@link #release(Connection)} in a finally.
     */
    @Override public Connection acquire() {
        Connection c;
        try {
            c = readers.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sentinel: interrupted acquiring reader connection", e);
        }
        try {
            if (c == null || !c.isValid(2)) {
                if (c != null) try { c.close(); } catch (SQLException ignored) {}
                c = DriverManager.getConnection(url, user, password);
            }
        } catch (SQLException e) {
            // Could not validate/replace: return the slot so the pool isn't drained, then fail soft.
            readers.offer(c);
            throw new RuntimeException("Sentinel: MySQL reader acquire failed", e);
        }
        return c;
    }

    @Override public void release(Connection c) {
        if (c == null) return;
        // Never return the writer connection to the reader pool.
        if (c == connection) return;
        if (!readers.offer(c)) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    @Override public void ensureValid() {
        try {
            if (connection != null && connection.isValid(2)) return;
        } catch (SQLException ignored) { /* fall through to reconnect */ }
        try {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
            connection = DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new RuntimeException("Sentinel: MySQL reconnect failed", e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : SqlDialect.MYSQL.schemaStatements()) {
                try {
                    st.executeUpdate(sql);
                } catch (SQLException e) {
                    // MySQL CREATE INDEX has no IF NOT EXISTS; ignore duplicate-index errors on re-run.
                    String m = String.valueOf(e.getMessage()).toLowerCase();
                    boolean duplicateIndex = e.getErrorCode() == 1061   // ER_DUP_KEYNAME (locale-invariant)
                        || m.contains("duplicate key name") || m.contains("already exists");
                    if (!duplicateIndex) throw e;
                }
            }
        }
        SchemaMigrator.migrate(this, Logger.getLogger("Sentinel"));
    }

    @Override public void close() {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
        Connection r;
        while ((r = readers.poll()) != null) {
            try { r.close(); } catch (SQLException ignored) { }
        }
    }
}
