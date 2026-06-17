package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class AppealCommand implements CommandExecutor {
    private final Sentinel plugin;
    public AppealCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.messages().prefixed("players-only")); return true; }
        if (args.length == 0) { sender.sendMessage(plugin.messages().prefixed("appeal-usage")); return true; }
        long now = System.currentTimeMillis();
        Punishment mute = plugin.punishments().activeMute(p.getUniqueId(), now).join();
        if (mute == null) { p.sendMessage(plugin.messages().prefixed("appeal-nothing")); return true; }
        String text = String.join(" ", args);
        if (!plugin.appeals().submit(p.getUniqueId(), p.getName(), mute.id(), PunishmentType.MUTE, text, now)) {
            p.sendMessage(plugin.messages().prefixed("appeal-exists")); return true;
        }
        p.sendMessage(plugin.messages().prefixed("appeal-submitted"));
        // notify online staff
        plugin.getServer().getOnlinePlayers().stream()
            .filter(s -> s.hasPermission("sentinel.use"))
            .forEach(s -> s.sendMessage(plugin.messages().prefixed("appeal-alert", "player", p.getName())));
        return true;
    }
}
