package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

public final class OrbitalStrike {
    private static final int BOTTOM = -60, TOP = 320;
    private static final int SPACING = 8;       // exposed y-levels (used by columnYs / tests)
    private static final int STRIKE_STEP = 4;   // explosions every 4 blocks → one continuous shaft

    private final Sentinel plugin;

    public OrbitalStrike(Sentinel plugin) { this.plugin = plugin; }

    /** The y levels stepped by spacing (min 1). */
    public List<Integer> columnYs(int spacing) {
        int step = Math.max(1, spacing);
        List<Integer> ys = new ArrayList<>();
        for (int y = BOTTOM; y <= TOP; y += step) ys.add(y);
        return ys;
    }

    /** Blast power of each in-block explosion for the chosen payload. */
    public float power(OrbitalPayload payload) {
        return switch (payload) {
            case TNT -> 4f;
            case TNT_MINECART -> 5f;
            case CHARGED_CREEPER -> 7f;
        };
    }

    /**
     * Instantly carves a hole straight down to bedrock at X/Z by detonating in-block
     * explosions all the way up the column (no falling entities — they would just destroy
     * each other). Bigger payloads = bigger blast. Server-only.
     */
    public void strike(World world, int x, int z, OrbitalPayload payload) {
        float power = power(payload);
        int top = TOP;
        try { top = Math.min(TOP, world.getHighestBlockYAt(x, z) + 8); } catch (Throwable ignored) { /* test env */ }
        for (int y = BOTTOM; y <= top; y += STRIKE_STEP) {
            try {
                world.createExplosion(x + 0.5, y, z + 0.5, power, false, true);
            } catch (Throwable t) {
                // one failed explosion (e.g. unloaded edge, test env) must not abort the strike
                plugin.getLogger().fine("orbital explosion failed at y=" + y + ": " + t.getMessage());
            }
        }
    }
}
