package de.derfakegamer.sentinel.manager;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FreezeManagerTest {
    @Test void toggleTracksFrozenState() {
        FreezeManager mgr = new FreezeManager();
        UUID id = UUID.randomUUID();
        assertFalse(mgr.isFrozen(id));
        assertTrue(mgr.toggle(id));   // now frozen
        assertTrue(mgr.isFrozen(id));
        assertFalse(mgr.toggle(id));  // now unfrozen
        assertFalse(mgr.isFrozen(id));
    }
}
