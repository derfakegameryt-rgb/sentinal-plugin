package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void applyBanRecordsActiveBan() {
        UUID target = UUID.randomUUID();
        boolean ok = plugin.moderation().apply(
            new UUID(0,0), "Admin", target, "Griefer", null, PunishmentType.BAN, 0, "hax");
        assertTrue(ok);
        assertNotNull(plugin.punishments().activeBan(target, System.currentTimeMillis()));
    }

    @Test void applyExemptReturnsFalseAndRecordsNothing() {
        UUID exempt = UUID.randomUUID();
        plugin.getConfig().set("exempt", java.util.List.of(exempt.toString()));
        plugin.reloadAll();
        boolean ok = plugin.moderation().apply(
            new UUID(0,0), "Admin", exempt, "Owner", null, PunishmentType.BAN, 0, "x");
        assertFalse(ok);
        assertNull(plugin.punishments().activeBan(exempt, System.currentTimeMillis()));
    }

    @Test void removeBanReturnsFalseWhenNotBanned() {
        assertFalse(plugin.moderation().removeBan(new UUID(0,0), "Admin", UUID.randomUUID(), "Nobody"));
    }
}
