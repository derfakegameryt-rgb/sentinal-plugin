package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.scheduler.Scheduler;
import de.derfakegamer.sentinel.util.Messages;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owner of the {@link Database} and its threads.
 *
 * <p>Two backend shapes:
 * <ul>
 *   <li><b>SQLite</b> ({@code supportsConcurrentReads() == false}) — a single writer thread runs
 *   every task (reads and writes) on the one connection, exactly as before. Before each task the
 *   single connection is bound to the {@link Database}'s ThreadLocal and cleared in a finally.</li>
 *   <li><b>MySQL</b> ({@code supportsConcurrentReads() == true}) — a single writer thread runs
 *   {@code execute} writes (keeping batch ordering stable) on the writer connection, and a fixed
 *   reader pool runs {@code submit} reads. Each read task {@code acquire()}s a pooled connection,
 *   binds it for the whole lambda, then {@code release()}s it in a finally — so an atomic
 *   read-then-write sequence inside one task always uses a single connection.</li>
 * </ul>
 * A connection is therefore never shared by two threads at once, so DAOs need no lock.
 */
public final class DatabaseExecutor {
    /** Number of reader threads for MySQL. Matches the backend's reader-pool size. */
    private static final int READER_THREADS = 4;

    /** Total write attempts on transient (busy/locked) failures = 1 + WRITE_RETRIES. */
    private static final int WRITE_RETRIES = 3;
    private static final long[] WRITE_BACKOFF_MS = {50L, 100L, 200L};

    private final Database database;
    private final Logger logger;
    private final Plugin plugin;
    /** May be null in tests that never call {@link #callbackOrError}. */
    private final Messages messages;
    /** May be null in pure DAO tests; then callbacks run inline. */
    private final Scheduler scheduler;

