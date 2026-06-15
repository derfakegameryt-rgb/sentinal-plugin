package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class MaintenanceCommand implements CommandExecutor {
    private final Sentinel plugin;
    public MaintenanceCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        boolean on = args.length == 0 ? !plugin.maintenance().isEnabled() : args[0].equalsIgnoreCase("on");
        plugin.maintenance().setEnabled(on);
        if (on) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (!p.isOp() && !plugin.owner().isOwner(p)) p.kick(Component.text(plugin.maintenance().kickMessage()));
        }
        sender.sendMessage(plugin.messages().prefixed(on ? "maintenance-on" : "maintenance-off"));
        return true;
    }
}
