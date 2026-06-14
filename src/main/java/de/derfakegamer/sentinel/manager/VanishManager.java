package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishManager {
    private final Sentinel plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isVanished(UUID player) { return vanished.contains(player); }

    /** Flips vanish for a staff member and updates visibility. Returns new state (true = now vanished). */
    public boolean toggle(Player staff) {
        boolean nowVanished;
        if (vanished.add(staff.getUniqueId())) { hideFromNonOps(staff); nowVanished = true; }
        else { vanished.remove(staff.getUniqueId()); showToAll(staff); nowVanished = false; }
        return nowVanished;
    }

    /** When a player joins, hide every currently-vanished staff member from them (unless they are op). */
    public void applyOnJoin(Player joiner) {
        if (joiner.isOp()) return;
        for (UUID id : vanished) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null) joiner.hidePlayer(plugin, staff);
        }
    }

    private void hideFromNonOps(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers())
            if (!other.isOp() && !other.equals(staff)) other.hidePlayer(plugin, staff);
    }

    private void showToAll(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, staff);
    }
}
