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

    // Online-player record cache: populated on join, evicted on quit.
    // Cache is an accelerator only — a miss falls through to the DB path.
    private final java.util.concurrent.ConcurrentHashMap<UUID, PlayerRecord> cacheByUuid =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, PlayerRecord> cacheByName =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PlayerDirectory(Sentinel plugin, PlayerDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** Stores a player record in the online cache. Called on join (after {@link #record}). */
    public void cacheOnline(PlayerRecord r) {
        cacheByUuid.put(r.uuid(), r);
        if (r.name() != null) cacheByName.put(r.name().toLowerCase(), r);
    }

    /** Removes a player from the online cache. Called on quit. */
    public void evict(UUID id, String name) {
        cacheByUuid.remove(id);
        if (name != null) cacheByName.remove(name.toLowerCase());
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
        // Populate the online cache with the known join-time fields.
        // playtime is left as 0 — it is stale in the cache intentionally;
        // playtime() and topByPlaytime() always bypass the cache and hit the DB.
        cacheOnline(new PlayerRecord(uuid, name, ip, now, now, 0));
    }

    public CompletableFuture<Long> playtime(UUID uuid) {
        return plugin.db().submit(() -> dao.playtime(uuid));
    }

    public CompletableFuture<List<PlayerRecord>> topByPlaytime(int limit) {
        return plugin.db().submit(() -> dao.topByPlaytime(limit));
    }

    public CompletableFuture<PlayerRecord> byUuid(UUID uuid) {
        PlayerRecord cached = cacheByUuid.get(uuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return plugin.db().submit(() -> dao.byUuid(uuid));
    }

    public CompletableFuture<PlayerRecord> byName(String name) {
        if (name != null) {
            PlayerRecord cached = cacheByName.get(name.toLowerCase());
            if (cached != null) return CompletableFuture.completedFuture(cached);
        }
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
