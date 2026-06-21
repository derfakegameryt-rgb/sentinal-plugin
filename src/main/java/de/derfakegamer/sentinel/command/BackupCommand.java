package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BackupCommand implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;
    public BackupCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        plugin.backup().backup(sender, System.currentTimeMillis());
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        // /backup takes no arguments
        return List.of();
    }
}
