package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.DurationParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class RestartCommand implements CommandExecutor {
    private final Sentinel plugin;
    public RestartCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!plugin.restart().cancel()) sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/restart <duration>|cancel"));
            return true;
        }
        if (args.length != 1) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/restart <duration>|cancel")); return true; }
        long ms;
        try { ms = DurationParser.parse(args[0]); }
        catch (IllegalArgumentException e) { sender.sendMessage(plugin.messages().prefixed("bad-duration")); return true; }
        int seconds = (int) Math.max(1, ms / 1000);
        plugin.restart().schedule(seconds);
        sender.sendMessage(plugin.messages().prefixed("restart-scheduled", "time", de.derfakegamer.sentinel.manager.RestartManager.human(seconds)));
        return true;
    }
}
