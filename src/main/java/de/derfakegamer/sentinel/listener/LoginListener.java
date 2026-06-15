package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class LoginListener implements Listener {
    private final Sentinel plugin;

    public LoginListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        long now = System.currentTimeMillis();
        String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : null;

        // Record the login first (also for banned players — used for alt detection),
        // so this never depends on the ban-evaluation control flow below.
        plugin.players().record(event.getUniqueId(), event.getName(), ip);

        if (plugin.maintenance().isEnabled()
                && !org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).isOp()
                && !plugin.owner().isOwner(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                net.kyori.adventure.text.Component.text(plugin.maintenance().kickMessage()));
            return;
        }

        Punishment ban = plugin.punishments().activeBan(event.getUniqueId(), now);
        if (ban == null && ip != null) ban = plugin.punishments().activeIpBan(ip, now);
        if (ban != null) {
            Component screen = plugin.messages().plain("ban-screen", "reason", ban.reason(),
                "duration", de.derfakegamer.sentinel.util.TimeFormat.until(ban.expiresAt(), now));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
    }
}
