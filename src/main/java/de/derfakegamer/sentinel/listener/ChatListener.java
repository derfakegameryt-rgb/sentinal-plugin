package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.ChatModeration;
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

        plugin.chatLog().logChat(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()));

        if (plugin.chatInput().has(id)) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Consumer<String> callback = plugin.chatInput().consume(id);
            if (callback != null && !text.equalsIgnoreCase("cancel")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(text));
            }
            return;
        }

        if (plugin.staffChat().isToggled(id)) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            plugin.staffChat().send(event.getPlayer().getName(), text);
            return;
        }

        de.derfakegamer.sentinel.model.Punishment shadow =
            plugin.punishments().activeShadowMute(id, System.currentTimeMillis());
        if (shadow != null) {
            // Restrict the audience to the sender only: they see their own message normally,
            // everyone else sees nothing. Do NOT cancel — the message must still render to them.
            event.viewers().removeIf(a -> !(a instanceof org.bukkit.entity.Player p) || !p.getUniqueId().equals(id));
            return;
        }

        long now = System.currentTimeMillis();
        Punishment mute = plugin.punishments().activeMute(id, now);
        if (mute != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().prefixed("you-are-muted",
                "reason", mute.reason(), "duration", de.derfakegamer.sentinel.util.TimeFormat.until(mute.expiresAt(), now)));
        }

        if (!event.isCancelled() && !event.getPlayer().isOp()) {
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            ChatModeration.Outcome outcome = plugin.chatModeration().evaluate(id, text, System.currentTimeMillis());
            switch (outcome.action()) {
                case BLOCK -> {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(plugin.messages().prefixed(outcome.messageKey()));
                }
                case CENSOR -> event.message(net.kyori.adventure.text.Component.text(outcome.censored()));
                case ALLOW -> {}
            }
        }
    }

    /** Drop any pending GUI chat-input when the moderator disconnects, so it can't leak or misfire. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.chatInput().cancel(event.getPlayer().getUniqueId());
    }
}
