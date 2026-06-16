package de.derfakegamer.sentinel.manager;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AfkManagerTest {
    @Test void unknownPlayerIsNotIdle() {
        assertEquals(0, new AfkManager().idleMs(UUID.randomUUID(), 1000));
    }
    @Test void idleGrowsAfterBump() {
        AfkManager m = new AfkManager();
        UUID id = UUID.randomUUID();
        m.bump(id);
        assertTrue(m.idleMs(id, System.currentTimeMillis() + 1_000_000L) >= 1_000_000L - 5000);
    }
    @Test void forgetClearsActivity() {
        AfkManager m = new AfkManager();
        UUID id = UUID.randomUUID();
        m.bump(id);
        m.forget(id);
        assertEquals(0, m.idleMs(id, System.currentTimeMillis()));
    }
    @Test void markAfkIsIdempotentAndBumpClearsIt() {
        AfkManager m = new AfkManager();
        UUID id = UUID.randomUUID();
        assertFalse(m.isAfk(id));
        assertTrue(m.markAfk(id));   // newly flagged
        assertFalse(m.markAfk(id));  // already flagged
        assertTrue(m.isAfk(id));
        assertTrue(m.bump(id));      // returning from AFK
        assertFalse(m.isAfk(id));
        assertFalse(m.bump(id));     // not AFK any more
    }
    @Test void forgetClearsAfkFlag() {
        AfkManager m = new AfkManager();
        UUID id = UUID.randomUUID();
        m.markAfk(id);
        m.forget(id);
        assertFalse(m.isAfk(id));
    }
}
