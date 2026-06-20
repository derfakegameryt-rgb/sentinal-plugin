package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class BroadcastCommand implements CommandExecutor {
    private final Sentinel plugin;
    public BroadcastCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 0) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/broadcast <message>")); return true; }
        String prefix = plugin.getConfig().getString("announcements.prefix", "");
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(prefix + String.join(" ", args)));
        plugin.audit().record(sender.getName(), "BROADCAST", null, String.join(" ", args));
        return true;
    }
}
