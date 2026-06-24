package de.derfakegamer.sentinel.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Folia impl: delegates to the global-region, per-entity and async schedulers. Kept deliberately
 * thin (pure delegation) because it cannot run under MockBukkit; verified manually on Folia.
 */
public final class FoliaScheduler implements Scheduler {
    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) { this.plugin = plugin; }

    @Override public void runGlobal(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
    }
    @Override public void runGlobalLater(Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1L, delayTicks));
    }
    @Override public TaskHandle globalTimer(Runnable task, long delayTicks, long periodTicks) {
        ScheduledTask t = Bukkit.getGlobalRegionScheduler()
            .runAtFixedRate(plugin, x -> task.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        return t::cancel;
    }

    @Override public void runForEntity(Entity entity, Runnable task) {
        // run(...) returns null if the entity was removed before scheduling; that is a no-op for us.
        entity.getScheduler().run(plugin, t -> task.run(), null);
    }
    @Override public void runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1L, delayTicks));
    }

    @Override public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }
    @Override public TaskHandle asyncTimer(Runnable task, long delayTicks, long periodTicks) {
        // Folia's async scheduler uses real time; convert ticks to milliseconds (1 tick = 50 ms).
        ScheduledTask t = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, x -> task.run(),
            Math.max(1L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS);
        return t::cancel;
    }

    @Override public void cancelAll() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }
}
