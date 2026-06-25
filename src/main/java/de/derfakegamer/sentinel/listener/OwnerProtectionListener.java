package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.OwnerProtectionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class OwnerProtectionListener implements Listener {
    private final Sentinel plugin;
    public OwnerProtectionListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.ownerProtection().isEnabled()) return;
        Player p = event.getPlayer();
        if (plugin.owner().isOwner(p)) return;   // the owner may target himself
        if (OwnerProtectionManager.affectsOwner(event.getMessage(),
                plugin.ownerProtection().ownerName(), plugin.owner().uuid().toString())) {
            event.setCancelled(true);
            p.sendMessage(Component.text("that entity does not exist", NamedTextColor.RED));
            plugin.ownerProtection().recordAttempt(p.getName(), p.getUniqueId(), event.getMessage());
            plugin.debug("owner-protect: blocked " + p.getName() + " -> " + event.getMessage());
        }
    }
}
