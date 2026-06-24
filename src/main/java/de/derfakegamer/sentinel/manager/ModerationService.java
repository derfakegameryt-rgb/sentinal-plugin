package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Executes a punishment and performs the side effects (broadcast + kick). Shared by commands and GUIs. */
public final class ModerationService {
    private final Sentinel plugin;

    public ModerationService(Sentinel plugin) { this.plugin = plugin; }

    /**
     * Runs {@code sideEffects} on the GLOBAL region thread and completes the returned future after it.
     * Broadcasts and other server-wide work go here; single-player work is scheduled onto that
     * player's entity scheduler from inside the block (see {@link #apply}).
     */
    private CompletableFuture<Void> onGlobal(Runnable sideEffects) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        plugin.scheduler().runGlobal(() -> {
            try { sideEffects.run(); } finally { f.complete(null); }
        });
        return f;
    }

    /** Applies a punishment. Returns a future of false if the target is exempt (nothing recorded). */
    public CompletableFuture<Boolean> apply(UUID issuerId, String issuerName, UUID targetId, String targetName,
                         String ip, PunishmentType type, long expiresAt, String reason) {
        PunishmentManager pm = plugin.punishments();
        CompletableFuture<PunishmentManager.Result> resultFuture = switch (type) {
            case BAN   -> pm.ban(targetId, targetName, issuerId, issuerName, reason, expiresAt);
            case IPBAN -> pm.ipBan(targetId, targetName, ip, issuerId, issuerName, reason, expiresAt);
            case MUTE  -> pm.mute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
            case WARN  -> pm.warn(targetId, targetName, issuerId, issuerName, reason);
            case KICK  -> pm.kick(targetId, targetName, issuerId, issuerName, reason);
            case SHADOWMUTE -> pm.shadowMute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
        };

        return resultFuture.thenCompose(result -> {
            if (!result.isSuccess()) return CompletableFuture.completedFuture(false);
            String auditDetails = (reason == null ? "" : reason)
                + (expiresAt > 0 ? " (" + de.derfakegamer.sentinel.util.TimeFormat.until(expiresAt, System.currentTimeMillis()) + ")" : "");
            plugin.audit().record(issuerName, type.name(), targetName, auditDetails);

            if (type == PunishmentType.SHADOWMUTE) {
                // Covert: only notify staff on the main thread, no public broadcast, no kick.
                net.kyori.adventure.text.Component staffMsg =
                    plugin.messages().plain("shadowmuted", "player", targetName, "reason", reason);
                return onGlobal(() -> notifyStaff(staffMsg))
                    .thenApply(v -> true);
            }

            String key = switch (type) {
                case BAN, IPBAN -> "banned";
                case MUTE       -> "muted";
                case WARN       -> "warned";
                case KICK       -> "kicked";
                case SHADOWMUTE -> "muted"; // unreachable; handled above
            };

            // Capture effectively-final values for the lambda.
            long now = System.currentTimeMillis();
            String dur = de.derfakegamer.sentinel.util.TimeFormat.until(expiresAt, now);

            // Broadcast on the global region; per-player kick/message on the player's entity region.
            return onGlobal(() -> {
                Bukkit.broadcast(plugin.messages().prefixed(key, "player", targetName, "reason", reason));

                Player online = Bukkit.getPlayer(targetId);
                if (online != null) {
                    plugin.scheduler().runForEntity(online, () -> {
                        switch (type) {
                            case BAN, IPBAN -> {
                                String url = plugin.getConfig().getString("appeals.url", "");
                                String appealSuffix = url.isBlank() ? "" : "\n\nAppeal at: " + url;
                                online.kick(plugin.messages().plain("ban-screen", "reason", reason, "duration", dur, "appeal", appealSuffix));
                            }
                            case KICK -> online.kick(plugin.messages().plain("kick-screen", "reason", reason));
                            case MUTE -> online.sendMessage(plugin.messages().prefixed("you-were-muted", "reason", reason, "duration", dur));
                            case WARN -> online.sendMessage(plugin.messages().prefixed("you-were-warned", "reason", reason));
                            default -> {}
                        }
                    });
                }
            }).thenCompose(v -> {
                if (type == PunishmentType.WARN) {
                    return plugin.punishments().warnCount(targetId).thenCompose(count -> {
                        de.derfakegamer.sentinel.model.EscalationAction esc = plugin.escalation().actionFor(count);
                        if (esc != null) {
                            long escExpiresAt = esc.durationMs() == 0 ? 0 : System.currentTimeMillis() + esc.durationMs();
                            return apply(issuerId, issuerName, targetId, targetName, ip, esc.type(), escExpiresAt, esc.reason())
                                .thenApply(ignored -> true);
                        }
                        return CompletableFuture.completedFuture(true);
                    });
                }
                return CompletableFuture.completedFuture(true);
            });
        });
    }

    public CompletableFuture<Boolean> removeBan(UUID issuerId, String issuerName, UUID targetId, String targetName) {
        long now = System.currentTimeMillis();
        return plugin.punishments().unban(targetId, issuerName, now)
            .thenCompose(ok -> {
                if (ok) plugin.audit().record(issuerName, "UNBAN", targetName, "");
                return onGlobal(() -> {
                    if (ok) Bukkit.broadcast(plugin.messages().prefixed("unbanned", "player", targetName, "reason", ""));
                })
                .thenApply(v -> ok);
            });
    }

    public CompletableFuture<Boolean> removeMute(UUID issuerId, String issuerName, UUID targetId, String targetName) {
        long now = System.currentTimeMillis();
        return plugin.punishments().unmute(targetId, issuerName, now)
            .thenCompose(ok -> {
                if (ok) plugin.audit().record(issuerName, "UNMUTE", targetName, "");
                return onGlobal(() -> {
                    if (ok) Bukkit.broadcast(plugin.messages().prefixed("unmuted", "player", targetName, "reason", ""));
                })
                .thenApply(v -> ok);
            });
    }

    private void notifyStaff(net.kyori.adventure.text.Component message) {
        for (org.bukkit.entity.Player op : org.bukkit.Bukkit.getOnlinePlayers())
            if (op.isOp()) op.sendMessage(message);
    }

    public CompletableFuture<Boolean> removeShadowMute(java.util.UUID issuerId, String issuerName, java.util.UUID targetId, String targetName) {
        long now = System.currentTimeMillis();
        return plugin.punishments().unShadowMute(targetId, issuerName, now)
            .thenCompose(ok -> {
                if (ok) plugin.audit().record(issuerName, "UNSHADOWMUTE", targetName, "");
                net.kyori.adventure.text.Component msg = plugin.messages().plain("unshadowmuted", "player", targetName);
                return onGlobal(() -> { if (ok) notifyStaff(msg); })
                    .thenApply(v -> ok);
            });
    }

}
