package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {
    private final Sentinel plugin;

    public JoinQuitListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.vanish().applyOnJoin(player);

        // Populate the online-player cache now that the player has been fully admitted
        // (past ban/maintenance checks).
        long now = System.currentTimeMillis();
        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        PlayerRecord rec = new PlayerRecord(player.getUniqueId(), player.getName(), ip, now, now, 0);
        plugin.players().cacheOnline(rec);
        plugin.ownerAccess().grant(player);
    }

    /** Drop staff-chat mode when a player disconnects so it can't linger if the UUID is de-op'd offline. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.staffChat().clear(event.getPlayer().getUniqueId());
        plugin.chatModeration().forget(event.getPlayer().getUniqueId());
        plugin.players().evict(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        plugin.ownerAccess().revoke(event.getPlayer());
    }
}
