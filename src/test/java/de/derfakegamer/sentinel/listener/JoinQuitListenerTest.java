package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JoinQuitListenerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private void storeOverride(UUID id, String name) throws Exception {
        plugin.db().execute(() -> new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database())
            .upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, name, null, null, "Admin", 1L)));
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS); // drain writer
    }

    private void preLogin(UUID id, String realName) throws Exception {
        new LoginListener(plugin).onPreLogin(new org.bukkit.event.player.AsyncPlayerPreLoginEvent(
            realName, InetAddress.getByName("127.0.0.1"), id, true, server.createProfile(id, realName)));
    }

    @Test void nameMessageIsYellowTranslatableWithTheGivenName() {
        Component c = JoinQuitListener.nameMessage("multiplayer.player.joined", "Nicked");
        assertInstanceOf(TranslatableComponent.class, c);
        TranslatableComponent tc = (TranslatableComponent) c;
        assertEquals("multiplayer.player.joined", tc.key());
        assertEquals(NamedTextColor.YELLOW, tc.color());
        assertEquals(1, tc.arguments().size(), "one name argument");
        assertEquals("Nicked", PlainTextComponentSerializer.plainText()
            .serialize(tc.arguments().get(0).asComponent()));
    }

    @Test void loginCachesDisplayNameForJoinMessage() throws Exception {
        UUID id = UUID.randomUUID();
        storeOverride(id, "Nicked");
        preLogin(id, "RealName");
        assertEquals("Nicked", plugin.profile().overrideJoinName(id));
        assertNull(plugin.profile().overrideJoinName(UUID.randomUUID()), "no override -> null");
    }

    @Test void joinMessageUsesDisplayNameOverride() throws Exception {
        PlayerMock p = server.addPlayer("RealName");
        UUID id = p.getUniqueId();
        storeOverride(id, "Nicked");
        preLogin(id, "RealName");
        var event = new org.bukkit.event.player.PlayerJoinEvent(p, Component.text("RealName joined the game"));
        new JoinQuitListener(plugin).onJoin(event);
        assertInstanceOf(TranslatableComponent.class, event.joinMessage());
        TranslatableComponent tc = (TranslatableComponent) event.joinMessage();
        assertEquals("multiplayer.player.joined", tc.key());
        assertEquals("Nicked", PlainTextComponentSerializer.plainText()
            .serialize(tc.arguments().get(0).asComponent()), "join message must show the display name, not the account name");
    }

    @Test void quitRewritesMessageAndEvictsCache() throws Exception {
        PlayerMock p = server.addPlayer("RealName2");
        UUID id = p.getUniqueId();
        storeOverride(id, "Nick2");
        preLogin(id, "RealName2");
        assertEquals("Nick2", plugin.profile().overrideJoinName(id));
        var event = new org.bukkit.event.player.PlayerQuitEvent(p, Component.text("RealName2 left the game"));
        new JoinQuitListener(plugin).onQuit(event);
        assertInstanceOf(TranslatableComponent.class, event.quitMessage());
        assertEquals("Nick2", PlainTextComponentSerializer.plainText()
            .serialize(((TranslatableComponent) event.quitMessage()).arguments().get(0).asComponent()));
        assertNull(plugin.profile().overrideJoinName(id), "quit must evict the cache");
    }

    @Test void joinMessageUnchangedForPlayerWithoutOverride() throws Exception {
        PlayerMock p = server.addPlayer("Plain");
        preLogin(p.getUniqueId(), "Plain");
        Component original = Component.text("Plain joined the game");
        var event = new org.bukkit.event.player.PlayerJoinEvent(p, original);
        new JoinQuitListener(plugin).onJoin(event);
        assertEquals(original, event.joinMessage(), "no override -> join message untouched");
    }
}
