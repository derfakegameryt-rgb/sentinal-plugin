package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class MoveListener implements Listener {
    private final Sentinel plugin;

    public MoveListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.freeze().isFrozen(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        // allow looking around, block changing position
        if (to != null && (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ())) {
            event.setCancelled(true);
        }
    }
}
