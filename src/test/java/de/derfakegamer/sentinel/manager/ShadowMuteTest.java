package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShadowMuteTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /** Pumps scheduler ticks while waiting for futures that schedule main-thread side-effects. */
    static <T> T await(ServerMock server, CompletableFuture<T> f) throws Exception {
        for (int i = 0; i < 200 && !f.isDone(); i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
        return f.get(2, TimeUnit.SECONDS);
    }

    @Test void applyShadowMuteRecordsActiveShadowMute() throws Exception {
        UUID t = UUID.randomUUID();
        boolean ok = await(server, plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null,
            PunishmentType.SHADOWMUTE, 0, "test"));
        assertTrue(ok);
        assertNotNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
        // a shadow-mute must NOT register as a normal mute
        assertNull(plugin.punishments().activeMute(t, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void removeShadowMuteClearsIt() throws Exception {
        UUID t = UUID.randomUUID();
        await(server, plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null, PunishmentType.SHADOWMUTE, 0, "x"));
        boolean removed = await(server, plugin.moderation().removeShadowMute(new UUID(0,0), "Admin", t, "Sneaky"));
        assertTrue(removed);
        assertNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }
}
