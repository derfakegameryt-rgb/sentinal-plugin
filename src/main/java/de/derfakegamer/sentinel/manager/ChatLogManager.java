package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.ChatLogEntry;
import de.derfakegamer.sentinel.storage.ChatLogDao;

import java.util.List;
import java.util.UUID;

public final class ChatLogManager {
    private final ChatLogDao dao;

    public ChatLogManager(ChatLogDao dao) { this.dao = dao; }

    public void logChat(UUID uuid, String name, String text) { dao.log(uuid, name, "CHAT", text, System.currentTimeMillis()); }
    public void logCommand(UUID uuid, String name, String cmd) { dao.log(uuid, name, "COMMAND", cmd, System.currentTimeMillis()); }
    public List<ChatLogEntry> recent(UUID uuid, int limit) { return dao.recent(uuid, limit); }

    /** Drops entries older than {@code retentionDays} (0 = keep forever). Returns rows removed. */
    public int prune(int retentionDays) {
        if (retentionDays <= 0) return 0;
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        return dao.deleteOlderThan(cutoff);
    }
}
