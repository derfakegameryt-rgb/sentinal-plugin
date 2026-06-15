package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.EscalationAction;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class WarnEscalationTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void noActionBelowThreshold() {
        plugin.getConfig().set("warn-actions.3", "ban too many warns");
        assertNull(new WarnEscalation(plugin).actionFor(2));
    }

    @Test void parsesBanAction() {
        plugin.getConfig().set("warn-actions.3", "ban too many warns");
        EscalationAction a = new WarnEscalation(plugin).actionFor(3);
        assertNotNull(a);
        assertEquals(PunishmentType.BAN, a.type());
        assertEquals(0, a.durationMs());
        assertEquals("too many warns", a.reason());
    }

    @Test void parsesTempbanWithDuration() {
        plugin.getConfig().set("warn-actions.5", "tempban 1d serial offender");
        EscalationAction a = new WarnEscalation(plugin).actionFor(5);
        assertEquals(PunishmentType.BAN, a.type());
        assertEquals(86_400_000L, a.durationMs());
        assertEquals("serial offender", a.reason());
    }
}
