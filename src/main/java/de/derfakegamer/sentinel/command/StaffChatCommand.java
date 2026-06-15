package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class StaffChatCommand implements CommandExecutor {
    private final Sentinel plugin;

    public StaffChatCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 0) {
            if (!(sender instanceof Player p)) return true;
            boolean on = plugin.staffChat().toggle(p.getUniqueId());
            sender.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));
            return true;
        }
        plugin.staffChat().send(sender.getName(), String.join(" ", args));
        return true;
    }
}
