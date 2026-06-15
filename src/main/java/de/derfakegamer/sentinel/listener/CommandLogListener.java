package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class CommandLogListener implements Listener {
    private final Sentinel plugin;
    public CommandLogListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        plugin.chatLog().logCommand(event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getMessage());
    }
}
