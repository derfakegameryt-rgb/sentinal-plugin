package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ActivityListener implements Listener {
    private final Sentinel plugin;
    public ActivityListener(Sentinel plugin) { this.plugin = plugin; }

    /** Records activity; if the player was flagged AFK, announces their return. */
    private void active(Player p) {
        if (plugin.afk().bump(p.getUniqueId()))
            plugin.getServer().broadcast(plugin.messages().plain("afk-back", "player", p.getName()));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { plugin.afk().forget(e.getPlayer().getUniqueId()); }
    @EventHandler public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) { active(e.getPlayer()); }
    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) { active(e.getPlayer()); }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()))
            active(e.getPlayer());
    }
}
