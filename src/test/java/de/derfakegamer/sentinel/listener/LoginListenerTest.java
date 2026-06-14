package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LoginListenerTest {
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

    @Test
    void bannedPlayerIsDisallowedAtLogin() throws Exception {
        UUID id = UUID.randomUUID();
        plugin.punishments().ban(id, "Griefer", UUID.randomUUID(), "Admin", "hax", 0);

        LoginListener listener = new LoginListener(plugin);
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
                "Griefer", InetAddress.getByName("1.2.3.4"), id);
        listener.onPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
    }

    @Test
    void normalPlayerIsAllowedAtLogin() throws Exception {
        UUID id = UUID.randomUUID();

        LoginListener listener = new LoginListener(plugin);
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
                "Friendly", InetAddress.getByName("1.2.3.4"), id);
        listener.onPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
    }

    @Test
    void ipBannedPlayerIsDisallowedAtLogin() throws Exception {
        UUID bannerTarget = UUID.randomUUID();
        plugin.punishments().ipBan(bannerTarget, "Evil", "9.9.9.9",
                UUID.randomUUID(), "Admin", "ban evasion", 0);

        // A different account, same IP, must be rejected by ip-ban.
        LoginListener listener = new LoginListener(plugin);
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
                "Alt", InetAddress.getByName("9.9.9.9"), UUID.randomUUID());
        listener.onPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
    }
}
