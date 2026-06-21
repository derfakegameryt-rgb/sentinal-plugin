package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class AppealCommand implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;
    public AppealCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.messages().prefixed("players-only")); return true; }
        if (args.length == 0) { sender.sendMessage(plugin.messages().prefixed("appeal-usage")); return true; }
        long now = System.currentTimeMillis();
        long cdMs = plugin.getConfig().getInt("appeals.cooldown-seconds", 60) * 1000L;
        if (!plugin.staffPerms().canUse(p, "sentinel.use")
                && !plugin.cooldowns().tryUse(p.getUniqueId(), "appeal", cdMs, now)) {
            long secs = (plugin.cooldowns().remainingMillis(p.getUniqueId(), "appeal", cdMs, now) + 999) / 1000;
            plugin.debug("appeal cooldown hit: " + p.getName() + " (" + secs + "s left)");
            p.sendMessage(plugin.messages().prefixed("cooldown", "seconds", String.valueOf(secs)));
            return true;
        }
        String text = String.join(" ", args);
        plugin.db().callbackOrError(p, plugin.punishments().activeMute(p.getUniqueId(), now), mute -> {
            if (mute == null) { p.sendMessage(plugin.messages().prefixed("appeal-nothing")); return; }
            // submit() atomically re-checks hasOpen + inserts on the DB thread
            plugin.db().callbackOrError(p, plugin.appeals().submit(p.getUniqueId(), p.getName(), mute.id(), PunishmentType.MUTE, text, now), submitted -> {
                if (!submitted) { p.sendMessage(plugin.messages().prefixed("appeal-exists")); return; }
                p.sendMessage(plugin.messages().prefixed("appeal-submitted"));
                // notify online staff
                plugin.getServer().getOnlinePlayers().stream()
                    .filter(s -> s.hasPermission("sentinel.use"))
                    .forEach(s -> s.sendMessage(plugin.messages().prefixed("appeal-alert", "player", p.getName())));
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        // /appeal <reason text> — no structured completion useful here
        return List.of();
    }
}
