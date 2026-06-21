package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CooldownManagerTest {
    @Test void firstAllowedSecondBlockedThenAllowedAfterExpiry() {
        CooldownManager c = new CooldownManager();
        UUID id = UUID.randomUUID();
        assertTrue(c.tryUse(id, "report", 1000, 0));
        assertFalse(c.tryUse(id, "report", 1000, 500));
        assertTrue(c.tryUse(id, "report", 1000, 1000));
    }
    @Test void remainingCountsDown() {
        CooldownManager c = new CooldownManager();
        UUID id = UUID.randomUUID();
        c.tryUse(id, "report", 1000, 0);
        assertEquals(700, c.remainingMillis(id, "report", 1000, 300));
        assertEquals(0, c.remainingMillis(id, "report", 1000, 1000));
    }
    @Test void zeroCooldownAlwaysAllows() {
        CooldownManager c = new CooldownManager();
        UUID id = UUID.randomUUID();
        assertTrue(c.tryUse(id, "x", 0, 0));
        assertTrue(c.tryUse(id, "x", 0, 0));
    }
    @Test void distinctKeysIndependent() {
        CooldownManager c = new CooldownManager();
        UUID id = UUID.randomUUID();
        assertTrue(c.tryUse(id, "report", 1000, 0));
        assertTrue(c.tryUse(id, "appeal", 1000, 0));
    }
}
