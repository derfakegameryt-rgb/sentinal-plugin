package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Executes a punishment and performs the side effects (broadcast + kick). Shared by commands and GUIs. */
public final class ModerationService {
    private final Sentinel plugin;

    public ModerationService(Sentinel plugin) { this.plugin = plugin; }

    /** Applies a punishment. Returns false if the target is exempt (nothing recorded). */
    public boolean apply(UUID issuerId, String issuerName, UUID targetId, String targetName,
                         String ip, PunishmentType type, long expiresAt, String reason) {
        PunishmentManager pm = plugin.punishments();
        PunishmentManager.Result result = switch (type) {
            case BAN   -> pm.ban(targetId, targetName, issuerId, issuerName, reason, expiresAt);
            case IPBAN -> pm.ipBan(targetId, targetName, ip, issuerId, issuerName, reason, expiresAt);
            case MUTE  -> pm.mute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
            case WARN  -> pm.warn(targetId, targetName, issuerId, issuerName, reason);
            case KICK  -> pm.kick(targetId, targetName, issuerId, issuerName, reason);
            case SHADOWMUTE -> pm.shadowMute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
        };
        if (!result.isSuccess()) return false;

        if (type == PunishmentType.SHADOWMUTE) {
            notifyStaff(plugin.messages().plain("shadowmuted", "player", targetName, "reason", reason));
            return true; // covert: no public broadcast, no kick
        }

        String key = switch (type) {
            case BAN, IPBAN -> "banned";
            case MUTE       -> "muted";
            case WARN       -> "warned";
            case KICK       -> "kicked";
            case SHADOWMUTE -> "muted";
        };
        Bukkit.broadcast(plugin.messages().prefixed(key, "player", targetName, "reason", reason));
        plugin.discord().post("**" + targetName + "** was " + key + " by " + issuerName
            + (reason == null || reason.isBlank() ? "" : ": " + reason));

        long now = System.currentTimeMillis();
        String dur = de.derfakegamer.sentinel.util.TimeFormat.until(expiresAt, now);
        Player online = Bukkit.getPlayer(targetId);
        if (online != null) {
            switch (type) {
                case BAN, IPBAN -> online.kick(plugin.messages().plain("ban-screen", "reason", reason, "duration", dur));
                case KICK -> online.kick(plugin.messages().plain("kick-screen", "reason", reason));
                case MUTE -> online.sendMessage(plugin.messages().prefixed("you-were-muted", "reason", reason, "duration", dur));
                case WARN -> online.sendMessage(plugin.messages().prefixed("you-were-warned", "reason", reason));
                default -> {}
            }
        }
        if (type == PunishmentType.WARN) {
            int count = plugin.punishments().warnCount(targetId);
            de.derfakegamer.sentinel.model.EscalationAction esc = plugin.escalation().actionFor(count);
            if (esc != null) {
                long escExpiresAt = esc.durationMs() == 0 ? 0 : System.currentTimeMillis() + esc.durationMs();
                apply(issuerId, issuerName, targetId, targetName, ip, esc.type(), escExpiresAt, esc.reason());
            }
        }
        return true;
    }

    public boolean removeBan(UUID issuerId, String issuerName, UUID targetId, String targetName) {
        boolean ok = plugin.punishments().unban(targetId, issuerName, System.currentTimeMillis());
        if (ok) Bukkit.broadcast(plugin.messages().prefixed("unbanned", "player", targetName, "reason", ""));
        return ok;
    }

    public boolean removeMute(UUID issuerId, String issuerName, UUID targetId, String targetName) {
        boolean ok = plugin.punishments().unmute(targetId, issuerName, System.currentTimeMillis());
        if (ok) Bukkit.broadcast(plugin.messages().prefixed("unmuted", "player", targetName, "reason", ""));
        return ok;
    }

    private void notifyStaff(net.kyori.adventure.text.Component message) {
        for (org.bukkit.entity.Player op : org.bukkit.Bukkit.getOnlinePlayers())
            if (op.isOp()) op.sendMessage(message);
    }

    public boolean removeShadowMute(java.util.UUID issuerId, String issuerName, java.util.UUID targetId, String targetName) {
        boolean ok = plugin.punishments().unShadowMute(targetId, issuerName, System.currentTimeMillis());
        if (ok) notifyStaff(plugin.messages().plain("unshadowmuted", "player", targetName));
        return ok;
    }

}
