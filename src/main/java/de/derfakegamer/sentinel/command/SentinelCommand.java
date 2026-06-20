package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

public final class SentinelCommand implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;

    private static final java.util.Set<String> SUBCOMMANDS = java.util.Set.of(
        "ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn",
        "shadowmute","unshadowmute","history","sc","clearchat","maintenance",
        "broadcast","bc","restart","playtime","report","rules");

    /** Subcommands whose first argument is a player name (for arg-2 tab completion). */
    private static final java.util.Set<String> PLAYER_TARGETING = java.util.Set.of(
        "ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn",
        "shadowmute","unshadowmute","history","report");

    public SentinelCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            plugin.audit().record(sender.getName(), "RELOAD", null, "");
            sender.sendMessage(plugin.messages().prefixed("reloaded"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("update")) {
            plugin.updater().checkNow(sender);
            return true;
        }
        if (args.length >= 1 && SUBCOMMANDS.contains(args[0].toLowerCase())) {
            String rest = args.length > 1 ? " " + String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
            plugin.getServer().dispatchCommand(sender, args[0].toLowerCase() + rest);
            return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player mod)) {
            sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/sentinel reload"));
            return true;
        }
        if (args.length == 1) {
            org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(args[0]);
            de.derfakegamer.sentinel.gui.PlayerActionsGui.open(plugin, target, mod);
        } else {
            new de.derfakegamer.sentinel.gui.AdminPanelGui(plugin).open(mod);
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.hasPermission("sentinel.use")) return java.util.List.of();
        if (args.length == 1) {
            java.util.List<String> opts = new java.util.ArrayList<>(java.util.List.of("reload", "update"));
            opts.addAll(SUBCOMMANDS);
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) opts.add(p.getName());
            return filter(opts, args[0]);
        }
        if (args.length == 2 && PLAYER_TARGETING.contains(args[0].toLowerCase())) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        return java.util.List.of();
    }

    private static java.util.List<String> filter(java.util.List<String> options, String prefix) {
        String low = prefix.toLowerCase();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(low)) out.add(o);
        return out;
    }
}
