package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.gui.OrbitalCodeGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class OrbitalStrikeCommand implements CommandExecutor {
    private final Sentinel plugin;

    public OrbitalStrikeCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (sender instanceof Player p) new OrbitalCodeGui(plugin).open(p);
        return true;
    }
}
