package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrbitalAccessListener implements Listener {
    private final Sentinel plugin;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public OrbitalAccessListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { apply(event.getPlayer()); }

    /** Drop the tracked attachment on disconnect so the map can't grow without bound. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PermissionAttachment att = attachments.remove(event.getPlayer().getUniqueId());
        if (att != null) { try { event.getPlayer().removeAttachment(att); } catch (IllegalArgumentException ignored) {} }
    }

    /** Grants or revokes the orbital permission for a player based on the current allowlist. */
    public void apply(Player player) {
        boolean allowed = plugin.orbitalAccess().isAllowed(player);
        PermissionAttachment att = attachments.get(player.getUniqueId());
        if (allowed && att == null) {
            PermissionAttachment a = player.addAttachment(plugin);
            a.setPermission("sentinel.orbital", true);
            attachments.put(player.getUniqueId(), a);
        } else if (!allowed && att != null) {
            player.removeAttachment(att);
            attachments.remove(player.getUniqueId());
        }
    }
}
