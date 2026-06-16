package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.DurationParser;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs config-defined commands on an interval ("every: 2h") or at a daily time ("at: 04:00"). */
public final class CronManager {
    private final Sentinel plugin;
    private final List<Task> tasks = new ArrayList<>();

    public CronManager(Sentinel plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        tasks.clear();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("scheduled-tasks")) {
            Object cmd = raw.get("do");
            if (cmd == null) { plugin.getLogger().warning("scheduled-task missing 'do' — skipped"); continue; }
            String command = String.valueOf(cmd);
            Object every = raw.get("every");
            Object at = raw.get("at");
            try {
                if (every != null) {
                    long interval = DurationParser.parse(String.valueOf(every));
                    tasks.add(Task.interval(command, interval));
                } else if (at != null) {
                    tasks.add(Task.daily(command, LocalTime.parse(String.valueOf(at))));
                } else {
                    plugin.getLogger().warning("scheduled-task '" + command + "' has neither 'every' nor 'at' — skipped");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("invalid scheduled-task '" + command + "': " + e.getMessage());
            }
        }
    }

    /** Returns the commands that should run at {@code now}/{@code time}, marking them as fired. Pure — no Bukkit. */
    public List<String> due(long nowMs, LocalTime time) {
        List<String> out = new ArrayList<>();
        for (Task t : tasks) if (t.isDue(nowMs, time)) out.add(t.command);
        return out;
    }

    /** Number of valid tasks loaded from config. */
    public int taskCount() {
        return tasks.size();
    }

    /** Starts the 1-minute scheduler that dispatches due commands as console. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (String cmd : due(System.currentTimeMillis(), LocalTime.now())) {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            }
        }, 1200L, 1200L); // every 60s
    }

    private static final class Task {
        final String command;
        final long intervalMs;          // >0 for interval tasks
        final LocalTime at;             // non-null for daily tasks
        long lastRun = Long.MIN_VALUE;  // for interval de-dup
        long lastFiredEpochDay = Long.MIN_VALUE;
        int lastFiredMinuteOfDay = -1;  // for daily de-dup within the same minute

        private Task(String command, long intervalMs, LocalTime at) {
            this.command = command; this.intervalMs = intervalMs; this.at = at;
        }
        static Task interval(String c, long ms) { return new Task(c, ms, null); }
        static Task daily(String c, LocalTime at) { return new Task(c, 0, at); }

        boolean isDue(long nowMs, LocalTime time) {
            if (at != null) {
                // Fire once when the wall clock reaches the same hour:minute, at most once per day.
                long epochDay = Math.floorDiv(nowMs, 86_400_000L);
                int minuteOfDay = time.getHour() * 60 + time.getMinute();
                boolean clockMatches = time.getHour() == at.getHour() && time.getMinute() == at.getMinute();
                boolean alreadyFired = epochDay == lastFiredEpochDay && minuteOfDay == lastFiredMinuteOfDay;
                if (clockMatches && !alreadyFired) {
                    lastFiredEpochDay = epochDay;
                    lastFiredMinuteOfDay = minuteOfDay;
                    return true;
                }
                return false;
            }
            // Interval task: fire on first evaluation, then once per interval window.
            if (lastRun == Long.MIN_VALUE || nowMs - lastRun >= intervalMs) {
                lastRun = nowMs;
                return true;
            }
            return false;
        }
    }
}
