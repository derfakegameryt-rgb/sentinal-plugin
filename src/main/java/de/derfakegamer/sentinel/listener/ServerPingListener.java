package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

public final class ServerPingListener implements Listener {
    private final Sentinel plugin;
    public ServerPingListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (plugin.maintenance().isEnabled())
            event.motd(MiniMessage.miniMessage().deserialize(plugin.maintenance().motd()));
    }
}
