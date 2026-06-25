package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/** Owner god-mode: while enabled the owner takes no damage (PvP, fall, lava, fire, drowning, combat-kick). */
public final class OwnerGodListener implements Listener {
    private final Sentinel plugin;

    public OwnerGodListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.ownerProtection().isGod() && plugin.owner().isOwner(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
