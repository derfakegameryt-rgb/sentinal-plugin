package de.derfakegamer.sentinel.scheduler;

import org.bukkit.plugin.Plugin;

/** Detects the platform and builds the matching {@link Scheduler}. */
public final class Schedulers {
    private Schedulers() {}

    private static final boolean FOLIA = detect();

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** True when running on Folia. */
    public static boolean isFolia() { return FOLIA; }

    /** Builds the appropriate scheduler for the detected platform. */
    public static Scheduler create(Plugin plugin) {
        return FOLIA ? new FoliaScheduler(plugin) : new PaperScheduler(plugin);
    }
}
