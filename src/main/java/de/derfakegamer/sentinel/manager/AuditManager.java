package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ActionCount;
import de.derfakegamer.sentinel.model.ActorCount;
import de.derfakegamer.sentinel.model.AuditEntry;
import de.derfakegamer.sentinel.storage.AuditDao;
import de.derfakegamer.sentinel.storage.BatchWriter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Append-only staff-action log + moderation-stat aggregates. Recording is fire-and-forget. */
public final class AuditManager {
    private static final int BATCH_SIZE = 200;

    private final Sentinel plugin;
    private final AuditDao dao;
    private final BatchWriter<AuditEntry> batchWriter;

    public AuditManager(Sentinel plugin, AuditDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        this.batchWriter = new BatchWriter<>(
            BATCH_SIZE,
            entries -> plugin.db().execute(() -> dao.insertBatch(entries)),
            plugin.getLogger()
        );
        // Flush every 2 seconds (40 ticks) via the Bukkit scheduler.
        plugin.getServer().getScheduler().runTaskTimer(plugin, batchWriter::flush, 40L, 40L);
    }

    public void record(String actor, String action, String target, String details) {
        long now = System.currentTimeMillis();
        String a = actor == null ? "?" : actor;
        String d = details == null ? "" : details;
        batchWriter.add(new AuditEntry(0, a, action, target, d, now));
    }

    /** Flushes the batch writer immediately (call before db.shutdown()). */
    public void flush() {
        batchWriter.flush();
    }

    // -----------------------------------------------------------------------
    // Read methods — each flushes first so just-buffered rows are visible.
    // The flush enqueues the insert on the DB executor's FIFO queue; the
    // subsequent submit() is enqueued after it, guaranteeing ordering.
    // -----------------------------------------------------------------------

    public CompletableFuture<List<AuditEntry>> recent(int limit, int offset) {
        batchWriter.flush();
        return plugin.db().submit(() -> dao.recent(limit, offset));
    }

    public CompletableFuture<List<AuditEntry>> recentForTarget(String target, int limit) {
        batchWriter.flush();
        return plugin.db().submit(() -> dao.recentForTarget(target, limit));
    }

    public CompletableFuture<List<ActorCount>> topActors(long since, int limit) {
        batchWriter.flush();
        return plugin.db().submit(() -> dao.topActors(since, limit));
    }

    public CompletableFuture<List<ActionCount>> countsByAction(long since) {
        batchWriter.flush();
        return plugin.db().submit(() -> dao.countsByAction(since));
    }
}
