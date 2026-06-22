package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import de.derfakegamer.sentinel.util.TtlCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PunishmentManager {
    /** Result of an action that may be blocked (e.g. exempt target). */
    public record Result(boolean success, String message) {
        public boolean isSuccess() { return success; }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    /**
     * Short-TTL caches for mute / shadow-mute checks. A cache hit skips the DB query within the
     * window. Accepted staleness: a mute that expires naturally may be reported active for up to
     * ~3 s longer than its actual expiry — acceptable for chat. Explicit mute/unmute calls always
     * invalidate immediately so the issuing server sees the correct state right away.
     *
     * Ban checks are intentionally NOT cached (login path; correctness critical).
     */
    private static final long MUTE_CACHE_TTL_MS = 3_000L;
    private final TtlCache<UUID, Optional<Punishment>> muteCache = new TtlCache<>(MUTE_CACHE_TTL_MS);
    private final TtlCache<UUID, Optional<Punishment>> shadowMuteCache = new TtlCache<>(MUTE_CACHE_TTL_MS);

    private final Sentinel plugin;
    private final PunishmentDao dao;
    private final Set<UUID> exempt;

    public PunishmentManager(Sentinel plugin, PunishmentDao dao, Set<UUID> exempt) {
        this.plugin = plugin;
        this.dao = dao;
        this.exempt = exempt;
    }

    public boolean isExempt(UUID uuid) { return exempt.contains(uuid); }

    public CompletableFuture<Result> ban(UUID target, String targetName, UUID issuer, String issuerName,
                                         String reason, long expiresAt) {
        return record(PunishmentType.BAN, target, targetName, null, issuer, issuerName, reason, expiresAt);
    }

    public CompletableFuture<Result> ipBan(UUID target, String targetName, String ip, UUID issuer,
                                            String issuerName, String reason, long expiresAt) {
        return record(PunishmentType.IPBAN, target, targetName, ip, issuer, issuerName, reason, expiresAt);
    }

    public CompletableFuture<Result> mute(UUID target, String targetName, UUID issuer, String issuerName,
                                           String reason, long expiresAt) {
        if (isExempt(target)) return CompletableFuture.completedFuture(Result.fail("exempt"));
        return plugin.db().submitWrite(() -> {
            Punishment p = Punishment.builder().type(PunishmentType.MUTE).targetUuid(target)
                .targetName(targetName).reason(reason).issuerUuid(issuer).issuerName(issuerName)
                .createdAt(System.currentTimeMillis()).expiresAt(expiresAt).active(true).build();
            dao.insert(p);
            muteCache.invalidate(target);
            notifyWebhook(p);
            return Result.ok();
        });
    }

    public CompletableFuture<Result> warn(UUID target, String targetName, UUID issuer, String issuerName,
                                           String reason) {
        return record(PunishmentType.WARN, target, targetName, null, issuer, issuerName, reason, 0);
    }

    public CompletableFuture<Result> kick(UUID target, String targetName, UUID issuer, String issuerName,
                                           String reason) {
        // kicks are history-only (never "active")
        if (isExempt(target)) return CompletableFuture.completedFuture(Result.fail("exempt"));
        return plugin.db().submitWrite(() -> {
            Punishment p = Punishment.builder().type(PunishmentType.KICK).targetUuid(target)
                .targetName(targetName).reason(reason).issuerUuid(issuer).issuerName(issuerName)
                .createdAt(System.currentTimeMillis()).expiresAt(0).active(false).build();
            dao.insert(p);
            notifyWebhook(p);
            return Result.ok();
        });
    }

    private CompletableFuture<Result> record(PunishmentType type, UUID target, String targetName, String ip,
                                              UUID issuer, String issuerName, String reason, long expiresAt) {
        if (isExempt(target)) return CompletableFuture.completedFuture(Result.fail("exempt"));
        return plugin.db().submitWrite(() -> {
            Punishment p = Punishment.builder().type(type).targetUuid(target).targetName(targetName)
                .targetIp(ip).reason(reason).issuerUuid(issuer).issuerName(issuerName)
                .createdAt(System.currentTimeMillis()).expiresAt(expiresAt).active(true).build();
            dao.insert(p);
            notifyWebhook(p);
            return Result.ok();
        });
    }

    /** Forwards a freshly-recorded punishment to the Discord webhook, if configured. Never throws. */
    private void notifyWebhook(Punishment p) {
        try {
            var webhook = plugin.webhook();
            if (webhook != null) webhook.notifyPunishment(p);
        } catch (Exception ignored) {
            // Notifications are best-effort and must never fail the punishment itself.
        }
    }

    /** Returns the active ban, lazily deactivating it if expired. */
    public CompletableFuture<Punishment> activeBan(UUID target, long now) {
        return plugin.db().submitWrite(() -> activeOrExpire(PunishmentType.BAN, target, now));
    }

    public CompletableFuture<Punishment> activeMute(UUID target, long now) {
        return plugin.db().submitWrite(() ->
            muteCache.get(target, k -> Optional.ofNullable(activeOrExpire(PunishmentType.MUTE, k, now)))
                     .orElse(null));
    }

    public CompletableFuture<Punishment> activeIpBan(String ip, long now) {
        return plugin.db().submitWrite(() -> {
            Punishment p = dao.findActiveByIp(PunishmentType.IPBAN, ip);
            if (p == null) return null;
            if (p.isExpired(now)) { dao.deactivate(p.id(), "SYSTEM", now); return null; }
            return p;
        });
    }

    private Punishment activeOrExpire(PunishmentType type, UUID target, long now) {
        Punishment p = dao.findActive(type, target);
        if (p == null) return null;
        if (p.isExpired(now)) { dao.deactivate(p.id(), "SYSTEM", now); return null; }
        return p;
    }

    public CompletableFuture<Boolean> unban(UUID target, String remover, long now) {
        return plugin.db().submitWrite(() -> {
            Punishment p = dao.findActive(PunishmentType.BAN, target);
            if (p == null) return false;
            dao.deactivate(p.id(), remover, now);
            return true;
        });
    }

    public CompletableFuture<Boolean> unmute(UUID target, String remover, long now) {
        return plugin.db().submitWrite(() -> {
            Punishment p = dao.findActive(PunishmentType.MUTE, target);
            if (p == null) return false;
            dao.deactivate(p.id(), remover, now);
            muteCache.invalidate(target);
            return true;
        });
    }

    public CompletableFuture<Result> shadowMute(UUID target, String targetName, UUID issuer,
                                                 String issuerName, String reason, long expiresAt) {
        if (isExempt(target)) return CompletableFuture.completedFuture(Result.fail("exempt"));
        return plugin.db().submitWrite(() -> {
            Punishment p = Punishment.builder().type(PunishmentType.SHADOWMUTE).targetUuid(target)
                .targetName(targetName).reason(reason).issuerUuid(issuer).issuerName(issuerName)
                .createdAt(System.currentTimeMillis()).expiresAt(expiresAt).active(true).build();
            dao.insert(p);
            shadowMuteCache.invalidate(target);
            notifyWebhook(p);
            return Result.ok();
        });
    }

    public CompletableFuture<Punishment> activeShadowMute(UUID target, long now) {
        return plugin.db().submitWrite(() ->
            shadowMuteCache.get(target, k -> Optional.ofNullable(activeOrExpire(PunishmentType.SHADOWMUTE, k, now)))
                           .orElse(null));
    }

    public CompletableFuture<Boolean> unShadowMute(UUID target, String remover, long now) {
        return plugin.db().submitWrite(() -> {
            Punishment p = dao.findActive(PunishmentType.SHADOWMUTE, target);
            if (p == null) return false;
            dao.deactivate(p.id(), remover, now);
            shadowMuteCache.invalidate(target);
            return true;
        });
    }

    /** All currently-active punishments of a type, lazily dropping any that have expired. */
    public CompletableFuture<List<Punishment>> activeList(PunishmentType type, long now) {
        return plugin.db().submitWrite(() -> {
            List<Punishment> out = new ArrayList<>();
            for (Punishment p : dao.findActiveByType(type)) {
                if (p.isExpired(now)) dao.deactivate(p.id(), "SYSTEM", now);
                else out.add(p);
            }
            return out;
        });
    }

    public CompletableFuture<Integer> warnCount(UUID target) {
        return plugin.db().submit(() -> dao.countWarns(target));
    }

    public CompletableFuture<List<Punishment>> history(UUID target) {
        return plugin.db().submit(() -> dao.findHistory(target));
    }
}
