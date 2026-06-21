package de.derfakegamer.sentinel.storage;

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

    private final Database database;
    private final Logger logger;
    private final Plugin plugin;

    /** Always present: serial writer thread (also runs reads on single-connection backends). */
    private final ExecutorService writer;
    /** Present only when the backend supports concurrent reads; otherwise null. */
    private final ExecutorService readers;

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin) {
        this.database = database;
        this.logger = logger;
        this.plugin = plugin;
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
                work.run();
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
                f.complete(work.call());
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed", t);
                f.completeExceptionally(t);
            } finally {
                database.bind(null);
            }
        });
        return f;
    }

    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (plugin == null) { onMain.accept(delivered); return; }
            plugin.getServer().getScheduler().runTask(plugin, () -> onMain.accept(delivered));
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
