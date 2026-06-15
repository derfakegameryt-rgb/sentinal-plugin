package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.storage.PunishmentDao;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PunishmentManager {
    /** Result of an action that may be blocked (e.g. exempt target). */
    public record Result(boolean success, String message) {
        public boolean isSuccess() { return success; }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    private final PunishmentDao dao;
    private final Set<UUID> exempt;

    public PunishmentManager(PunishmentDao dao, Set<UUID> exempt) {
        this.dao = dao; this.exempt = exempt;
    }

    public boolean isExempt(UUID uuid) { return exempt.contains(uuid); }

    public Result ban(UUID target, String targetName, UUID issuer, String issuerName,
                      String reason, long expiresAt) {
        return record(PunishmentType.BAN, target, targetName, null, issuer, issuerName, reason, expiresAt);
    }

    public Result ipBan(UUID target, String targetName, String ip, UUID issuer, String issuerName,
                        String reason, long expiresAt) {
        return record(PunishmentType.IPBAN, target, targetName, ip, issuer, issuerName, reason, expiresAt);
    }

    public Result mute(UUID target, String targetName, UUID issuer, String issuerName,
                       String reason, long expiresAt) {
        return record(PunishmentType.MUTE, target, targetName, null, issuer, issuerName, reason, expiresAt);
    }

    public Result warn(UUID target, String targetName, UUID issuer, String issuerName, String reason) {
        return record(PunishmentType.WARN, target, targetName, null, issuer, issuerName, reason, 0);
    }

    public Result kick(UUID target, String targetName, UUID issuer, String issuerName, String reason) {
        // kicks are history-only (never "active")
        if (isExempt(target)) return Result.fail("exempt");
        dao.insert(Punishment.builder().type(PunishmentType.KICK).targetUuid(target)
            .targetName(targetName).reason(reason).issuerUuid(issuer).issuerName(issuerName)
            .createdAt(System.currentTimeMillis()).expiresAt(0).active(false).build());
        return Result.ok();
    }

    private Result record(PunishmentType type, UUID target, String targetName, String ip,
                          UUID issuer, String issuerName, String reason, long expiresAt) {
        if (isExempt(target)) return Result.fail("exempt");
        dao.insert(Punishment.builder().type(type).targetUuid(target).targetName(targetName)
            .targetIp(ip).reason(reason).issuerUuid(issuer).issuerName(issuerName)
            .createdAt(System.currentTimeMillis()).expiresAt(expiresAt).active(true).build());
        return Result.ok();
    }

    /** Returns the active ban, lazily deactivating it if expired. */
    public Punishment activeBan(UUID target, long now) {
        return activeOrExpire(PunishmentType.BAN, target, now);
    }

    public Punishment activeMute(UUID target, long now) {
        return activeOrExpire(PunishmentType.MUTE, target, now);
    }

    public Punishment activeIpBan(String ip, long now) {
        Punishment p = dao.findActiveByIp(PunishmentType.IPBAN, ip);
        if (p == null) return null;
        if (p.isExpired(now)) { dao.deactivate(p.id(), "SYSTEM", now); return null; }
        return p;
    }

    private Punishment activeOrExpire(PunishmentType type, UUID target, long now) {
        Punishment p = dao.findActive(type, target);
        if (p == null) return null;
        if (p.isExpired(now)) { dao.deactivate(p.id(), "SYSTEM", now); return null; }
        return p;
    }

    public boolean unban(UUID target, String remover, long now) {
        Punishment p = dao.findActive(PunishmentType.BAN, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    }

    public boolean unmute(UUID target, String remover, long now) {
        Punishment p = dao.findActive(PunishmentType.MUTE, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    }

    public Result shadowMute(java.util.UUID target, String targetName, java.util.UUID issuer,
                             String issuerName, String reason, long expiresAt) {
        return record(PunishmentType.SHADOWMUTE, target, targetName, null, issuer, issuerName, reason, expiresAt);
    }

    public Punishment activeShadowMute(java.util.UUID target, long now) {
        return activeOrExpire(PunishmentType.SHADOWMUTE, target, now);
    }

    public boolean unShadowMute(java.util.UUID target, String remover, long now) {
        Punishment p = dao.findActive(PunishmentType.SHADOWMUTE, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    }

    /** All currently-active punishments of a type, lazily dropping any that have expired. */
    public java.util.List<Punishment> activeList(PunishmentType type, long now) {
        java.util.List<Punishment> out = new java.util.ArrayList<>();
        for (Punishment p : dao.findActiveByType(type)) {
            if (p.isExpired(now)) dao.deactivate(p.id(), "SYSTEM", now);
            else out.add(p);
        }
        return out;
    }

    public int warnCount(UUID target) { return dao.countWarns(target); }

    public List<Punishment> history(UUID target) { return dao.findHistory(target); }
}
