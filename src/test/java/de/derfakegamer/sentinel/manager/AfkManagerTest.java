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
}
