package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.PunishmentManager;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PunishmentCommands implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;

    public PunishmentCommands(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        String cmd = command.getName().toLowerCase();
        PunishmentManager pm = plugin.punishments();
        UUID issuerId = (sender instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        String issuerName = sender.getName();
        long now = System.currentTimeMillis();

        switch (cmd) {
            case "ban", "ipban", "mute" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                if (cmd.equals("ipban") && t.ip == null) {
                    sender.sendMessage(plugin.messages().prefixed("ipban-requires-online"));
                    return true;
                }
                String reason = join(args, 1);
                de.derfakegamer.sentinel.model.PunishmentType type = switch (cmd) {
                    case "ban" -> de.derfakegamer.sentinel.model.PunishmentType.BAN;
                    case "ipban" -> de.derfakegamer.sentinel.model.PunishmentType.IPBAN;
                    default -> de.derfakegamer.sentinel.model.PunishmentType.MUTE;
                };
                boolean applied = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason);
                if (!applied) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
            }
            case "tempban", "tempmute" -> {
                if (args.length < 3) return usage(sender, "/" + cmd + " <player> <duration> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                long expiresAt;
                try { expiresAt = now + DurationParser.parse(args[1]); }
                catch (IllegalArgumentException e) { return usage(sender, "/" + cmd + " <player> 1d2h <reason>"); }
                String reason = join(args, 2);
                de.derfakegamer.sentinel.model.PunishmentType type = cmd.equals("tempban")
                    ? de.derfakegamer.sentinel.model.PunishmentType.BAN
                    : de.derfakegamer.sentinel.model.PunishmentType.MUTE;
                boolean applied = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, expiresAt, reason);
                if (!applied) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
            }
            case "kick", "warn" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                String reason = join(args, 1);
                de.derfakegamer.sentinel.model.PunishmentType type = cmd.equals("kick")
                    ? de.derfakegamer.sentinel.model.PunishmentType.KICK
                    : de.derfakegamer.sentinel.model.PunishmentType.WARN;
                boolean applied = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason);
                if (!applied) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
            }
            case "unban", "unmute" -> {
                if (args.length < 1) return usage(sender, "/" + cmd + " <player>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                boolean ok = cmd.equals("unban")
                    ? plugin.moderation().removeBan(issuerId, issuerName, t.id, t.name)
                    : plugin.moderation().removeMute(issuerId, issuerName, t.id, t.name);
                if (!ok) sender.sendMessage(plugin.messages().prefixed(cmd.equals("unban") ? "not-banned" : "not-muted"));
                return true;
            }
            case "history" -> {
                if (args.length < 1) return usage(sender, "/history <player>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                var entries = pm.history(t.id);
                if (entries.isEmpty()) {
                    sender.sendMessage(plugin.messages().prefixed("history-empty", "player", t.name));
                    return true;
                }
                sender.sendMessage(plugin.messages().prefixed("history-header", "player", t.name));
                for (Punishment p : entries) {
                    String date = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(java.time.Instant.ofEpochMilli(p.createdAt()));
                    sender.sendMessage(plugin.messages().prefixed("history-entry",
                        "type", p.type().name(),
                        "issuer", p.issuerName(),
                        "reason", p.reason(),
                        "date", date,
                        "status", p.active() ? "(active)" : ""));
                }
            }
        }
        return true;
    }

    private static final java.util.List<String> DURATIONS =
        java.util.List.of("30m", "1h", "6h", "12h", "1d", "3d", "7d", "30d");

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.isOp()) return java.util.List.of();
        boolean temp = command.getName().equalsIgnoreCase("tempban")
            || command.getName().equalsIgnoreCase("tempmute");
        if (args.length == 1) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[0]);
        }
        if (args.length == 2 && temp) return filter(DURATIONS, args[1]);
        return java.util.List.of();
    }

    private static java.util.List<String> filter(java.util.List<String> options, String prefix) {
        String low = prefix.toLowerCase();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(low)) out.add(o);
        return out;
    }

    private record Target(UUID id, String name, String ip) {}

    private Target resolve(CommandSender sender, String name) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.getUniqueId() == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return null; }
        String ip = (op.getPlayer() != null && op.getPlayer().getAddress() != null)
            ? op.getPlayer().getAddress().getAddress().getHostAddress() : null;
        if (ip == null) {
            var rec = plugin.players().byUuid(op.getUniqueId());
            if (rec != null) ip = rec.lastIp();
        }
        return new Target(op.getUniqueId(), name, ip);
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(plugin.messages().prefixed("usage", "usage", usage));
        return true;
    }

    private String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