    /** Always present: serial writer thread (also runs reads on single-connection backends). */
    private final ExecutorService writer;
    /** Present only when the backend supports concurrent reads; otherwise null. */
    private final ExecutorService readers;

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin) {
        this(database, logger, plugin, null, null);
    }

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin, Messages messages) {
        this(database, logger, plugin, messages, null);
    }

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin, Messages messages, Scheduler scheduler) {
        this.database = database;
        this.logger = logger;
        this.plugin = plugin;
        this.messages = messages;
        this.scheduler = scheduler;
        this.writer = Executors.newSingleThreadExecutor(namedFactory("Sentinel-DB"));
        this.readers = database.supportsConcurrentReads()
            ? Executors.newFixedThreadPool(READER_THREADS, namedFactory("Sentinel-DB-Reader"))
            : null;
    }

    private static ThreadFactory namedFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(false);
            return t;
        };
    }

    public Database database() { return database; }

    /** A read. Runs on the reader pool when the backend supports it, else on the writer thread. */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        ExecutorService target = readers != null ? readers : writer;
        boolean pooledReader = readers != null;
        CompletableFuture<T> f = new CompletableFuture<>();
        target.execute(() -> {
            Connection conn = null;
            try {
                database.ensureValid();
                conn = pooledReader ? database.acquire() : database.connection();
                database.bind(conn);
                f.complete(work.call());
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB read failed", t);
                f.completeExceptionally(t);
            } finally {
                database.bind(null);
                if (pooledReader && conn != null) database.release(conn);
            }
        });
        return f;
    }

    /** A write. Always runs on the single writer thread, keeping write/batch ordering stable. */
    public void execute(Runnable work) {
        writer.execute(() -> {
            try {
                database.ensureValid();
                database.bind(database.connection());
                runWithRetry(() -> { work.run(); return null; });
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed (operation dropped)", t);
            } finally {
                database.bind(null);
            }
        });
    }

    /**
     * A value-returning write. Runs on the SAME single writer thread/connection as {@link #execute},
     * so writes never land on the reader pool and stay strictly FIFO-ordered with each other and with
     * {@code execute} writes. This is the path for any operation that writes (INSERT/UPDATE/DELETE/
     * deactivate) but must return a value via a {@link CompletableFuture} — including
     * "lazy-expiry reads" that conditionally write. Pure reads use {@link #submit}.
     *
     * <p>On SQLite this is observably identical to {@code submit} (one thread, one connection).
     */
    public <T> CompletableFuture<T> submitWrite(Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        writer.execute(() -> {
            try {
                database.ensureValid();
                database.bind(database.connection());
                f.complete(runWithRetry(work));
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed", t);
                f.completeExceptionally(t);
            } finally {
                database.bind(null);
            }
        });
        return f;
    }

    /**
     * Runs a write, retrying ONLY on transient failures (SQLITE_BUSY / SQLITE_LOCKED) with a short
     * backoff. A transient failure means the statement never acquired the lock and so did not modify
     * the database — re-running it cannot double-apply. Non-transient errors (constraint, syntax, I/O)
     * are rethrown immediately. Runs on the single writer thread, so the backoff only briefly delays
     * later queued writes — never the server tick.
     *
     * <p>Caveat for multi-statement batches: a JDBC {@code executeBatch()} that fails busy/locked
     * part-way is re-run whole, which could re-insert the already-applied rows. This is harmless for
     * the only batched writers here (audit + chat-log: append-only, AUTOINCREMENT, no unique key — at
     * worst a duplicate log row) and is effectively unreachable on SQLite anyway (busy_timeout blocks
     * rather than returning BUSY). Do NOT route a batch into a uniquely-constrained or partially-
     * committed table through this retry without making the batch itself idempotent/transactional.
     */
    private <T> T runWithRetry(Callable<T> work) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return work.call();
            } catch (Exception e) {
                if (!isTransient(e) || attempt >= WRITE_RETRIES) throw e;
                try {
                    Thread.sleep(WRITE_BACKOFF_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e; // abort the retry; surface the original failure
                }
                attempt++;
            }
        }
    }

    /** True for SQLite "database is busy/locked" conditions, which are safe and worthwhile to retry. */
    static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql) {
                int code = sql.getErrorCode();
                if (code == 5 || code == 6) return true; // SQLITE_BUSY / SQLITE_LOCKED
            }
            String msg = c.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(java.util.Locale.ROOT);
                if (m.contains("busy") || m.contains("locked")) return true;
            }
        }
        return false;
    }

    /** Delivers the result on the GLOBAL region (server-wide side effects). Inline if no scheduler. */
    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (scheduler == null) { onMain.accept(delivered); return; }
            scheduler.runGlobal(() -> onMain.accept(delivered));
        });
    }

    /**
     * Like {@link #callback(CompletableFuture, Consumer)} but routes failures to {@code onError}
     * instead of passing {@code null} to {@code onMain}, logging at SEVERE. Both run on the global region.
     */
    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> {
            Runnable task = (error == null)
                ? () -> onMain.accept(value)
                : () -> {
                    logger.log(Level.SEVERE, "DB operation failed", error);
                    onError.accept(error);
                };
            if (scheduler == null) { task.run(); return; }
            scheduler.runGlobal(task);
        });
    }

    /** Delivers the result on a specific entity's region (player-bound side effects, e.g. opening a GUI). */
    public <T> void callbackFor(Entity entity, CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (scheduler == null) { onMain.accept(delivered); return; }
            if (entity != null) scheduler.runForEntity(entity, () -> onMain.accept(delivered));
            else scheduler.runGlobal(() -> onMain.accept(delivered));
        });
    }

    /**
     * Convenience wrapper: on success runs {@code onSuccess} on the VIEWER's region (so GUI opens land
     * on the right thread); on failure logs SEVERE and sends {@code db-error} to {@code viewer} (if online).
     */
    public <T> void callbackOrError(Player viewer, CompletableFuture<T> future, Consumer<T> onSuccess) {
        future.whenComplete((value, error) -> {
            Runnable task = (error == null)
                ? () -> onSuccess.accept(value)
                : () -> {
                    logger.log(Level.SEVERE, "DB operation failed", error);
                    if (viewer != null && viewer.isOnline() && messages != null)
                        viewer.sendMessage(messages.prefixed("db-error"));
                };
            if (scheduler == null) { task.run(); return; }
            if (viewer != null) scheduler.runForEntity(viewer, task);
            else scheduler.runGlobal(task);
        });
    }

    public void shutdown() {
        writer.shutdown();
        if (readers != null) readers.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
            if (readers != null && !readers.awaitTermination(5, TimeUnit.SECONDS)) readers.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
            if (readers != null) readers.shutdownNow();
        }
        try { database.close(); } catch (Exception e) {
            logger.log(Level.WARNING, "closing database failed", e);
        }
    }
}
