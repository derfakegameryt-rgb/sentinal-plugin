package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShadowMuteTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void applyShadowMuteRecordsActiveShadowMute() {
        UUID t = UUID.randomUUID();
        assertTrue(plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null,
            PunishmentType.SHADOWMUTE, 0, "test"));
        assertNotNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()));
        // a shadow-mute must NOT register as a normal mute
        assertNull(plugin.punishments().activeMute(t, System.currentTimeMillis()));
    }

    @Test void removeShadowMuteClearsIt() {
        UUID t = UUID.randomUUID();
        plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null, PunishmentType.SHADOWMUTE, 0, "x");
        assertTrue(plugin.moderation().removeShadowMute(new UUID(0,0), "Admin", t, "Sneaky"));
        assertNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()));
    }
}
