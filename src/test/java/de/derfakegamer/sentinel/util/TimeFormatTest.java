package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimeFormatTest {
    @Test void permanentWhenZero() { assertEquals("permanent", TimeFormat.until(0, 1000)); }
    @Test void expiredWhenPast() { assertEquals("expired", TimeFormat.until(500, 1000)); }
    @Test void remainingDuration() { assertEquals("1h", TimeFormat.until(1000 + 3_600_000L, 1000)); }
    @Test void durationFormatsParts() {
        assertEquals("1d 2h 3m", TimeFormat.duration(86_400_000L + 2*3_600_000L + 3*60_000L));
        assertEquals("45s", TimeFormat.duration(45_000L));
        assertEquals("0s", TimeFormat.duration(0));
    }
}
