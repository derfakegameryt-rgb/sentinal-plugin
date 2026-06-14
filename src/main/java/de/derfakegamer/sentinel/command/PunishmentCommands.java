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
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PunishmentCommands implements CommandExecutor {
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
                var result = switch (cmd) {
                    case "ban" -> pm.ban(t.id, t.name, issuerId, issuerName, reason, 0);
                    case "ipban" -> pm.ipBan(t.id, t.name, t.ip, issuerId, issuerName, reason, 0);
                    default -> pm.mute(t.id, t.name, issuerId, issuerName, reason, 0);
                };
                if (!result.isSuccess()) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
                String key = cmd.equals("mute") ? "muted" : "banned";
                announce(key, t.name, reason);
                if (!cmd.equals("mute")) kickIfOnline(t.id, "ban-screen", reason);
            }
            case "tempban", "tempmute" -> {
                if (args.length < 3) return usage(sender, "/" + cmd + " <player> <duration> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                long expiresAt;
                try { expiresAt = now + DurationParser.parse(args[1]); }
                catch (IllegalArgumentException e) { return usage(sender, "/" + cmd + " <player> 1d2h <reason>"); }
                String reason = join(args, 2);
                var result = cmd.equals("tempban")
                    ? pm.ban(t.id, t.name, issuerId, issuerName, reason, expiresAt)
                    : pm.mute(t.id, t.name, issuerId, issuerName, reason, expiresAt);
                if (!result.isSuccess()) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
                announce(cmd.equals("tempban") ? "banned" : "muted", t.name, reason);
                if (cmd.equals("tempban")) kickIfOnline(t.id, "ban-screen", reason);
            }
            case "kick", "warn" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                String reason = join(args, 1);
                var result = cmd.equals("kick")
                    ? pm.kick(t.id, t.name, issuerId, issuerName, reason)
                    : pm.warn(t.id, t.name, issuerId, issuerName, reason);
                if (!result.isSuccess()) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
                announce(cmd.equals("kick") ? "kicked" : "warned", t.name, reason);
                if (cmd.equals("kick")) kickIfOnline(t.id, "ban-screen", reason);
            }
            case "unban", "unmute" -> {
                if (args.length < 1) return usage(sender, "/" + cmd + " <player>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                boolean ok = cmd.equals("unban") ? pm.unban(t.id, issuerName, now) : pm.unmute(t.id, issuerName, now);
                if (!ok) {
                    sender.sendMessage(plugin.messages().prefixed(cmd.equals("unban") ? "not-banned" : "not-muted"));
                    return true;
                }
                announce(cmd.equals("unban") ? "unbanned" : "unmuted", t.name, "");
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

    private record Target(UUID id, String name, String ip) {}

    private Target resolve(CommandSender sender, String name) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.getUniqueId() == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return null; }
        String ip = (op.getPlayer() != null && op.getPlayer().getAddress() != null)
            ? op.getPlayer().getAddress().getAddress().getHostAddress() : null;
        return new Target(op.getUniqueId(), name, ip);
    }

    private void kickIfOnline(UUID id, String key, String reason) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.kick(plugin.messages().plain(key, "reason", reason));
    }

    private void announce(String key, String player, String reason) {
        Bukkit.broadcast(plugin.messages().prefixed(key, "player", player, "reason", reason));
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(plugin.messages().prefixed("usage", "usage", usage));
        return true;
    }

    private String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
