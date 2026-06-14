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
        };
        if (!result.isSuccess()) return false;

        String key = switch (type) {
            case BAN, IPBAN -> "banned";
            case MUTE       -> "muted";
            case WARN       -> "warned";
            case KICK       -> "kicked";
        };
        Bukkit.broadcast(plugin.messages().prefixed(key, "player", targetName, "reason", reason));
        if (type == PunishmentType.BAN || type == PunishmentType.IPBAN || type == PunishmentType.KICK)
            kickIfOnline(targetId, reason);
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

    private void kickIfOnline(UUID id, String reason) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.kick(plugin.messages().plain("ban-screen", "reason", reason));
    }
}
