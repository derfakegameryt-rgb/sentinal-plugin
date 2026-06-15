package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PlaytimeCommand implements CommandExecutor {
    private final Sentinel plugin;
    public PlaytimeCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        OfflinePlayer target = args.length >= 1 ? Bukkit.getOfflinePlayer(args[0])
            : (sender instanceof Player p ? p : null);
        if (target == null) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/playtime <player>")); return true; }
        long ms = plugin.players().playtime(target.getUniqueId());
        sender.sendMessage(plugin.messages().prefixed("playtime", "player",
            args.length >= 1 ? args[0] : sender.getName(), "time", format(ms)));
        return true;
    }

    static String format(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h + "h " + m + "m";
    }
}
