package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ActivityListener implements Listener {
    private final Sentinel plugin;
    public ActivityListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler public void onJoin(PlayerJoinEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { plugin.afk().forget(e.getPlayer().getUniqueId()); }
    @EventHandler public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()))
            plugin.afk().bump(e.getPlayer().getUniqueId());
    }
}
