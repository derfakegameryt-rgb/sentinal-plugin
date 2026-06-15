package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;

import java.util.ArrayList;
import java.util.List;

public final class OrbitalStrike {
    private static final int BOTTOM = -60, TOP = 320;
    private static final int SPACING = 8; // fixed; not configurable

    private final Sentinel plugin;

    public OrbitalStrike(Sentinel plugin) { this.plugin = plugin; }

    /** The y levels that receive a payload, bottom-up, stepped by spacing (min 1). */
    public List<Integer> columnYs(int spacing) {
        int step = Math.max(1, spacing);
        List<Integer> ys = new ArrayList<>();
        for (int y = BOTTOM; y <= TOP; y += step) ys.add(y);
        return ys;
    }

    /** Bombards the X/Z column in the given world with the chosen payload. Server-only. */
    public void strike(World world, int x, int z, OrbitalPayload payload) {
        for (int y : columnYs(SPACING)) {
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            try {
                switch (payload) {
                    case TNT -> world.spawn(loc, TNTPrimed.class, t -> t.setFuseTicks(40));
                    case TNT_MINECART -> {
                        ExplosiveMinecart cart = world.spawn(loc, ExplosiveMinecart.class);
                        try { cart.ignite(); } catch (Throwable ignored) { /* older API */ }
                    }
                    case CHARGED_CREEPER -> world.spawn(loc, Creeper.class, c -> {
                        c.setPowered(true);
                        c.setIgnited(true);
                    });
                }
            } catch (Throwable t) {
                // one failed spawn (e.g. unloaded edge, test env) must not abort the whole strike
                plugin.getLogger().fine("orbital strike spawn failed at y=" + y + ": " + t.getMessage());
            }
        }
    }
}
