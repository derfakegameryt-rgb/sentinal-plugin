package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChatListenerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    /**
     * Builds a real {@link AsyncChatEvent} for the given player. The event is constructed
     * directly via its paper-api constructor
     * {@code (boolean async, Player, Set<Audience>, ChatRenderer, Component, Component, SignedMessage)}.
     * We pass {@code async = true} (AsyncChatEvent represents an async event) and a
     * {@link SignedMessage} produced by adventure's {@link SignedMessage#system(String, net.kyori.adventure.text.ComponentLike)}
     * factory. Because the listener is invoked directly (not dispatched through the plugin
     * manager / scheduler), no async-thread assertions are triggered.
     */
    private AsyncChatEvent chatEvent(PlayerMock player, String message) {
        Component msg = Component.text(message);
        Set<Audience> viewers = new HashSet<>(server.getOnlinePlayers());
        SignedMessage signed = SignedMessage.system(message, msg);
        return new AsyncChatEvent(
                true,
                player,
                viewers,
                ChatRenderer.defaultRenderer(),
                msg,
                msg,
                signed);
    }

    @Test
    void mutedPlayerChatIsCancelledAndNotified() {
        PlayerMock p = server.addPlayer("Spammer");
        plugin.punishments().mute(p.getUniqueId(), "Spammer", p.getUniqueId(), "Admin", "spam", 0);

        // Sanity: the mute is genuinely active.
        assertNotNull(plugin.punishments().activeMute(p.getUniqueId(), System.currentTimeMillis()),
                "test precondition: the mute must be active");

        AsyncChatEvent event = chatEvent(p, "hello world");
        new ChatListener(plugin).onChat(event);

        assertTrue(event.isCancelled(), "a muted player's chat must be cancelled");

        // The muted player must be told they are muted.
        Component received = p.nextComponentMessage();
        assertNotNull(received, "muted player should receive a notification");
        String text = PlainTextComponentSerializer.plainText().serialize(received);
        assertTrue(text.toLowerCase().contains("muted"),
                "muted player should be told they are muted, got: " + text);
        assertFalse(text.contains("hello world"),
                "the muted player's chat message must not be echoed back as the notification");
    }

    @Test
    void unmutedPlayerChatIsNotCancelled() {
        PlayerMock p = server.addPlayer("Friendly");

        assertNull(plugin.punishments().activeMute(p.getUniqueId(), System.currentTimeMillis()),
                "test precondition: the player must not be muted");

        AsyncChatEvent event = chatEvent(p, "hello world");
        new ChatListener(plugin).onChat(event);

        assertFalse(event.isCancelled(), "an unmuted player's chat must not be cancelled");
        assertNull(p.nextComponentMessage(),
                "an unmuted player should not receive a mute notification");
    }

    @Test
    void pendingChatInputIsCapturedAndCancelled() {
        org.mockbukkit.mockbukkit.entity.PlayerMock p = server.addPlayer("Mod");
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        plugin.chatInput().await(p.getUniqueId(), captured::set);

        io.papermc.paper.event.player.AsyncChatEvent event = chatEvent(p, "2d6h");
        new ChatListener(plugin).onChat(event);

        assertTrue(event.isCancelled(), "input message must not reach public chat");
        server.getScheduler().performTicks(2); // input callback runs on the main thread
        assertEquals("2d6h", captured.get());
        assertFalse(plugin.chatInput().has(p.getUniqueId()), "pending input is consumed");
    }

    @Test
    void cancelKeywordAbortsInput() {
        org.mockbukkit.mockbukkit.entity.PlayerMock p = server.addPlayer("Mod");
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        plugin.chatInput().await(p.getUniqueId(), captured::set);

        io.papermc.paper.event.player.AsyncChatEvent event = chatEvent(p, "cancel");
        new ChatListener(plugin).onChat(event);

        assertTrue(event.isCancelled());
        server.getScheduler().performTicks(2);
        assertNull(captured.get(), "cancel must NOT invoke the callback");
        assertFalse(plugin.chatInput().has(p.getUniqueId()));
    }

    @Test
    void quitClearsPendingInput() {
        PlayerMock p = server.addPlayer("Mod");
        plugin.chatInput().await(p.getUniqueId(), s -> {});
        assertTrue(plugin.chatInput().has(p.getUniqueId()));

        new ChatListener(plugin).onQuit(
            new org.bukkit.event.player.PlayerQuitEvent(p, Component.empty()));

        assertFalse(plugin.chatInput().has(p.getUniqueId()), "pending input dropped on quit");
    }
}
