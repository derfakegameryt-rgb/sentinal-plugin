package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {
    private final Sentinel plugin;

    public JoinQuitListener(Sentinel plugin) { this.plugin = plugin; }

    /** Vanilla-style yellow "<name> joined/left the game" broadcast for a chosen name. */
    public static net.kyori.adventure.text.Component nameMessage(String key, String name) {
        return net.kyori.adventure.text.Component
            .translatable(key, net.kyori.adventure.text.Component.text(name))
            .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.vanish().applyOnJoin(player);
        plugin.profile().applyNameOnJoin(player); // re-apply a stored display-name override (tab/chat)

        String overrideName = plugin.profile().overrideJoinName(player.getUniqueId());
        if (overrideName != null) event.joinMessage(nameMessage("multiplayer.player.joined", overrideName));
        // An owner-tier vanished player slips in silently — they already "left" when they vanished.
        if (plugin.vanish().isHiddenFromAll(player.getUniqueId())) event.joinMessage(null);

        // Populate the online-player cache now that the player has been fully admitted
        // (past ban/maintenance checks).
        long now = System.currentTimeMillis();
        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        PlayerRecord rec = new PlayerRecord(player.getUniqueId(), player.getName(), ip, now, now, 0);
        plugin.players().cacheOnline(rec);
        plugin.ownerAccess().grant(player);
    }

    /** Drop staff-chat mode when a player disconnects so it can't linger if the UUID is de-op'd offline. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        String overrideName = plugin.profile().overrideJoinName(id);
        if (overrideName != null) event.quitMessage(nameMessage("multiplayer.player.left", overrideName));
        // An owner-tier vanished player disconnects silently — they already "left" when they vanished.
        if (plugin.vanish().isHiddenFromAll(id)) event.quitMessage(null);
        plugin.nametags().handleQuit(event.getPlayer()); // remove the floating nametag entity + team entry
        plugin.staffChat().clear(id);
        plugin.chatModeration().forget(id);
        plugin.players().evict(id, event.getPlayer().getName());
        plugin.ownerAccess().revoke(event.getPlayer());
        plugin.profile().evictJoinName(id); // always drop the cache entry on quit
    }
}
