package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.command.CommandSender;

/** Maps each punishment action to a permission node and gates who may perform it. */
public final class StaffPermissions {
    private final Sentinel plugin;
    public StaffPermissions(Sentinel plugin) { this.plugin = plugin; }

    /** The permission node required to issue this punishment type. */
    public static String node(PunishmentType type) {
        return switch (type) {
            case BAN -> "sentinel.ban";
            case IPBAN -> "sentinel.ipban";
            case MUTE -> "sentinel.mute";
            case KICK -> "sentinel.kick";
            case WARN -> "sentinel.warn";
            case SHADOWMUTE -> "sentinel.shadowmute";
        };
    }

    /** True if the sender may issue this punishment. The owner always may. */
    public boolean canPerform(CommandSender sender, PunishmentType type) {
        return plugin.owner().isOwner(sender) || sender.hasPermission(node(type));
    }

    /** True if the sender may use the given action node (e.g. "sentinel.unban"). The owner always may. */
    public boolean canUse(CommandSender sender, String permission) {
        return plugin.owner().isOwner(sender) || sender.hasPermission(permission);
    }
}
