package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BroadcastCommand implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;
    public BroadcastCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 0) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/broadcast <message>")); return true; }
        String message = String.join(" ", args);
        // "[BROADCAST]" in red, the message itself in normal (white) text.
        net.kyori.adventure.text.Component line =
            net.kyori.adventure.text.Component.text("[BROADCAST] ", net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(MiniMessage.miniMessage().deserialize(message)
                    .colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE));
        Bukkit.broadcast(line);
        plugin.audit().record(sender.getName(), "BROADCAST", null, message);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        // /broadcast <free-text message> — no structured completion
        return List.of();
    }
}
