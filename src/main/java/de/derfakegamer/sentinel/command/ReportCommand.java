package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ReportCommand implements CommandExecutor {
    private final Sentinel plugin;

    public ReportCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(plugin.messages().prefixed("report-usage")); return true; }
        if (args.length < 2) { sender.sendMessage(plugin.messages().prefixed("report-usage")); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        boolean ok = plugin.reports().file(sender, target.getUniqueId(), args[0], reason);
        sender.sendMessage(ok ? plugin.messages().prefixed("report-filed")
                              : plugin.messages().prefixed("report-self"));
        return true;
    }
}
