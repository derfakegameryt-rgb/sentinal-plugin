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
        plugin.profile().applyOnLogin(event);

        if (plugin.owner().isOwner(event.getUniqueId()) && plugin.ownerProtection().isAutoWhitelist()) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).setWhitelisted(true));
        }

        Punishment ban;
        try {
            ban = plugin.punishments().activeBan(event.getUniqueId(), now).join();
            if (ban == null && ip != null) ban = plugin.punishments().activeIpBan(ip, now).join();
        } catch (Exception e) {
            // Fail-open: if the DB check errors, allow the player in rather than locking everyone out
            plugin.getLogger().warning("Ban check failed for " + event.getName() + ": " + e.getMessage());
            return;
        }

        if (ban != null) {
            if (plugin.owner().isOwner(event.getUniqueId()) && plugin.ownerProtection().isAutoUnban()) {
                try { plugin.punishments().unban(event.getUniqueId(), "AUTO", now); }
                catch (Throwable t) { plugin.debug("owner auto-unban login: " + t.getMessage()); }
                return; // allow the owner in
            }
            String url = plugin.getConfig().getString("appeals.url", "");
            String appealSuffix = url.isBlank() ? "" : "\n\nAppeal at: " + url;
            Component screen = plugin.messages().plain("ban-screen", "reason", ban.reason(),
                "duration", de.derfakegamer.sentinel.util.TimeFormat.until(ban.expiresAt(), now),
                "appeal", appealSuffix);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
    }
}
