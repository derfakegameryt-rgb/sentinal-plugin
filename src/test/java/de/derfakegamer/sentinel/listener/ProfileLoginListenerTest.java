package de.derfakegamer.sentinel.listener;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.derfakegamer.sentinel.Sentinel;
import java.net.InetAddress;
import java.util.UUID;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ProfileLoginListenerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }

    @AfterEach
    void teardown() { MockBukkit.unmock(); }

    @Test
    void loginListenerAppliesStoredOverride() throws Exception {
        UUID id = UUID.randomUUID();
        plugin.db().execute(() -> new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database())
            .upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, "Nicked", null, null, "Admin", 1L)));
        Thread.sleep(200);

        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        new LoginListener(plugin).onPreLogin(event);

        assertEquals("Nicked", event.getPlayerProfile().getName());
    }
}
