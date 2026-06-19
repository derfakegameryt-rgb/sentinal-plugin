package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalStrikeTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void columnCoversFullHeightAtSpacing() {
        List<Integer> ys = new OrbitalStrike(plugin).columnYs(8);
        assertEquals(-60, ys.get(0));               // starts at bottom
        assertTrue(ys.get(ys.size() - 1) <= 320);   // never above build limit
        assertTrue(ys.contains(-52) && ys.contains(4)); // stepped by 8 from -60
        // every entry is 8 apart
        for (int i = 1; i < ys.size(); i++) assertEquals(8, ys.get(i) - ys.get(i - 1));
    }

    @Test void spacingIsClampedToAtLeastOne() {
        assertFalse(new OrbitalStrike(plugin).columnYs(0).isEmpty());
    }

    @Test void biggerPayloadHasMorePower() {
        OrbitalStrike s = new OrbitalStrike(plugin);
        assertTrue(s.power(de.derfakegamer.sentinel.model.OrbitalPayload.CHARGED_CREEPER)
                 > s.power(de.derfakegamer.sentinel.model.OrbitalPayload.TNT_MINECART));
        assertTrue(s.power(de.derfakegamer.sentinel.model.OrbitalPayload.TNT_MINECART)
                 > s.power(de.derfakegamer.sentinel.model.OrbitalPayload.TNT));
    }
}
