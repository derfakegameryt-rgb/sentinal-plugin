package de.derfakegamer.sentinel.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class MysqlDatabase implements Database {
    private final String url;
    private final String user;
    private final String password;
    private volatile Connection connection;

    public MysqlDatabase(String host, int port, String database, String user,
                         String password, String properties) throws SQLException {
        String props = (properties == null || properties.isBlank()) ? "" : "?" + properties;
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + props;
        this.user = user;
        this.password = password;
        this.connection = DriverManager.getConnection(url, user, password);
        createSchema();
    }

    @Override public Connection connection() { return connection; }
    @Override public SqlDialect dialect() { return SqlDialect.MYSQL; }

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
                    // MySQL CREATE INDEX has no IF NOT EXISTS; ignore "duplicate key name" on re-run.
                    String m = String.valueOf(e.getMessage()).toLowerCase();
                    if (!m.contains("duplicate key name") && !m.contains("already exists")) throw e;
                }
            }
        }
    }

    @Override public void close() {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
    }
}
