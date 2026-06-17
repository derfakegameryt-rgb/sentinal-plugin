package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.storage.AppealDao;

import java.util.List;
import java.util.UUID;

/** Stores and resolves ban/mute appeals. Accepting an appeal lifts the linked punishment. */
public final class AppealManager {
    private final Sentinel plugin;
    private final AppealDao dao;
    public AppealManager(Sentinel plugin, AppealDao dao) { this.plugin = plugin; this.dao = dao; }

    public boolean hasOpen(UUID uuid) { return dao.hasOpenForTarget(uuid); }
    public List<Appeal> open() { return dao.findOpen(); }

    /** Files an appeal. Returns false if the player already has an open appeal. */
    public boolean submit(UUID uuid, String name, long punishmentId, PunishmentType type, String text, long now) {
        if (dao.hasOpenForTarget(uuid)) return false;
        dao.insert(new Appeal(0, punishmentId, uuid, name, type, text, "OPEN", now, null, 0));
        return true;
    }

    /** Accepts an appeal: lifts the linked punishment (unban/unmute) and marks it accepted. */
    public void accept(Appeal a, String staff, long now) {
        if (a.type() == PunishmentType.MUTE) plugin.punishments().unmute(a.targetUuid(), staff, now).join();
        else plugin.punishments().unban(a.targetUuid(), staff, now).join();
        dao.setStatus(a.id(), "ACCEPTED", staff, now);
    }

    public void deny(Appeal a, String staff, long now) { dao.setStatus(a.id(), "DENIED", staff, now); }
}
