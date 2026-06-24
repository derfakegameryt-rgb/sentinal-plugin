package de.derfakegamer.sentinel.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SchedulersTest {
    @Test void notFoliaUnderTests() {
        assertFalse(Schedulers.isFolia(), "MockBukkit is not Folia, so detection must be false");
    }
}
