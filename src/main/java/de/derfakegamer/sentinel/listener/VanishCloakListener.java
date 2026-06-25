package de.derfakegamer.sentinel.listener;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.potion.PotionEffect;

/**
 * Keeps an admin-vanished staff member "clean" for the ops who can still see them: armour and held
 * items stay blanked, and potion-effect particles stay suppressed. A real equipment or potion change
 * would otherwise overwrite our suppression, so we re-apply on the next tick after the change settles.
 * Owner-tier vanish is fully hidden (no entity rendered), so it needs nothing here.
 */
public final class VanishCloakListener implements Listener {
    private final Sentinel plugin;

    public VanishCloakListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        if (cloaked(event.getPlayer())) {
            plugin.scheduler().runForEntityLater(event.getPlayer(),
                () -> plugin.vanish().hideEquipmentFromOps(event.getPlayer()), 1L);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (cloaked(event.getPlayer())) {
            plugin.scheduler().runForEntityLater(event.getPlayer(),
                () -> plugin.vanish().hideEquipmentFromOps(event.getPlayer()), 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player p) || !cloaked(p)) return;
        PotionEffect added = event.getNewEffect();
        if (added == null || !added.hasParticles()) return;   // a removal, or already stripped
        plugin.scheduler().runForEntityLater(p, () -> plugin.vanish().stripPotionParticles(p), 1L);
    }

    /** True for an admin-tier vanished player (visible to ops, so still needs cloaking). */
    private boolean cloaked(Player p) {
        return plugin.vanish().isVanished(p.getUniqueId())
            && !plugin.vanish().isHiddenFromAll(p.getUniqueId());
    }
}
