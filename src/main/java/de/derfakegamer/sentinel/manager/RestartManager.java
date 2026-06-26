package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import java.util.List;

public final class RestartManager {
    // seconds-remaining marks at which players are warned
    private static final List<Integer> MARKS = List.of(600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1);

    private final Sentinel plugin;
    private de.derfakegamer.sentinel.scheduler.TaskHandle task;
    private int remaining;

    public RestartManager(Sentinel plugin) { this.plugin = plugin; }

    /** True while a restart countdown is scheduled (set by schedule(), cleared by cancel()). */
    public boolean isPending() { return task != null; }

    /** True if a given seconds-remaining value should trigger a warning broadcast. */
    public boolean isWarnTick(int secondsRemaining) { return MARKS.contains(secondsRemaining); }

    public void schedule(int seconds) {
        cancel();
        this.remaining = seconds;
        warn(remaining);
        task = plugin.scheduler().globalTimer(() -> {
            remaining--;
            if (remaining <= 0) { Bukkit.shutdown(); return; }
            if (isWarnTick(remaining)) warn(remaining);
        }, 20L, 20L);
    }

    public boolean cancel() {
        if (task == null) return false;
        task.cancel(); task = null;
        Bukkit.broadcast(plugin.messages().prefixed("restart-cancelled"));
        return true;
    }

    private void warn(int seconds) {
        Bukkit.broadcast(plugin.messages().prefixed("restart-warning", "time", human(seconds)));
    }

    public static String human(int seconds) {
        if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
}
