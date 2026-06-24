package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.PunishmentManager;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.util.Completions;
import de.derfakegamer.sentinel.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PunishmentCommands implements CommandExecutor, TabCompleter {
    private final Sentinel plugin;

    public PunishmentCommands(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sentinel.use")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        String cmd = command.getName().toLowerCase();
        PunishmentManager pm = plugin.punishments();
        UUID issuerId = (sender instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        String issuerName = sender.getName();
        long now = System.currentTimeMillis();

        switch (cmd) {
            case "ban", "ipban", "mute" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                String reason = join(args, 1);
                de.derfakegamer.sentinel.model.PunishmentType type = switch (cmd) {
                    case "ban" -> de.derfakegamer.sentinel.model.PunishmentType.BAN;
                    case "ipban" -> de.derfakegamer.sentinel.model.PunishmentType.IPBAN;
                    default -> de.derfakegamer.sentinel.model.PunishmentType.MUTE;
                };
                if (!plugin.staffPerms().canPerform(sender, type)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        if (cmd.equals("ipban") && t.ip == null) { sender.sendMessage(plugin.messages().prefixed("ipban-requires-online")); return; }
                        plugin.db().callbackOrError(sp, plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason),
                            applied -> { if (applied == null || !applied) sender.sendMessage(plugin.messages().prefixed("exempt")); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        if (cmd.equals("ipban") && t.ip == null) { sender.sendMessage(plugin.messages().prefixed("ipban-requires-online")); return; }
                        plugin.db().callback(plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason),
                            applied -> { if (applied == null || !applied) sender.sendMessage(plugin.messages().prefixed("exempt")); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to apply punishment", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
            case "tempban", "tempmute" -> {
                if (args.length < 3) return usage(sender, "/" + cmd + " <player> <duration> <reason>");
                long expiresAt;
                try { expiresAt = now + DurationParser.parse(args[1]); }
                catch (IllegalArgumentException e) { return usage(sender, "/" + cmd + " <player> 1d2h <reason>"); }
                String reason = join(args, 2);
                de.derfakegamer.sentinel.model.PunishmentType type = cmd.equals("tempban")
                    ? de.derfakegamer.sentinel.model.PunishmentType.BAN
                    : de.derfakegamer.sentinel.model.PunishmentType.MUTE;
                if (!plugin.staffPerms().canPerform(sender, type)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                final long expiry = expiresAt;
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callbackOrError(sp, plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, expiry, reason),
                            applied -> { if (applied == null || !applied) sender.sendMessage(plugin.messages().prefixed("exempt")); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callback(plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, expiry, reason),
                            applied -> { if (applied == null || !applied) sender.sendMessage(plugin.messages().prefixed("exempt")); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to apply punishment", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
            case "kick", "warn" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                String reason = join(args, 1);
                de.derfakegamer.sentinel.model.PunishmentType type = cmd.equals("kick")
                    ? de.derfakegamer.sentinel.model.PunishmentType.KICK
                    : de.derfakegamer.sentinel.model.PunishmentType.WARN;
                if (!plugin.staffPerms().canPerform(sender, type)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callbackOrError(sp, plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason),
                            applied -> { if (applied == null || !applied) sender.sendMessage(plugin.messages().prefixed("exempt")); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callback(plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason),
                            applied -> { if (applied == null || !applied) sender.sendMessage(plugin.messages().prefixed("exempt")); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to apply punishment", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
            case "shadowmute" -> {
                if (args.length < 2) return usage(sender, "/shadowmute <player> <reason>");
                if (!plugin.staffPerms().canPerform(sender, de.derfakegamer.sentinel.model.PunishmentType.SHADOWMUTE)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                String reason = join(args, 1);
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callbackOrError(sp, plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip,
                            de.derfakegamer.sentinel.model.PunishmentType.SHADOWMUTE, 0, reason),
                            ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed("exempt")); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callback(plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip,
                            de.derfakegamer.sentinel.model.PunishmentType.SHADOWMUTE, 0, reason),
                            ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed("exempt")); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to apply shadowmute", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
            case "unshadowmute" -> {
                if (args.length < 1) return usage(sender, "/unshadowmute <player>");
                if (!plugin.staffPerms().canUse(sender, "sentinel.shadowmute")) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callbackOrError(sp, plugin.moderation().removeShadowMute(issuerId, issuerName, t.id, t.name),
                            ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed("not-muted")); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callback(plugin.moderation().removeShadowMute(issuerId, issuerName, t.id, t.name),
                            ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed("not-muted")); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to remove shadowmute", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
            case "unban", "unmute", "ipunban" -> {
                if (args.length < 1) return usage(sender, "/" + cmd + " <player>");
                String unNode = cmd.equals("unmute") ? "sentinel.unmute" : "sentinel.unban";
                if (!plugin.staffPerms().canUse(sender, unNode)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                String notKey = switch (cmd) { case "unban" -> "not-banned"; case "ipunban" -> "not-ip-banned"; default -> "not-muted"; };
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        CompletableFuture<Boolean> fut = switch (cmd) {
                            case "unban" -> plugin.moderation().removeBan(issuerId, issuerName, t.id, t.name);
                            case "ipunban" -> plugin.moderation().removeIpBan(issuerId, issuerName, t.id, t.name);
                            default -> plugin.moderation().removeMute(issuerId, issuerName, t.id, t.name);
                        };
                        plugin.db().callbackOrError(sp, fut, ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed(notKey)); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        CompletableFuture<Boolean> fut = switch (cmd) {
                            case "unban" -> plugin.moderation().removeBan(issuerId, issuerName, t.id, t.name);
                            case "ipunban" -> plugin.moderation().removeIpBan(issuerId, issuerName, t.id, t.name);
                            default -> plugin.moderation().removeMute(issuerId, issuerName, t.id, t.name);
                        };
                        plugin.db().callback(fut, ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed(notKey)); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to remove punishment", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
            case "history" -> {
                if (args.length < 1) return usage(sender, "/history <player>");
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callbackOrError(sp, pm.history(t.id), entries -> {
                            if (entries == null || entries.isEmpty()) {
                                sender.sendMessage(plugin.messages().prefixed("history-empty", "player", t.name));
                                return;
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
                        });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        plugin.db().callback(pm.history(t.id), entries -> {
                            if (entries == null || entries.isEmpty()) {
                                sender.sendMessage(plugin.messages().prefixed("history-empty", "player", t.name));
                                return;
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
                        }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to fetch history", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.hasPermission("sentinel.use")) return java.util.List.of();
        boolean temp = command.getName().equalsIgnoreCase("tempban")
            || command.getName().equalsIgnoreCase("tempmute");
        if (args.length == 1) return Completions.players(args[0]);
        if (args.length == 2 && temp) return Completions.tempDurations(args[1]);
        if (args.length >= 2 && !temp) return Completions.reasons(args[args.length - 1],
            plugin.getConfig().getStringList("reasons"));
        return java.util.List.of();
    }

    private record Target(UUID id, String name, String ip) {}

    /**
     * Resolves a player name to a Target. If online, resolves immediately (no DB hit).
     * For offline players, queries the DB on the executor thread and returns null if not found.
     */
    private CompletableFuture<Target> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            String ip = online.getAddress() != null ? online.getAddress().getAddress().getHostAddress() : null;
            return CompletableFuture.completedFuture(new Target(online.getUniqueId(), online.getName(), ip));
        }
        return plugin.players().byName(name).thenApply(rec ->
            rec == null ? null : new Target(rec.uuid(), rec.name(), rec.lastIp()));
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(plugin.messages().prefixed("usage", "usage", usage));
        return true;
    }

    private String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
