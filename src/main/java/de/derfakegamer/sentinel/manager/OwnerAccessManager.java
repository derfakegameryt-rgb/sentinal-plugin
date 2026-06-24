package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Grants the (only) owner every registered permission for their session — full command access
 *  without the OP flag, so the owner stays out of the ops list. Owner-only; no config, no trace. */
public final class OwnerAccessManager {
    private final Sentinel plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public OwnerAccessManager(Sentinel plugin) { this.plugin = plugin; }

    public void grant(Player player) {
        if (!plugin.owner().isOwner(player)) return;
        revoke(player); // idempotent — never stack attachments
        PermissionAttachment att = player.addAttachment(plugin);
        for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
            att.setPermission(perm, true);
        }
        att.setPermission("minecraft.command.*", true);
        att.setPermission("bukkit.command.*", true);
        attachments.put(player.getUniqueId(), att);
        player.recalculatePermissions();
    }

    public void revoke(Player player) {
        PermissionAttachment att = attachments.remove(player.getUniqueId());
        if (att != null) {
            try { player.removeAttachment(att); } catch (IllegalArgumentException ignored) {}
        }
    }
}
