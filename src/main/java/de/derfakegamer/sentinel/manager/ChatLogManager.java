package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ChatLogEntry;
import de.derfakegamer.sentinel.storage.ChatLogDao;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ChatLogManager {
    private final Sentinel plugin;
    private final ChatLogDao dao;

    public ChatLogManager(Sentinel plugin, ChatLogDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public void logChat(UUID uuid, String name, String text) {
        long now = System.currentTimeMillis();
        plugin.db().execute(() -> dao.log(uuid, name, "CHAT", text, now));
    }

    public void logCommand(UUID uuid, String name, String cmd) {
        long now = System.currentTimeMillis();
        plugin.db().execute(() -> dao.log(uuid, name, "COMMAND", cmd, now));
    }

    public CompletableFuture<List<ChatLogEntry>> recent(UUID uuid, int limit) {
        return plugin.db().submit(() -> dao.recent(uuid, limit));
    }

    /** Drops entries older than {@code retentionDays} (0 = keep forever). Returns rows removed. */
    public CompletableFuture<Integer> prune(int retentionDays) {
        if (retentionDays <= 0) return CompletableFuture.completedFuture(0);
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        return plugin.db().submit(() -> dao.deleteOlderThan(cutoff));
    }
}
