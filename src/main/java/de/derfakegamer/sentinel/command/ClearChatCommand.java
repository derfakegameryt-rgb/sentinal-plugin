package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ClearChatCommand implements CommandExecutor {
    private final Sentinel plugin;

    public ClearChatCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) continue; // keep staff's view; clear for everyone else
            for (int i = 0; i < 100; i++) p.sendMessage(Component.empty());
        }
        Bukkit.broadcast(plugin.messages().prefixed("chat-cleared", "player", sender.getName()));
        return true;
    }
}
