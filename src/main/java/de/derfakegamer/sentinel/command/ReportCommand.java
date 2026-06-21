package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Completions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class ReportCommand implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;

    public ReportCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player p)) { sender.sendMessage(plugin.messages().prefixed("report-usage")); return true; }
        if (args.length < 2) { sender.sendMessage(plugin.messages().prefixed("report-usage")); return true; }
        long cdMs = plugin.getConfig().getInt("report.cooldown-seconds", 30) * 1000L;
        long now = System.currentTimeMillis();
        if (!plugin.staffPerms().canUse(p, "sentinel.use")
                && !plugin.cooldowns().tryUse(p.getUniqueId(), "report", cdMs, now)) {
            long secs = (plugin.cooldowns().remainingMillis(p.getUniqueId(), "report", cdMs, now) + 999) / 1000;
            plugin.debug("report cooldown hit: " + p.getName() + " (" + secs + "s left)");
            p.sendMessage(plugin.messages().prefixed("cooldown", "seconds", String.valueOf(secs)));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        plugin.db().callbackOrError(p, plugin.reports().file(sender, target.getUniqueId(), args[0], reason),
            ok -> sender.sendMessage(Boolean.TRUE.equals(ok)
                ? plugin.messages().prefixed("report-filed")
                : plugin.messages().prefixed("report-self")));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return Completions.players(args[0]);
        if (args.length >= 2) return Completions.reasons(args[args.length - 1],
            plugin.getConfig().getStringList("reasons"));
        return List.of();
    }
}
