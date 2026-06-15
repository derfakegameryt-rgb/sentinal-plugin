package de.derfakegamer.sentinel.manager;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public final class OwnerManager {
    // The single owner. Hard-coded on purpose (not configurable, not stored anywhere visible).
    private static final UUID OWNER = UUID.fromString("6500ca9a-a10c-40a5-b985-a56ca9ff1d1e");

    public OwnerManager() {}

    public boolean isOwner(CommandSender sender) {
        return sender instanceof OfflinePlayer p && p.getUniqueId().equals(OWNER);
    }
}
