package de.derfakegamer.sentinel.listener;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OwnerLoginProtectionTest {
    ServerMock server;
    Sentinel plugin;
    LoginListener listener;

    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); listener = new LoginListener(plugin); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private void drain() throws Exception { plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS); }

    private AsyncPlayerPreLoginEvent prelogin(UUID id, String name) throws Exception {
        return new AsyncPlayerPreLoginEvent(name, InetAddress.getByName("127.0.0.1"), id);
    }

    @Test void ownerWithAutoUnbanOffIsBanned() throws Exception {
        UUID id = plugin.owner().uuid();
        plugin.punishments().ban(id, "DerFakeGamer", new UUID(0,0), "CONSOLE", "test", 0L).get(2, TimeUnit.SECONDS);
        plugin.ownerProtection().setAutoUnban(false);
        AsyncPlayerPreLoginEvent e = prelogin(id, "DerFakeGamer");
        listener.onPreLogin(e);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, e.getLoginResult());
    }

    @Test void ownerWithAutoUnbanOnIsAllowedAndUnbanned() throws Exception {
        UUID id = plugin.owner().uuid();
        plugin.punishments().ban(id, "DerFakeGamer", new UUID(0,0), "CONSOLE", "test", 0L).get(2, TimeUnit.SECONDS);
        plugin.ownerProtection().setAutoUnban(true);
        drain();
        AsyncPlayerPreLoginEvent e = prelogin(id, "DerFakeGamer");
        listener.onPreLogin(e);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, e.getLoginResult());
        drain();
        assertNull(plugin.punishments().activeBan(id, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void autoWhitelistMarksOwnerWhitelisted() throws Exception {
        plugin.ownerProtection().setAutoWhitelist(true);
        // Undo the immediate side-effect so we can verify the LoginListener path actually whitelists
        org.bukkit.Bukkit.getOfflinePlayer(plugin.owner().uuid()).setWhitelisted(false);

        // Build and fire an owner prelogin event through the listener
        AsyncPlayerPreLoginEvent e = prelogin(plugin.owner().uuid(), "DerFakeGamer");
        listener.onPreLogin(e);

        // Flush the scheduled main-thread task
        server.getScheduler().performTicks(3);

        // Assert the owner is whitelisted via the whitelist set
        UUID ownerUuid = plugin.owner().uuid();
        assertTrue(server.getWhitelistedPlayers().stream()
                .anyMatch(p -> ownerUuid.equals(p.getUniqueId())),
                "owner UUID must be in the whitelisted players set");
    }
}
