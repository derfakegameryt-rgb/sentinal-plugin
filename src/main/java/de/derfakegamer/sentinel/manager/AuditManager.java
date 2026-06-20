package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ActionCount;
import de.derfakegamer.sentinel.model.ActorCount;
import de.derfakegamer.sentinel.model.AuditEntry;
import de.derfakegamer.sentinel.storage.AuditDao;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Append-only staff-action log + moderation-stat aggregates. Recording is fire-and-forget. */
public final class AuditManager {
    private final Sentinel plugin;
    private final AuditDao dao;
    public AuditManager(Sentinel plugin, AuditDao dao) { this.plugin = plugin; this.dao = dao; }

    public void record(String actor, String action, String target, String details) {
        long now = System.currentTimeMillis();
        String a = actor == null ? "?" : actor;
        String d = details == null ? "" : details;
        plugin.db().execute(() -> dao.insert(a, action, target, d, now));
    }

    public CompletableFuture<List<AuditEntry>> recent(int limit, int offset) {
        return plugin.db().submit(() -> dao.recent(limit, offset));
    }
    public CompletableFuture<List<AuditEntry>> recentForTarget(String target, int limit) {
        return plugin.db().submit(() -> dao.recentForTarget(target, limit));
    }
    public CompletableFuture<List<ActorCount>> topActors(long since, int limit) {
        return plugin.db().submit(() -> dao.topActors(since, limit));
    }
    public CompletableFuture<List<ActionCount>> countsByAction(long since) {
        return plugin.db().submit(() -> dao.countsByAction(since));
    }
}
