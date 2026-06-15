package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public final class OwnerManager {
    private final Sentinel plugin;
    public OwnerManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isOwner(CommandSender sender) {
        if (!(sender instanceof OfflinePlayer p)) return false;
        String configured = plugin.getConfig().getString("owner", "DerFakeGamer");
        if (configured == null || configured.isBlank()) return false;
        try {
            return p.getUniqueId().equals(UUID.fromString(configured)); // owner is a UUID
        } catch (IllegalArgumentException notUuid) {
            return p.getName() != null && p.getName().equalsIgnoreCase(configured); // owner is a name
        }
    }
}
