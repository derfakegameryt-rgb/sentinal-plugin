package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Completions;
import de.derfakegamer.sentinel.util.DurationParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class RestartCommand implements CommandExecutor, TabCompleter {
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

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            // Merge duration suggestions with "cancel"
            List<String> durations = Completions.durations(args[0]);
            List<String> cancel = Completions.of(args[0], "cancel");
            if (cancel.isEmpty()) return durations;
            if (durations.isEmpty()) return cancel;
            java.util.List<String> merged = new java.util.ArrayList<>(durations);
            merged.addAll(cancel);
            java.util.Collections.sort(merged);
            return merged;
        }
        return List.of();
    }
}
