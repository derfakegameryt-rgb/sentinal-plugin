package de.derfakegamer.sentinel.scheduler;

import org.bukkit.entity.Entity;

/** Platform-neutral scheduling: Paper (one main thread) and Folia (regionized) behind one API. */
public interface Scheduler {
    /** Server-wide state (broadcast, shutdown, dispatchCommand, whitelist). */
    void runGlobal(Runnable task);
    void runGlobalLater(Runnable task, long delayTicks);
    TaskHandle globalTimer(Runnable task, long delayTicks, long periodTicks);

    /** Anything that mutates one entity/player (kick, setOp, hide/show, setPlayerProfile, openInventory). */
    void runForEntity(Entity entity, Runnable task);
    void runForEntityLater(Entity entity, Runnable task, long delayTicks);

    /** Off-thread work with no Bukkit state (HTTP, DB I/O). */
    void runAsync(Runnable task);
    TaskHandle asyncTimer(Runnable task, long delayTicks, long periodTicks);

    /** Cancels every task this plugin owns (called on disable). */
    void cancelAll();
}
