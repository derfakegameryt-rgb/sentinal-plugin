package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

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

    // A tick later, once the player entity has settled in its new state/world.
    private void reapplyNextTick(Player player) {
        plugin.scheduler().runForEntityLater(player, () -> {
            if (player.isOnline()) plugin.nametags().refresh(player);
        }, 2L);
    }
}
