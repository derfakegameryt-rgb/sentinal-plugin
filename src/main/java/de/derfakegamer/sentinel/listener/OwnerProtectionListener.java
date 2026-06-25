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
import org.bukkit.event.server.ServerCommandEvent;

public final class OwnerProtectionListener implements Listener {
    private final Sentinel plugin;
    public OwnerProtectionListener(Sentinel plugin) { this.plugin = plugin; }

    /** Player-typed commands. */
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

    /**
     * Non-player commands: console, command blocks, and command minecarts. This closes the
     * {@code /execute as ...} and command-block bypasses — any such command that names the owner or
     * uses a target selector is suppressed exactly like a player's would be.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!plugin.ownerProtection().isEnabled()) return;
        if (OwnerProtectionManager.affectsOwner(event.getCommand(),
                plugin.ownerProtection().ownerName(), plugin.owner().uuid().toString())) {
            event.setCancelled(true);
            plugin.ownerProtection().recordAttempt(senderName(event.getSender()), null, event.getCommand());
            plugin.debug("owner-protect: blocked non-player command -> " + event.getCommand());
        }
    }

    private static String senderName(org.bukkit.command.CommandSender sender) {
        if (sender instanceof org.bukkit.command.BlockCommandSender) return "Command Block";
        return sender.getName();   // "CONSOLE" for the console
    }
}
