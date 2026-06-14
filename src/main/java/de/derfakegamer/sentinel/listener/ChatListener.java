package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class ChatListener implements Listener {
    private final Sentinel plugin;

    public ChatListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Punishment mute = plugin.punishments()
            .activeMute(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (mute != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().prefixed("you-are-muted", "reason", mute.reason()));
        }
    }
}
