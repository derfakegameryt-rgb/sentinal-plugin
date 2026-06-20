package de.derfakegamer.sentinel.storage;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-thread owner of the {@link Database}. Every DB operation runs on this one
 * thread, so the shared JDBC Connection is never used concurrently.
 */
public final class DatabaseExecutor {
    private final Database database;
    private final Logger logger;
    private final Plugin plugin;
    private final ExecutorService exec;

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin) {
        this.database = database;
        this.logger = logger;
        this.plugin = plugin;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Sentinel-DB");
            t.setDaemon(false);
            return t;
        });
    }

    public Database database() { return database; }

    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                database.ensureValid();
                f.complete(work.call());
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB read failed", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    public void execute(Runnable work) {
        exec.execute(() -> {
            try {
                database.ensureValid();
                work.run();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed (operation dropped)", t);
            }
        });
    }

    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (plugin == null) { onMain.accept(delivered); return; }
            plugin.getServer().getScheduler().runTask(plugin, () -> onMain.accept(delivered));
        });
    }

    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
        }
        try { database.close(); } catch (Exception e) {
            logger.log(Level.WARNING, "closing database failed", e);
        }
    }
}
