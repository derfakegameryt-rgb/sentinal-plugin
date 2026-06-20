package de.derfakegamer.sentinel.storage;

import java.sql.Connection;

/** A database backend. The single live connection is used only by the DatabaseExecutor thread. */
public interface Database extends AutoCloseable {
    Connection connection();
    SqlDialect dialect();
    /** Ensure the connection is alive; reconnect if it has dropped (no-op for healthy connections). */
    void ensureValid();
    @Override void close();
}
