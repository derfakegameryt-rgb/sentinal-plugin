package de.derfakegamer.sentinel.storage;

import java.sql.Connection;

/**
 * A database backend.
 *
 * <p>Connection model: the {@link DatabaseExecutor} binds exactly one connection to the
 * currently-running task (via a {@link ThreadLocal}) and DAOs call {@link #connection()} to
 * obtain it. SQLite uses a single connection on a single writer thread; MySQL hands each task
 * its own connection (the writer connection for writes, a pooled reader for reads), so a
 * connection is never used by two threads at once and DAOs need no {@code synchronized} guard.
 */
public interface Database extends AutoCloseable {

    /**
     * The connection bound to the currently-running executor task, or the primary connection
     * when no task is bound (e.g. during schema setup or direct/test use).
     */
    Connection connection();

    SqlDialect dialect();

    /** Ensure the connection is alive; reconnect if it has dropped (no-op for healthy connections). */
    void ensureValid();

    /**
     * Borrow a connection for one task. For reads the executor may pass a reader connection;
     * the supplied connection is bound to the task for its whole duration. SQLite returns its
     * single connection; MySQL returns a pooled reader (for reads) or the writer connection.
     */
    Connection acquire();

    /** Return a connection previously obtained from {@link #acquire()}. No-op for SQLite. */
    void release(Connection connection);

    /**
     * Whether this backend can serve reads on connections separate from the writer, allowing
     * the executor to run reads in parallel on a reader pool. False for SQLite (single thread,
     * single connection); true for MySQL.
     */
    boolean supportsConcurrentReads();

    /**
     * Bind {@code connection} as the current task's connection. The executor calls this before
     * running a task and clears it (with {@code null}) in a finally. The default uses a shared
     * {@link ThreadLocalHolder}; both built-in backends rely on it.
     */
    default void bind(Connection connection) { ThreadLocalHolder.CURRENT.set(connection); }

    /** Holder for the per-task connection bound by the executor. */
    final class ThreadLocalHolder {
        static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();
        private ThreadLocalHolder() {}
    }

    @Override void close();
}
