package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.storage.PlayerDao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerDirectory {
    private final Sentinel plugin;
    private final PlayerDao dao;
    private final java.util.Map<java.util.UUID, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    public PlayerDirectory(Sentinel plugin, PlayerDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    public void startSession(UUID uuid) { sessions.put(uuid, System.currentTimeMillis()); }

    public void endSession(UUID uuid) {
        Long start = sessions.remove(uuid);
        if (start != null) {
            long elapsed = System.currentTimeMillis() - start;
            plugin.db().execute(() -> dao.addPlaytime(uuid, elapsed));
        }
    }

    /** Commits every open session (called on shutdown so a /restart doesn't lose live playtime). */
    public void flushSessions() {
        for (UUID id : new ArrayList<>(sessions.keySet())) endSession(id);
    }

    public void record(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        plugin.db().execute(() -> dao.upsert(uuid, name, ip, now));
    }

    public CompletableFuture<Long> playtime(UUID uuid) {
        return plugin.db().submit(() -> dao.playtime(uuid));
    }

    public CompletableFuture<List<PlayerRecord>> topByPlaytime(int limit) {
        return plugin.db().submit(() -> dao.topByPlaytime(limit));
    }

    public CompletableFuture<PlayerRecord> byUuid(UUID uuid) {
        return plugin.db().submit(() -> dao.byUuid(uuid));
    }

    public CompletableFuture<PlayerRecord> byName(String name) {
        return plugin.db().submit(() -> dao.byName(name));
    }

    /** Other accounts that share this player's last IP. Entire lookup runs atomically on the DB thread. */
    public CompletableFuture<List<PlayerRecord>> alts(UUID uuid) {
        return plugin.db().submit(() -> {
            PlayerRecord self = dao.byUuid(uuid);
            if (self == null || self.lastIp() == null) return List.<PlayerRecord>of();
            List<PlayerRecord> out = new ArrayList<>();
            for (PlayerRecord r : dao.byIp(self.lastIp()))
                if (!r.uuid().equals(uuid)) out.add(r);
            return out;
        });
    }
}
