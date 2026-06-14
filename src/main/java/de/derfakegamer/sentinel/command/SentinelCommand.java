package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class SentinelCommand implements CommandExecutor {
    private final Sentinel plugin;

    public SentinelCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            sender.sendMessage(plugin.messages().prefixed("reloaded"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("update")) {
            plugin.updater().checkNow(sender);
            return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player mod)) {
            sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/sentinel reload"));
            return true;
        }
        if (args.length == 1) {
            org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(args[0]);
            new de.derfakegamer.sentinel.gui.PlayerActionsGui(plugin, target).open(mod);
        } else {
            new de.derfakegamer.sentinel.gui.PlayersGui(plugin, 0).open(mod);
        }
        return true;
    }
}
