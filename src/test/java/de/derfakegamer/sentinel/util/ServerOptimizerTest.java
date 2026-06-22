package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerOptimizerTest {
    private static long gb(double n) { return (long) (n * 1073741824.0); }

    @Test void roundsXmxLikeValues() {
        assertEquals(4, ServerOptimizer.roundedGb(gb(3.9)));   // -Xmx4G reports ~3.9
        assertEquals(6, ServerOptimizer.roundedGb(gb(5.8)));
    }

    @Test void recommendsTableValuesAtTiers() {
        assertEquals(new ServerOptimizer.Preset(4, 3), ServerOptimizer.recommend(gb(1)));
        assertEquals(new ServerOptimizer.Preset(6, 4), ServerOptimizer.recommend(gb(2)));
        assertEquals(new ServerOptimizer.Preset(7, 5), ServerOptimizer.recommend(gb(4)));
        assertEquals(new ServerOptimizer.Preset(8, 5), ServerOptimizer.recommend(gb(6)));
        assertEquals(new ServerOptimizer.Preset(9, 6), ServerOptimizer.recommend(gb(8)));
        assertEquals(new ServerOptimizer.Preset(10, 6), ServerOptimizer.recommend(gb(12)));
        assertEquals(new ServerOptimizer.Preset(11, 7), ServerOptimizer.recommend(gb(16)));
        assertEquals(new ServerOptimizer.Preset(12, 7), ServerOptimizer.recommend(gb(24)));
        assertEquals(new ServerOptimizer.Preset(12, 8), ServerOptimizer.recommend(gb(32)));
    }

    @Test void recommendsBetweenTiersUsesLowerTierAndClamps() {
        assertEquals(new ServerOptimizer.Preset(7, 5), ServerOptimizer.recommend(gb(5)));   // 5 -> 4-tier
        assertEquals(new ServerOptimizer.Preset(9, 6), ServerOptimizer.recommend(gb(10)));  // 10 -> 8-tier
        assertEquals(new ServerOptimizer.Preset(4, 3), ServerOptimizer.recommend(gb(0.5))); // <1 -> 1-tier
        assertEquals(new ServerOptimizer.Preset(12, 8), ServerOptimizer.recommend(gb(64))); // >32 -> 32-tier
    }

    @Test void simAlwaysBelowView() {
        for (double g : new double[]{1,2,4,6,8,12,16,24,32}) {
            var p = ServerOptimizer.recommend(gb(g));
            assertTrue(p.sim() < p.view(), "sim<view at " + g + "GB");
        }
    }

    @Test void replacePropertyReplacesAndPreserves() {
        String in = "#comment\nview-distance=10\nsimulation-distance=10\nmotd=hi\n";
        String out = ServerOptimizer.replaceProperty(in, "view-distance", 7);
        assertTrue(out.contains("view-distance=7"));
        assertTrue(out.contains("simulation-distance=10"));
        assertTrue(out.contains("motd=hi"));
        assertTrue(out.contains("#comment"));
    }

    @Test void replacePropertyAppendsWhenAbsent() {
        String out = ServerOptimizer.replaceProperty("motd=hi\n", "view-distance", 7);
        assertTrue(out.contains("motd=hi"));
        assertTrue(out.contains("view-distance=7"));
    }
}
