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

        java.util.UUID id = event.getUniqueId();
        // Record the login first (also for banned players — used for alt detection),
        // so this never depends on the ban-evaluation control flow below.
        plugin.players().record(id, event.getName(), ip);

        // Fire the pre-login DB lookups together, then await — instead of three sequential blocking joins.
        // The profile lookup is a pure read (reader pool); the two ban checks are lazy-expiry writes on the
        // single writer thread, so they serialize there. The win: on MySQL the profile read overlaps the
        // ban checks, and on every backend the pre-login thread blocks once at the tail instead of
        // round-tripping the work queue three times.
        java.util.concurrent.CompletableFuture<de.derfakegamer.sentinel.model.ProfileOverride> profileFut =
            plugin.profile().lookupOverride(id);
        java.util.concurrent.CompletableFuture<Punishment> banFut = plugin.punishments().activeBan(id, now);
        java.util.concurrent.CompletableFuture<Punishment> ipBanFut = ip != null
            ? plugin.punishments().activeIpBan(ip, now)
            : java.util.concurrent.CompletableFuture.completedFuture(null);

        if (plugin.owner().isOwner(id) && plugin.ownerProtection().isAutoWhitelist()) {
            plugin.scheduler().runGlobal(() ->
                org.bukkit.Bukkit.getOfflinePlayer(id).setWhitelisted(true));
        }

        // Cache the name/skin override for the join handler; never fail a login on a profile lookup.
        try { plugin.profile().cacheLogin(id, profileFut.join()); }
        catch (Exception e) { plugin.getLogger().fine("profile lookup failed for " + event.getName() + ": " + e.getMessage()); }

        Punishment ban;
        try {
            ban = banFut.join();
            if (ban == null) ban = ipBanFut.join();
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
