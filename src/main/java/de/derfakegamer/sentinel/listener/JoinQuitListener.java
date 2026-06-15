package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {
    private final Sentinel plugin;

    public JoinQuitListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.vanish().applyOnJoin(event.getPlayer());
        plugin.players().startSession(event.getPlayer().getUniqueId());
    }

    /** Drop staff-chat mode when a player disconnects so it can't linger if the UUID is de-op'd offline. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.staffChat().clear(event.getPlayer().getUniqueId());
        plugin.players().endSession(event.getPlayer().getUniqueId());
    }
}
