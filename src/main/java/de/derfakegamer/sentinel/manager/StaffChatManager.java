package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffChatManager {
    private final Sentinel plugin;
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();

    public StaffChatManager(Sentinel plugin) { this.plugin = plugin; }

    /** Flips staff-chat mode for a player. Returns the new state (true = now on). */
    public boolean toggle(UUID player) {
        if (toggled.add(player)) return true;
        toggled.remove(player);
        return false;
    }

    public boolean isToggled(UUID player) { return toggled.contains(player); }

    /** Clears staff-chat mode for a player (called on disconnect to avoid stale state). */
    public void clear(UUID player) { toggled.remove(player); }

    public void send(String senderName, String message) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp())
                staff.sendMessage(plugin.messages().plain("staffchat-prefix", "player", senderName, "message", message));
        }
    }
}
