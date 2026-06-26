package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/**
 * Re-applies the floating nametag after events that detach the mounted TextDisplay passenger: death
 * (the display is ejected on respawn) and world changes (the passenger may not survive the transfer).
 * {@link de.derfakegamer.sentinel.manager.NametagManager#refresh} clears the stale entity and remounts.
 */
public final class NametagListener implements Listener {
    private final Sentinel plugin;

    public NametagListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        reapplyNextTick(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reapplyNextTick(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        // Long-distance / cross-world teleports can drop the mounted TextDisplay passenger.
        reapplyNextTick(event.getPlayer());
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        // Mounting a boat/horse/minecart re-stacks passengers and can eject the floating name.
        if (event.getEntered() instanceof Player player) reapplyNextTick(player);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) reapplyNextTick(player);
    }

    // A tick later, once the player entity has settled in its new state/world.
    private void reapplyNextTick(Player player) {
        plugin.scheduler().runForEntityLater(player, () -> {
            if (player.isOnline()) plugin.nametags().refresh(player);
        }, 2L);
    }
}
