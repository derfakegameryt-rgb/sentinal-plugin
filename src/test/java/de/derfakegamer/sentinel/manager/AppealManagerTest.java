package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AppealManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void submitDedupsOpenAppeals() {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        assertTrue(plugin.appeals().submit(uuid, "Bob", 0, PunishmentType.MUTE, "first", now));
        assertFalse(plugin.appeals().submit(uuid, "Bob", 0, PunishmentType.MUTE, "second", now));
    }

    @Test void acceptLiftsTheMute() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        long now = System.currentTimeMillis();
        plugin.punishments().mute(uuid, "Bob", issuer, "Admin", "spam", 0).get(2, TimeUnit.SECONDS);

        Punishment mute = plugin.punishments().activeMute(uuid, now).get(2, TimeUnit.SECONDS);
        assertNotNull(mute);
        assertTrue(plugin.appeals().submit(uuid, "Bob", mute.id(), PunishmentType.MUTE, "sorry", now));
        var appeal = plugin.appeals().open().get(0);
        plugin.appeals().accept(appeal, "Admin", now).get(2, TimeUnit.SECONDS);

        assertNull(plugin.punishments().activeMute(uuid, now).get(2, TimeUnit.SECONDS));
        assertTrue(plugin.appeals().open().isEmpty());
    }
}
