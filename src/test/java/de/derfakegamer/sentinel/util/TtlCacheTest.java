package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TtlCacheTest {
    @Test void cachesWithinTtlThenReloadsAfterExpiry() {
        AtomicLong now = new AtomicLong(0);
        TtlCache<String,Integer> c = new TtlCache<>(100, now::get);
        AtomicInteger loads = new AtomicInteger();
        assertEquals(1, c.get("k", k -> { loads.incrementAndGet(); return 1; }));
        assertEquals(1, c.get("k", k -> { loads.incrementAndGet(); return 2; })); // cached
        assertEquals(1, loads.get());
        now.set(101); // expire
        assertEquals(2, c.get("k", k -> { loads.incrementAndGet(); return 2; }));
        assertEquals(2, loads.get());
    }

    @Test void invalidateForcesReload() {
        AtomicLong now = new AtomicLong(0);
        TtlCache<String,Integer> c = new TtlCache<>(1000, now::get);
        AtomicInteger loads = new AtomicInteger();
        c.get("k", k -> { loads.incrementAndGet(); return 1; });
        c.invalidate("k");
        c.get("k", k -> { loads.incrementAndGet(); return 1; });
        assertEquals(2, loads.get());
    }

    @Test void nullValueIsAMiss() {
        AtomicLong now = new AtomicLong(0);
        TtlCache<String,String> c = new TtlCache<>(1000, now::get);
        AtomicInteger loads = new AtomicInteger();
        c.get("k", k -> { loads.incrementAndGet(); return null; });
        c.get("k", k -> { loads.incrementAndGet(); return null; });
        assertEquals(2, loads.get()); // null not cached
    }

    @Test void clearEmpties() {
        TtlCache<String,Integer> c = new TtlCache<>(1000);
        c.put("k", 9);
        c.clear();
        AtomicInteger loads = new AtomicInteger();
        c.get("k", k -> { loads.incrementAndGet(); return 1; });
        assertEquals(1, loads.get());
    }
}
