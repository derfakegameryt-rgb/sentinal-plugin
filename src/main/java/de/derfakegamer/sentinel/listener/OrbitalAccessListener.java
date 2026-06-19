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

    /**
     * Drops every tracked attachment (for any still-online player) and clears the map.
     * Called from onDisable so a reload/shutdown leaves no dangling permission attachments.
     */
    public void removeAll() {
        for (Map.Entry<UUID, PermissionAttachment> e : attachments.entrySet()) {
            Player player = plugin.getServer().getPlayer(e.getKey());
            if (player != null) {
                try { player.removeAttachment(e.getValue()); } catch (Throwable ignored) {}
            }
        }
        attachments.clear();
    }

    /**
     * Re-grants this player's runtime permissions from current state:
     * orbital-strike access for the owner + allowlisted players, and full command
     * access (sentinel.use) for the owner — so the owner can use everything without OP.
     */
    public void apply(Player player) {
        boolean owner = plugin.owner().isOwner(player);
        boolean orbital = plugin.orbitalAccess().isAllowed(player); // already includes the owner

        PermissionAttachment old = attachments.remove(player.getUniqueId());
        if (old != null) { try { player.removeAttachment(old); } catch (IllegalArgumentException ignored) {} }

        if (owner || orbital) {
            PermissionAttachment a = player.addAttachment(plugin);
            if (orbital) a.setPermission("sentinel.orbital", true);
            if (owner) a.setPermission("sentinel.use", true);
            attachments.put(player.getUniqueId(), a);
        }

        // Re-send the client's command tree so tab-completion reflects the just-granted
        // permissions immediately — without this the owner wouldn't see the (runtime-registered)
        // orbitalstrike command, nor reliably tab-fill the rest, until a relog.
        try { player.updateCommands(); } catch (Throwable ignored) { /* unsupported in test JVM */ }
    }
}
