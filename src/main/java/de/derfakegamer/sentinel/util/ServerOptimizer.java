package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** RAM-tiered view/simulation-distance presets + one-click apply. Pure parts are static + testable. */
public final class ServerOptimizer {
    public record Preset(int view, int sim) {}

    // {gbThreshold, view, sim} ascending; sim < view; view capped 12, sim capped 8.
    private static final int[][] TIERS = {
        {1, 4, 3}, {2, 6, 4}, {4, 7, 5}, {6, 8, 5}, {8, 9, 6},
        {12, 10, 6}, {16, 11, 7}, {24, 12, 7}, {32, 12, 8}
    };

    public static long roundedGb(long maxMemoryBytes) {
        return Math.round(maxMemoryBytes / 1073741824.0);
    }

    public static Preset recommend(long maxMemoryBytes) {
        long gb = roundedGb(maxMemoryBytes);
        int[] chosen = TIERS[0];               // floor: 1 GB tier
        for (int[] t : TIERS) if (gb >= t[0]) chosen = t;   // highest threshold <= gb
        return new Preset(chosen[1], chosen[2]);
    }

    /** Replaces the value of {@code key=...} in properties text (preserves other lines + comments); appends if absent. */
    public static String replaceProperty(String content, String key, int value) {
        String[] lines = content.split("\n", -1);
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(key + "=")) { lines[i] = key + "=" + value; found = true; }
        }
        String joined = String.join("\n", lines);
        if (!found) joined = joined + (joined.isEmpty() || joined.endsWith("\n") ? "" : "\n") + key + "=" + value + "\n";
        return joined;
    }

    private final Sentinel plugin;
    public ServerOptimizer(Sentinel plugin) { this.plugin = plugin; }

    public long ramGb() { return roundedGb(Runtime.getRuntime().maxMemory()); }
    public Preset recommended() { return recommend(Runtime.getRuntime().maxMemory()); }

    public int currentView() {
        List<World> w = Bukkit.getWorlds();
        return w.isEmpty() ? -1 : w.get(0).getViewDistance();
    }
    public int currentSim() {
        List<World> w = Bukkit.getWorlds();
        return w.isEmpty() ? -1 : w.get(0).getSimulationDistance();
    }

    /** Applies to every world (runtime) and persists to server.properties (best-effort, never throws). */
    public void apply(Preset p) {
        for (World w : Bukkit.getWorlds()) {
            try { w.setViewDistance(p.view()); w.setSimulationDistance(p.sim()); }
            catch (Throwable t) { plugin.debug("optimize: world " + w.getName() + " failed: " + t.getMessage()); }
        }
        try {
            Path f = Path.of("server.properties");
            if (Files.isRegularFile(f)) {
                String c = Files.readString(f);
                c = replaceProperty(c, "view-distance", p.view());
                c = replaceProperty(c, "simulation-distance", p.sim());
                Files.writeString(f, c);
            }
        } catch (Throwable t) {
            plugin.debug("optimize: persist failed: " + t.getMessage());
        }
    }
}
