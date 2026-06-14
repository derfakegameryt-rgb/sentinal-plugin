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
        Punishment ban = plugin.punishments().activeBan(event.getUniqueId(), now);
        if (ban == null) {
            String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : null;
            if (ip != null) ban = plugin.punishments().activeIpBan(ip, now);
        }
        if (ban != null) {
            Component screen = plugin.messages().plain("ban-screen", "reason", ban.reason());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
    }
}
