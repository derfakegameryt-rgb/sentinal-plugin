package de.derfakegamer.sentinel.listener;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;

/**
 * Keeps a vanished staff member's armour and held items blanked for the op viewers who can still see
 * them. A real equipment change would otherwise overwrite the fake AIR we sent, so we re-blank on the
 * next tick after the change settles. Owner-tier vanish is fully hidden, so it needs nothing here.
 */
public final class VanishEquipmentListener implements Listener {
    private final Sentinel plugin;

    public VanishEquipmentListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) { refresh(event.getPlayer()); }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) { refresh(event.getPlayer()); }

    private void refresh(Player staff) {
        if (!plugin.vanish().isVanished(staff.getUniqueId())) return;
        if (plugin.vanish().isHiddenFromAll(staff.getUniqueId())) return;
        plugin.scheduler().runForEntityLater(staff, () -> plugin.vanish().hideEquipmentFromOps(staff), 1L);
    }
}
