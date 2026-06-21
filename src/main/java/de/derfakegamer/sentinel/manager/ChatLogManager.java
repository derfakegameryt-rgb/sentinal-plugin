package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ChatLogEntry;
import de.derfakegamer.sentinel.storage.BatchWriter;
import de.derfakegamer.sentinel.storage.ChatLogDao;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ChatLogManager {
    private static final int BATCH_SIZE = 200;

    private final Sentinel plugin;
    private final ChatLogDao dao;
    private final BatchWriter<ChatLogEntry> batchWriter;

    public ChatLogManager(Sentinel plugin, ChatLogDao dao) {
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

    public void logChat(UUID uuid, String name, String text) {
        long now = System.currentTimeMillis();
        batchWriter.add(new ChatLogEntry(0, uuid, name, "CHAT", text, now));
    }

    public void logCommand(UUID uuid, String name, String cmd) {
        long now = System.currentTimeMillis();
        batchWriter.add(new ChatLogEntry(0, uuid, name, "COMMAND", cmd, now));
    }

    /**
     * Flushes any buffered entries to the DB then reads. Because {@link BatchWriter#flush()}
     * enqueues the batch insert on the single-threaded DB executor before the subsequent
     * read is enqueued, the ordering guarantee is provided by the executor's FIFO queue.
     */
    public CompletableFuture<List<ChatLogEntry>> recent(UUID uuid, int limit) {
        batchWriter.flush();
        return plugin.db().submit(() -> dao.recent(uuid, limit));
    }

    /** Drops entries older than {@code retentionDays} (0 = keep forever). Returns rows removed. */
    public CompletableFuture<Integer> prune(int retentionDays) {
        if (retentionDays <= 0) return CompletableFuture.completedFuture(0);
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        return plugin.db().submitWrite(() -> dao.deleteOlderThan(cutoff));
    }

    /** Flushes the batch writer immediately (call before db.shutdown()). */
    public void flush() {
        batchWriter.flush();
    }
}
