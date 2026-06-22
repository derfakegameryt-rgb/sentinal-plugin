package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.storage.AppealDao;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Stores and resolves ban/mute appeals. Accepting an appeal lifts the linked punishment. */
public final class AppealManager {
    private final Sentinel plugin;
    private final AppealDao dao;
    public AppealManager(Sentinel plugin, AppealDao dao) { this.plugin = plugin; this.dao = dao; }

    public CompletableFuture<Boolean> hasOpen(UUID uuid) {
        return plugin.db().submit(() -> dao.hasOpenForTarget(uuid));
    }

    public CompletableFuture<List<Appeal>> open() {
        return plugin.db().submit(() -> dao.findOpen());
    }

    /**
     * Files an appeal. Returns false if the player already has an open appeal.
     * The hasOpen check and insert run atomically on the DB thread.
     */
    public CompletableFuture<Boolean> submit(UUID uuid, String name, long punishmentId, PunishmentType type, String text, long now) {
        return plugin.db().submitWrite(() -> {
            if (dao.hasOpenForTarget(uuid)) return false;
            dao.insert(new Appeal(0, punishmentId, uuid, name, type, text, "OPEN", now, null, 0));
            return true;
        });
    }

    /**
     * Accepts an appeal: lifts the linked punishment (unban/unmute) and marks it accepted.
     * Returns a future that completes once the status has been written to the DB.
     */
    public CompletableFuture<Void> accept(Appeal a, String staff, long now) {
        CompletableFuture<Boolean> liftFuture = a.type() == PunishmentType.MUTE
            ? plugin.punishments().unmute(a.targetUuid(), staff, now)
            : plugin.punishments().unban(a.targetUuid(), staff, now);
        return liftFuture.thenCompose(ok ->
            plugin.db().<Void>submitWrite(() -> { dao.setStatus(a.id(), "ACCEPTED", staff, now); return null; }));
    }

    /** Denies an appeal (fire-and-forget). */
    public void deny(Appeal a, String staff, long now) {
        plugin.db().execute(() -> dao.setStatus(a.id(), "DENIED", staff, now));
    }
}
