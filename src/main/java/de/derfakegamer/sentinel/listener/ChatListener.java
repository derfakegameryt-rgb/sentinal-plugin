package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.function.Consumer;

public final class ChatListener implements Listener {
    private final Sentinel plugin;

    public ChatListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        if (plugin.chatInput().has(id)) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Consumer<String> callback = plugin.chatInput().consume(id);
            if (callback != null && !text.equalsIgnoreCase("cancel")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(text));
            }
            return;
        }

        Punishment mute = plugin.punishments().activeMute(id, System.currentTimeMillis());
        if (mute != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().prefixed("you-are-muted", "reason", mute.reason()));
        }
    }

    /** Drop any pending GUI chat-input when the moderator disconnects, so it can't leak or misfire. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.chatInput().cancel(event.getPlayer().getUniqueId());
    }
}
