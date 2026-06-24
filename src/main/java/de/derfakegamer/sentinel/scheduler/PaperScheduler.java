package de.derfakegamer.sentinel.scheduler;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

/** Paper/Spigot impl: one main thread, so global and entity both map to runTask. */
public final class PaperScheduler implements Scheduler {
    private final Plugin plugin;
    private final BukkitScheduler s;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.s = plugin.getServer().getScheduler();
    }

    @Override public void runGlobal(Runnable task) { s.runTask(plugin, task); }
    @Override public void runGlobalLater(Runnable task, long delayTicks) { s.runTaskLater(plugin, task, delayTicks); }
    @Override public TaskHandle globalTimer(Runnable task, long delayTicks, long periodTicks) {
        org.bukkit.scheduler.BukkitTask t = s.runTaskTimer(plugin, task, delayTicks, periodTicks);
        return t::cancel;
    }

    @Override public void runForEntity(Entity entity, Runnable task) { s.runTask(plugin, task); }
    @Override public void runForEntityLater(Entity entity, Runnable task, long delayTicks) { s.runTaskLater(plugin, task, delayTicks); }

    @Override public void runAsync(Runnable task) { s.runTaskAsynchronously(plugin, task); }
    @Override public TaskHandle asyncTimer(Runnable task, long delayTicks, long periodTicks) {
        org.bukkit.scheduler.BukkitTask t = s.runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        return t::cancel;
    }

    @Override public void cancelAll() { s.cancelTasks(plugin); }
}
