package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void applyBanRecordsActiveBan() throws Exception {
        UUID target = UUID.randomUUID();
        boolean ok = plugin.moderation().apply(
            new UUID(0,0), "Admin", target, "Griefer", null, PunishmentType.BAN, 0, "hax");
        assertTrue(ok);
        assertNotNull(plugin.punishments().activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void applyExemptReturnsFalseAndRecordsNothing() throws Exception {
        UUID exempt = UUID.randomUUID();
        plugin.getConfig().set("exempt", java.util.List.of(exempt.toString()));
        plugin.saveConfig();
        plugin.reloadAll();
        boolean ok = plugin.moderation().apply(
            new UUID(0,0), "Admin", exempt, "Owner", null, PunishmentType.BAN, 0, "x");
        assertFalse(ok);
        assertNull(plugin.punishments().activeBan(exempt, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void removeBanReturnsFalseWhenNotBanned() {
        assertFalse(plugin.moderation().removeBan(new UUID(0,0), "Admin", UUID.randomUUID(), "Nobody"));
    }

    @Test void warnEscalationAutoBansAtThreshold() throws Exception {
        plugin.getConfig().set("warn-actions.2", "ban escalated for repeated warnings");
        UUID target = UUID.randomUUID();
        long now = System.currentTimeMillis();
        plugin.moderation().apply(new UUID(0,0), "Admin", target, "Repeat", null, PunishmentType.WARN, 0, "w1");
        assertNull(plugin.punishments().activeBan(target, now).get(2, TimeUnit.SECONDS), "not banned after the first warning");
        plugin.moderation().apply(new UUID(0,0), "Admin", target, "Repeat", null, PunishmentType.WARN, 0, "w2");
        assertNotNull(plugin.punishments().activeBan(target, now).get(2, TimeUnit.SECONDS), "auto-banned when warnings reach the threshold");
    }
}
