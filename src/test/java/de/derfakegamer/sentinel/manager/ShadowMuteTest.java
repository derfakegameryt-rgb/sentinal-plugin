package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShadowMuteTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void applyShadowMuteRecordsActiveShadowMute() throws Exception {
        UUID t = UUID.randomUUID();
        boolean ok = plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null,
            PunishmentType.SHADOWMUTE, 0, "test").get(2, TimeUnit.SECONDS);
        assertTrue(ok);
        assertNotNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
        // a shadow-mute must NOT register as a normal mute
        assertNull(plugin.punishments().activeMute(t, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void removeShadowMuteClearsIt() throws Exception {
        UUID t = UUID.randomUUID();
        plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null, PunishmentType.SHADOWMUTE, 0, "x")
            .get(2, TimeUnit.SECONDS);
        boolean removed = plugin.moderation().removeShadowMute(new UUID(0,0), "Admin", t, "Sneaky")
            .get(2, TimeUnit.SECONDS);
        assertTrue(removed);
        assertNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }
}
