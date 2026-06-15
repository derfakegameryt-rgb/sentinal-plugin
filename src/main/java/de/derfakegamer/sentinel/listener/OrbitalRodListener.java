package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.OrbitalRod;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class OrbitalRodListener implements Listener {
    private final Sentinel plugin;

    public OrbitalRodListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        OrbitalPayload payload = OrbitalRod.payloadOf(plugin, event.getItem());
        if (payload == null) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (!p.isOp()) return;
        Block target = p.getTargetBlockExact(320);
        if (target == null) { p.sendMessage(plugin.messages().prefixed("orbital-no-target")); return; }
        plugin.orbital().strike(p.getWorld(), target.getX(), target.getZ(), payload);
        p.sendMessage(plugin.messages().prefixed("orbital-fired",
            "x", String.valueOf(target.getX()), "z", String.valueOf(target.getZ())));
        // consume the one-shot rod
        if (event.getHand() == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(null);
        else p.getInventory().setItemInMainHand(null);
    }
}
