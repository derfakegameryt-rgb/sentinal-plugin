package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * On join, warns online staff (permission {@code sentinel.ban}) when the joining player shares an IP
 * with a currently-banned account — the classic ban-evasion-via-alt signal. Opt out with
 * {@code alt-alerts: false} in config.yml. All lookups run off the main thread.
 */
public final class AltAlertListener implements Listener {
    private final Sentinel plugin;

    public AltAlertListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("alt-alerts", true)) return;
        Player joiner = event.getPlayer();
        UUID id = joiner.getUniqueId();
        long now = System.currentTimeMillis();
        // same-IP alts -> which of them are banned -> their names; entirely on the DB thread.
        CompletableFuture<List<String>> bannedAlts = plugin.players().alts(id).thenCompose(alts -> {
            if (alts.isEmpty()) return CompletableFuture.completedFuture(List.<String>of());
            Map<UUID, String> names = alts.stream()
                .collect(Collectors.toMap(PlayerRecord::uuid, PlayerRecord::name, (a, b) -> a));
            return plugin.punishments().activeBansAmong(names.keySet(), now)
                .thenApply(ids -> ids.stream().map(names::get)
                    .filter(java.util.Objects::nonNull).sorted().toList());
        });
        plugin.db().callback(bannedAlts,
            names -> { if (!names.isEmpty()) alert(joiner, names); },
            err -> plugin.getLogger().fine("alt-alert lookup failed: " + err.getMessage()));
    }

    // Delivered on the main thread by plugin.db().callback.
    private void alert(Player joiner, List<String> bannedAlts) {
        net.kyori.adventure.text.Component msg = plugin.messages().prefixed("alt-alert",
            "player", joiner.getName(), "alts", String.join(", ", bannedAlts));
        for (Player o : Bukkit.getOnlinePlayers())
            if (o.hasPermission("sentinel.ban")) o.sendMessage(msg);
    }
}
