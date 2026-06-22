package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Completions;
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
        "broadcast","bc","restart","playtime","report","rules","audit","stats");

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
        if (args.length == 1 && args[0].equalsIgnoreCase("admin")) {
            if (sender instanceof org.bukkit.entity.Player p) {
                new de.derfakegamer.sentinel.gui.AdminPanelGui(plugin).open(p);
            } else {
                sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/sentinel reload"));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("owner")) {
            if (plugin.owner().isOwner(sender) && sender instanceof org.bukkit.entity.Player p) {
                new de.derfakegamer.sentinel.gui.OwnerPanelGui(plugin).open(p);
            } else {
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "Unknown command. Type \"/help\" for help.", net.kyori.adventure.text.format.NamedTextColor.RED));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("audit")) {
            if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
            if (args.length >= 2) {
                String name = args[1];
                if (sender instanceof org.bukkit.entity.Player p) {
                    plugin.db().callbackOrError(p, plugin.audit().recentForTarget(name, 10), list -> printAudit(sender, list));
                } else {
                    plugin.db().callback(plugin.audit().recentForTarget(name, 10), list -> printAudit(sender, list),
                        error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to fetch audit log", error));
                }
            } else {
                if (sender instanceof org.bukkit.entity.Player p) {
                    plugin.db().callbackOrError(p, plugin.audit().recent(10, 0), list -> printAudit(sender, list));
                } else {
                    plugin.db().callback(plugin.audit().recent(10, 0), list -> printAudit(sender, list),
                        error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to fetch audit log", error));
                }
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
            if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
            long since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
            if (sender instanceof org.bukkit.entity.Player p) {
                plugin.db().callbackOrError(p, plugin.audit().topActors(since, 10), top -> {
                    sender.sendMessage(net.kyori.adventure.text.Component.text("Top staff (30d):", net.kyori.adventure.text.format.NamedTextColor.AQUA));
                    if (top != null) for (var a : top) sender.sendMessage(net.kyori.adventure.text.Component.text("  " + a.actor() + ": " + a.count(), net.kyori.adventure.text.format.NamedTextColor.GRAY));
                });
            } else {
                plugin.db().callback(plugin.audit().topActors(since, 10), top -> {
                    sender.sendMessage(net.kyori.adventure.text.Component.text("Top staff (30d):", net.kyori.adventure.text.format.NamedTextColor.AQUA));
                    if (top != null) for (var a : top) sender.sendMessage(net.kyori.adventure.text.Component.text("  " + a.actor() + ": " + a.count(), net.kyori.adventure.text.format.NamedTextColor.GRAY));
                }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to fetch stats", error));
            }
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
            mod.sendMessage(plugin.messages().prefixed("usage", "usage", "/sentinel admin"));
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.hasPermission("sentinel.use")) return java.util.List.of();
        if (args.length == 1) {
            java.util.List<String> opts = new java.util.ArrayList<>(java.util.List.of("reload", "update", "admin"));
            if (plugin.owner().isOwner(sender)) opts.add("owner");
            opts.addAll(SUBCOMMANDS);
            opts.addAll(org.bukkit.Bukkit.getOnlinePlayers().stream()
                .map(org.bukkit.entity.Player::getName).toList());
            return Completions.filter(args[0], opts);
        }
        if (args.length == 2 && PLAYER_TARGETING.contains(args[0].toLowerCase())) {
            return Completions.players(args[1]);
        }
        return java.util.List.of();
    }

    private void printAudit(org.bukkit.command.CommandSender sender, java.util.List<de.derfakegamer.sentinel.model.AuditEntry> list) {
        sender.sendMessage(net.kyori.adventure.text.Component.text("Recent audit:", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        if (list == null || list.isEmpty()) { sender.sendMessage(net.kyori.adventure.text.Component.text("  (none)", net.kyori.adventure.text.format.NamedTextColor.GRAY)); return; }
        for (var e : list) sender.sendMessage(net.kyori.adventure.text.Component.text(
            "  " + e.actor() + " " + e.action() + (e.target() == null ? "" : " " + e.target())
            + (e.details() == null || e.details().isBlank() ? "" : " — " + e.details()),
            net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }
}
