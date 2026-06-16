package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
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

    @Test void acceptLiftsTheMute() {
        UUID uuid = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        long now = System.currentTimeMillis();
        plugin.punishments().mute(uuid, "Bob", issuer, "Admin", "spam", 0);
        assertNotNull(plugin.punishments().activeMute(uuid, now));

        var mute = plugin.punishments().activeMute(uuid, now);
        assertTrue(plugin.appeals().submit(uuid, "Bob", mute.id(), PunishmentType.MUTE, "sorry", now));
        var appeal = plugin.appeals().open().get(0);
        plugin.appeals().accept(appeal, "Admin", now);

        assertNull(plugin.punishments().activeMute(uuid, now));
        assertTrue(plugin.appeals().open().isEmpty());
    }
}
