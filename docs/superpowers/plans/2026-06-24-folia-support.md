# Folia Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One jar that runs correctly on both Paper/Spigot and Folia, choosing the right scheduler at runtime.

**Architecture:** A hand-rolled `Scheduler` abstraction (`scheduler` package) with a Paper impl (delegates to `BukkitScheduler`) and a thin Folia impl (delegates to Folia's global/entity/async schedulers), selected at runtime by class-presence detection. The DB callback hop and all 26 scheduler call sites are routed through the abstraction, sorted into global / entity / async domains.

**Tech Stack:** Java 21, Paper API 1.21.11 (its jar already exposes the Folia scheduler API — no extra dependency), MockBukkit 4.110.0, Gradle.

## Global Constraints

- One jar for **both** Paper and Folia (runtime detection), no Folia-only API at load time outside the `FoliaScheduler` class, no new dependency.
- The Folia scheduler classes (`io.papermc.paper.threadedregions.scheduler.*`, `Bukkit.getGlobalRegionScheduler()`, `Entity.getScheduler()`, `Bukkit.getAsyncScheduler()`) are present in paper-api 1.21.11 and compile fine; they only *throw* at runtime on non-Folia if actually used — so `FoliaScheduler` is only ever instantiated when Folia is detected.
- **Scope boundary:** plain `player.sendMessage(...)` / `Bukkit.broadcast(...)` text sends are thread-safe on Folia and are NOT individually scheduled. Only scheduler calls and **hard region mutations** are migrated: `kick`, `setOp`, `teleport`, `openInventory`, `hidePlayer`/`showPlayer`, `setPlayerProfile`, inventory edits (entity domain); `setWhitelisted`, `Bukkit.shutdown()`, `dispatchCommand`, `World.save()` (global domain).
- Existing code style: 4-space indent; inline fully-qualified names are common. Preserve indentation when editing.
- `Schedulers.isFolia()` returns `false` under MockBukkit, so the Paper path runs in all tests; the full `./gradlew build` must stay GREEN. The Folia path is verified manually on a real Folia server.

---

### Task 1: Scheduler abstraction core + Paper impl + detection + wiring

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/scheduler/Scheduler.java`
- Create: `src/main/java/de/derfakegamer/sentinel/scheduler/TaskHandle.java`
- Create: `src/main/java/de/derfakegamer/sentinel/scheduler/PaperScheduler.java`
- Create: `src/main/java/de/derfakegamer/sentinel/scheduler/Schedulers.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Test: `src/test/java/de/derfakegamer/sentinel/scheduler/SchedulersTest.java`
- Test: `src/test/java/de/derfakegamer/sentinel/scheduler/PaperSchedulerTest.java`

**Interfaces:**
- Produces: `Scheduler` (methods `runGlobal`, `runGlobalLater`, `globalTimer`, `runForEntity`, `runForEntityLater`, `runAsync`, `asyncTimer`, `cancelAll`), `TaskHandle` (`cancel()`), `PaperScheduler`, `Schedulers.create(Plugin)` / `Schedulers.isFolia()`. `Sentinel.scheduler()` returns the active `Scheduler`.

- [ ] **Step 1: Write `Scheduler` and `TaskHandle` interfaces**

`Scheduler.java`:
```java
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
```

`TaskHandle.java`:
```java
package de.derfakegamer.sentinel.scheduler;

/** A cancellable repeating task, returned by the timer methods. */
public interface TaskHandle {
    void cancel();
}
```

- [ ] **Step 2: Write `PaperScheduler`**

`PaperScheduler.java`:
```java
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
```

- [ ] **Step 3: Write `Schedulers` (Paper-only for now)**

`Schedulers.java`:
```java
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

    /** Builds the Paper scheduler. The Folia branch is added in the next task. */
    public static Scheduler create(Plugin plugin) {
        return new PaperScheduler(plugin);
    }
}
```

- [ ] **Step 4: Write the failing tests**

`SchedulersTest.java`:
```java
package de.derfakegamer.sentinel.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SchedulersTest {
    @Test void notFoliaUnderTests() {
        assertFalse(Schedulers.isFolia(), "MockBukkit is not Folia, so detection must be false");
    }
}
```

`PaperSchedulerTest.java`:
```java
package de.derfakegamer.sentinel.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperSchedulerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void runGlobalAndEntityExecuteOnTheServerThread() {
        Scheduler s = new PaperScheduler(plugin);
        boolean[] ran = {false, false};
        s.runGlobal(() -> ran[0] = true);
        s.runForEntity(server.addPlayer(), () -> ran[1] = true);
        server.getScheduler().performTicks(1);
        assertTrue(ran[0], "runGlobal task must execute");
        assertTrue(ran[1], "runForEntity task must execute");
    }

    @Test void cancelAllStopsARepeatingTask() {
        Scheduler s = new PaperScheduler(plugin);
        int[] count = {0};
        TaskHandle h = s.globalTimer(() -> count[0]++, 1L, 1L);
        server.getScheduler().performTicks(3);
        int afterRun = count[0];
        assertTrue(afterRun >= 1, "timer must have fired at least once");
        h.cancel();
        server.getScheduler().performTicks(5);
        assertEquals(afterRun, count[0], "cancelled timer must not fire again");
    }

    @Test void schedulerAccessorReturnsPaperOffFolia() {
        assertInstanceOf(PaperScheduler.class, plugin.scheduler(),
            "off Folia, plugin.scheduler() must be the Paper impl");
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew test --no-daemon --tests "*SchedulersTest" --tests "*PaperSchedulerTest"`
Expected: FAIL — `plugin.scheduler()` does not exist yet (compile error in `schedulerAccessorReturnsPaperOffFolia`).

- [ ] **Step 6: Wire the scheduler into `Sentinel`**

In `Sentinel.java`, add the field next to the other manager fields (after the `db` field declaration near line 18):
```java
    private de.derfakegamer.sentinel.scheduler.Scheduler scheduler;
```

In `onEnable`, create it **before** the database block. Insert immediately after `this.secret = new de.derfakegamer.sentinel.manager.SecretMessages(this.messages.prefix());` (line 61) and before the `try {` that opens the database:
```java
        this.scheduler = de.derfakegamer.sentinel.scheduler.Schedulers.create(this);
```

Add the accessor next to the other accessors (e.g. after `public ... DatabaseExecutor db() { return db; }`):
```java
    public de.derfakegamer.sentinel.scheduler.Scheduler scheduler() { return scheduler; }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --no-daemon --tests "*SchedulersTest" --tests "*PaperSchedulerTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/scheduler/ \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/scheduler/
git commit -m "feat: scheduler abstraction (Paper impl + runtime detection)"
```

---

### Task 2: FoliaScheduler

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/scheduler/FoliaScheduler.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/scheduler/Schedulers.java`

**Interfaces:**
- Consumes: `Scheduler`, `TaskHandle`.
- Produces: `FoliaScheduler implements Scheduler`; `Schedulers.create` now returns it when `isFolia()`.

> No unit test: MockBukkit cannot run Folia schedulers. `FoliaScheduler` is thin pure delegation and is verified manually on a real Folia server. The Paper-path tests remain the safety net.

- [ ] **Step 1: Write `FoliaScheduler`**

`FoliaScheduler.java`:
```java
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
```

- [ ] **Step 2: Make `Schedulers.create` pick Folia** — replace the `create` method body:

```java
    public static Scheduler create(Plugin plugin) {
        return FOLIA ? new FoliaScheduler(plugin) : new PaperScheduler(plugin);
    }
```

- [ ] **Step 3: Verify it compiles and the Paper path is unaffected**

Run: `./gradlew test --no-daemon --tests "*SchedulersTest" --tests "*PaperSchedulerTest"`
Expected: PASS (still Paper under MockBukkit; the new class only compiles).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/scheduler/
git commit -m "feat: FoliaScheduler (thin delegation to Folia's regionized schedulers)"
```

---

### Task 3: DB callback routing through the scheduler

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Test: `src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorCallbackTest.java`

**Interfaces:**
- Consumes: `Scheduler` (`runGlobal`, `runForEntity`).
- Produces: `DatabaseExecutor` 5-arg constructor `(Database, Logger, Plugin, Messages, Scheduler)`; `callback(...)` → global, `callbackOrError(Player, ...)` → viewer's entity, new `callbackFor(Entity, future, Consumer)` → entity. With a null scheduler (pure DAO tests) bodies run inline as before.

- [ ] **Step 1: Write the failing test** — `DatabaseExecutorCallbackTest.java`:

```java
package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.CompletableFuture;

class DatabaseExecutorCallbackTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void callbackForRunsBodyForAnEntity() {
        PlayerMock p = server.addPlayer();
        boolean[] ran = {false};
        plugin.db().callbackFor(p, CompletableFuture.completedFuture("x"), v -> ran[0] = "x".equals(v));
        server.getScheduler().performTicks(1);
        assertTrue(ran[0], "callbackFor must deliver the value on the entity's scheduler");
    }

    @Test void callbackRunsGloballyWithoutAViewer() {
        boolean[] ran = {false};
        plugin.db().callback(CompletableFuture.completedFuture(42), v -> ran[0] = (v == 42));
        server.getScheduler().performTicks(1);
        assertTrue(ran[0], "callback must deliver the value globally");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*DatabaseExecutorCallbackTest"`
Expected: FAIL (compile error — `callbackFor` does not exist).

- [ ] **Step 3: Add the scheduler field + 5-arg constructor in `DatabaseExecutor`**

Add the import near the top (with the other imports):
```java
import de.derfakegamer.sentinel.scheduler.Scheduler;
import org.bukkit.entity.Entity;
```

Add the field after the `messages` field:
```java
    /** May be null in pure DAO tests; then callbacks run inline. */
    private final Scheduler scheduler;
```

Replace the two existing constructors with three (keep the old 3/4-arg for tests, default scheduler null):
```java
    public DatabaseExecutor(Database database, Logger logger, Plugin plugin) {
        this(database, logger, plugin, null, null);
    }

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin, Messages messages) {
        this(database, logger, plugin, messages, null);
    }

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin, Messages messages, Scheduler scheduler) {
        this.database = database;
        this.logger = logger;
        this.plugin = plugin;
        this.messages = messages;
        this.scheduler = scheduler;
        this.writer = Executors.newSingleThreadExecutor(namedFactory("Sentinel-DB"));
        this.readers = database.supportsConcurrentReads()
            ? Executors.newFixedThreadPool(READER_THREADS, namedFactory("Sentinel-DB-Reader"))
            : null;
    }
```

- [ ] **Step 4: Rewrite the callback methods** — replace the existing `callback`/`callback`/`callbackOrError` block (lines 133–170) with:

```java
    /** Delivers the result on the GLOBAL region (server-wide side effects). Inline if no scheduler. */
    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (scheduler == null) { onMain.accept(delivered); return; }
            scheduler.runGlobal(() -> onMain.accept(delivered));
        });
    }

    /**
     * Like {@link #callback(CompletableFuture, Consumer)} but routes failures to {@code onError}
     * instead of passing {@code null} to {@code onMain}, logging at SEVERE. Both run on the global region.
     */
    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> {
            Runnable task = (error == null)
                ? () -> onMain.accept(value)
                : () -> {
                    logger.log(Level.SEVERE, "DB operation failed", error);
                    onError.accept(error);
                };
            if (scheduler == null) { task.run(); return; }
            scheduler.runGlobal(task);
        });
    }

    /** Delivers the result on a specific entity's region (player-bound side effects, e.g. opening a GUI). */
    public <T> void callbackFor(Entity entity, CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (scheduler == null) { onMain.accept(delivered); return; }
            if (entity != null) scheduler.runForEntity(entity, () -> onMain.accept(delivered));
            else scheduler.runGlobal(() -> onMain.accept(delivered));
        });
    }

    /**
     * Convenience wrapper: on success runs {@code onSuccess} on the VIEWER's region (so GUI opens land
     * on the right thread); on failure logs SEVERE and sends {@code db-error} to {@code viewer} (if online).
     */
    public <T> void callbackOrError(Player viewer, CompletableFuture<T> future, Consumer<T> onSuccess) {
        future.whenComplete((value, error) -> {
            Runnable task = (error == null)
                ? () -> onSuccess.accept(value)
                : () -> {
                    logger.log(Level.SEVERE, "DB operation failed", error);
                    if (viewer != null && viewer.isOnline() && messages != null)
                        viewer.sendMessage(messages.prefixed("db-error"));
                };
            if (scheduler == null) { task.run(); return; }
            if (viewer != null) scheduler.runForEntity(viewer, task);
            else scheduler.runGlobal(task);
        });
    }
```

- [ ] **Step 5: Pass the scheduler into `DatabaseExecutor` in `Sentinel`** — the construction is currently:

```java
            this.db = new de.derfakegamer.sentinel.storage.DatabaseExecutor(raw, getLogger(), this, this.messages);
```

Change it to pass the scheduler (created earlier in onEnable):
```java
            this.db = new de.derfakegamer.sentinel.storage.DatabaseExecutor(raw, getLogger(), this, this.messages, this.scheduler);
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --no-daemon --tests "*DatabaseExecutorCallbackTest" --tests "*AuditManagerTest" --tests "*AdminPanelGuiTest"`
Expected: PASS (the new test plus existing callback-dependent tests still green on the Paper path).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorCallbackTest.java
git commit -m "feat: route DB callbacks through the scheduler (global vs entity)"
```

---

### Task 4: Lifecycle — onDisable cancelAll, AFK loop, plugin.yml

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Modify: `src/main/resources/plugin.yml`

- [ ] **Step 1: Replace `cancelTasks` in `onDisable`** — the current first line of `onDisable` is:

```java
        getServer().getScheduler().cancelTasks(this);
```

Replace with (null-guarded because disable can run after a failed enable):
```java
        if (scheduler != null) scheduler.cancelAll();
```

- [ ] **Step 2: Move the AFK loop onto the global timer** — the AFK loop in `onEnable` is currently:

```java
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("afk.enabled", true)) return;
            int mins = getConfig().getInt("afk.minutes", 5);
            if (mins <= 0) return;
            long now = System.currentTimeMillis();
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                if (afk().idleMs(p.getUniqueId(), now) > mins * 60_000L && afk().markAfk(p.getUniqueId()))
                    getServer().broadcast(messages().plain("afk-now", "player", p.getName()));
            }
        }, 600L, 600L);
```

Replace the wrapping `getServer().getScheduler().runTaskTimer(this, () -> { … }, 600L, 600L);` with the scheduler's global timer — only the first and last lines change:
```java
        scheduler.globalTimer(() -> {
            if (!getConfig().getBoolean("afk.enabled", true)) return;
            int mins = getConfig().getInt("afk.minutes", 5);
            if (mins <= 0) return;
            long now = System.currentTimeMillis();
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                if (afk().idleMs(p.getUniqueId(), now) > mins * 60_000L && afk().markAfk(p.getUniqueId()))
                    getServer().broadcast(messages().plain("afk-now", "player", p.getName()));
            }
        }, 600L, 600L);
```
(`getServer().broadcast(...)` is a global text send and is safe on the global region thread. The handle is not stored — `cancelAll()` stops it on disable.)

- [ ] **Step 3: Declare Folia support in `plugin.yml`** — add a line near the top (after `api-version: '1.21'`):

```yaml
folia-supported: true
```

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew test --no-daemon`
Expected: PASS (the AFK loop + disable path still work on the Paper scheduler under MockBukkit).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/Sentinel.java src/main/resources/plugin.yml
git commit -m "feat: Folia lifecycle — cancelAll on disable, global AFK timer, folia-supported flag"
```

---

### Task 5: Repeating-timer managers → scheduler timers

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/AuditManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ChatLogManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/AutoAnnouncer.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/CronManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/RestartManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/updater/UpdateChecker.java`

**Interfaces:**
- Consumes: `Sentinel#scheduler()` → `Scheduler.asyncTimer`, `globalTimer`, `runAsync`, `runGlobal`; `TaskHandle`.

- [ ] **Step 1: `AuditManager` flush → async timer** — the constructor line is:

```java
        plugin.getServer().getScheduler().runTaskTimer(plugin, batchWriter::flush, 40L, 40L);
```

Replace with (the flush only touches the DB batch writer, no Bukkit state):
```java
        plugin.scheduler().asyncTimer(batchWriter::flush, 40L, 40L);
```

- [ ] **Step 2: `ChatLogManager` flush → async timer** — the constructor line is:

```java
        plugin.getServer().getScheduler().runTaskTimer(plugin, batchWriter::flush, 40L, 40L);
```

Replace with:
```java
        plugin.scheduler().asyncTimer(batchWriter::flush, 40L, 40L);
```

- [ ] **Step 3: `AutoAnnouncer` → global timer + `TaskHandle`** — change the field type:

```java
    private org.bukkit.scheduler.BukkitTask task;
```
to:
```java
    private de.derfakegamer.sentinel.scheduler.TaskHandle task;
```

Change the `schedule()` body line:
```java
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::announceNext, ticks, ticks);
```
to:
```java
        task = plugin.scheduler().globalTimer(this::announceNext, ticks, ticks);
```
(`stop()` already calls `task.cancel()` — `TaskHandle.cancel()` matches. `announceNext` does `Bukkit.broadcast`, safe on the global region.)

- [ ] **Step 4: `CronManager` → global timer** — the timer is:

```java
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
```

Replace just that opening line with:
```java
        plugin.scheduler().globalTimer(() -> {
```
and the trailing scheduler arguments stay the same (`}, 1200L, 1200L);` becomes the `globalTimer(..., 1200L, 1200L)` closing — i.e. the `}, 1200L, 1200L);` line is unchanged). (`dispatchCommand` runs on the global region; the handle is not stored — `cancelAll()` stops it.)

- [ ] **Step 5: `RestartManager` → global timer + `TaskHandle`** — change the field type:

```java
    private BukkitTask task;
```
to:
```java
    private de.derfakegamer.sentinel.scheduler.TaskHandle task;
```
and remove the now-unused import `import org.bukkit.scheduler.BukkitTask;`.

Change the `schedule(...)` timer line:
```java
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            remaining--;
            if (remaining <= 0) { Bukkit.shutdown(); return; }
            if (isWarnTick(remaining)) warn(remaining);
        }, 20L, 20L);
```
to:
```java
        task = plugin.scheduler().globalTimer(() -> {
            remaining--;
            if (remaining <= 0) { Bukkit.shutdown(); return; }
            if (isWarnTick(remaining)) warn(remaining);
        }, 20L, 20L);
```
(`cancel()` already calls `task.cancel()` — `TaskHandle.cancel()` matches. `Bukkit.shutdown()` and broadcasts run on the global region.)

- [ ] **Step 6: `UpdateChecker` → async timer / async / global notify**

The periodic check (line 48–49):
```java
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
            () -> check(null), 20L, 300L * 20L);
```
Replace with:
```java
        plugin.scheduler().asyncTimer(() -> check(null), 20L, 300L * 20L);
```

The on-demand check (line 55):
```java
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> check(requester));
```
Replace with:
```java
        plugin.scheduler().runAsync(() -> check(requester));
```

The two notify hops (lines 212 and 230) currently use `plugin.getServer().getScheduler().runTask(plugin, () -> …)`; replace the `runTask(plugin, …)` wrapper with `plugin.scheduler().runGlobal(…)` (the bodies only `sendMessage`, which is a safe text send):
- Line ~212: `plugin.getServer().getScheduler().runTask(plugin, () -> {` → `plugin.scheduler().runGlobal(() -> {`
- Line ~230: `plugin.getServer().getScheduler().runTask(plugin,` → `plugin.scheduler().runGlobal(`
  (the lambda argument and closing parenthesis stay; just drop the `plugin, ` first argument.)

- [ ] **Step 7: Run the tests**

Run: `./gradlew test --no-daemon --tests "*AuditManagerTest" --tests "*ChatLog*" --tests "*AutoAnnouncer*" --tests "*Cron*" --tests "*Restart*" --tests "*Update*"`
Expected: PASS (whichever of these have tests; all compile and the Paper scheduler runs them).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/AuditManager.java \
        src/main/java/de/derfakegamer/sentinel/manager/ChatLogManager.java \
        src/main/java/de/derfakegamer/sentinel/manager/AutoAnnouncer.java \
        src/main/java/de/derfakegamer/sentinel/manager/CronManager.java \
        src/main/java/de/derfakegamer/sentinel/manager/RestartManager.java \
        src/main/java/de/derfakegamer/sentinel/updater/UpdateChecker.java
git commit -m "refactor: move repeating tasks onto the scheduler abstraction"
```

---

### Task 6: Region-bound mutations (moderation, profile, vanish, op, chat-input)

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ModerationService.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/VanishManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/ChatListener.java`

**Interfaces:**
- Consumes: `Sentinel#scheduler()` (`runGlobal`, `runForEntity`, `runForEntityLater`), `DatabaseExecutor#callbackFor(Entity, …)`.

- [ ] **Step 1: `ModerationService` — global broadcast + per-entity kick.** Replace the `onMain` helper (lines 21–31) with a global-region helper:

```java
    /**
     * Runs {@code sideEffects} on the GLOBAL region thread and completes the returned future after it.
     * Broadcasts and other server-wide work go here; single-player work is scheduled onto that
     * player's entity scheduler from inside the block (see {@link #apply}).
     */
    private CompletableFuture<Void> onGlobal(Runnable sideEffects) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        plugin.scheduler().runGlobal(() -> {
            try { sideEffects.run(); } finally { f.complete(null); }
        });
        return f;
    }
```

In `apply`, replace the punishment side-effects block (the `return onMain(() -> { Bukkit.broadcast(...); Player online = ...; if (online != null) { switch ... kick/sendMessage } }).thenCompose(...)`) with a version that broadcasts globally and schedules the per-player kick on that player's entity scheduler:

```java
            return onGlobal(() -> {
                Bukkit.broadcast(plugin.messages().prefixed(key, "player", targetName, "reason", reason));

                Player online = Bukkit.getPlayer(targetId);
                if (online != null) {
                    plugin.scheduler().runForEntity(online, () -> {
                        switch (type) {
                            case BAN, IPBAN -> {
                                String url = plugin.getConfig().getString("appeals.url", "");
                                String appealSuffix = url.isBlank() ? "" : "\n\nAppeal at: " + url;
                                online.kick(plugin.messages().plain("ban-screen", "reason", reason, "duration", dur, "appeal", appealSuffix));
                            }
                            case KICK -> online.kick(plugin.messages().plain("kick-screen", "reason", reason));
                            case MUTE -> online.sendMessage(plugin.messages().prefixed("you-were-muted", "reason", reason, "duration", dur));
                            case WARN -> online.sendMessage(plugin.messages().prefixed("you-were-warned", "reason", reason));
                            default -> {}
                        }
                    });
                }
            }).thenCompose(v -> {
```
(The rest of the `.thenCompose(v -> { … WARN escalation … })` body is unchanged. The kick is fire-and-forget on the player's region — the chain does not await it, so a logged-off target cannot hang the future.)

Replace the three other `onMain(` call sites (shadowmute notify at line ~56, unban broadcast at ~112, unmute broadcast at ~124, unshadowmute notify at ~142) with `onGlobal(` — the bodies only broadcast or `notifyStaff` (per-op `sendMessage`, a safe text send), so they belong on the global region. Concretely change each `return onMain(() ->` to `return onGlobal(() ->`.

- [ ] **Step 2: `ProfileManager` — entity-routed callbacks + per-entity resend.**

`setName`: change the delivery from `callback` to `callbackFor(target, …)` so the apply lands on the target's region:
```java
        plugin.db().callbackFor(target, plugin.db().submitWrite(() -> {
```
(the rest of the `setName` lambda body is unchanged.)

`resend` (lines 54–63): each `o.hidePlayer`/`o.showPlayer` mutates the *other* player `o`, so it must run on `o`'s region. Replace the method body with:
```java
    private void resend(org.bukkit.entity.Player target) {
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!o.equals(target)) {
                org.bukkit.entity.Player viewer = o;
                plugin.scheduler().runForEntity(viewer, () -> viewer.hidePlayer(plugin, target));
            }
        }
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!o.equals(target)) {
                org.bukkit.entity.Player viewer = o;
                plugin.scheduler().runForEntityLater(viewer, () -> viewer.showPlayer(plugin, target), 2L);
            }
        }
    }
```

`setSkin` (lines 85–107): replace the `runTaskAsynchronously`/inner `runTask` with the scheduler, resolving the player on the global region then delivering on the target's region:
```java
        plugin.scheduler().runAsync(() -> {
            PlayerProfile src = org.bukkit.Bukkit.createProfile(sourceName);
            boolean ok = src.complete(true);
            ProfileProperty tex = ok ? texturesOf(src) : null;
            plugin.scheduler().runGlobal(() -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null || !t.isOnline() || tex == null) { done.accept(false); return; }
                long now = System.currentTimeMillis();
                plugin.db().callbackFor(t, plugin.db().submitWrite(() -> {
                    de.derfakegamer.sentinel.model.ProfileOverride existing = dao.find(id);
                    String nm = existing != null ? existing.displayName() : null;
                    dao.upsert(new de.derfakegamer.sentinel.model.ProfileOverride(
                        id, nm, tex.getValue(), tex.getSignature(), staff, now));
                    return nm;
                }), nm -> {
                    if (!t.isOnline()) { done.accept(false); return; }
                    applyLive(t, nm, tex.getValue(), tex.getSignature());
                    plugin.audit().record(staff, "SETSKIN", t.getName(), sourceName);
                    done.accept(true);
                });
            });
        });
```

`reset` (lines 114–131): replace the `runTaskAsynchronously`/inner `runTask` similarly, with the profile mutation on the target's region:
```java
        plugin.scheduler().runAsync(() -> {
            PlayerProfile real = org.bukkit.Bukkit.createProfile(id, realName);
            real.complete(true);
            ProfileProperty tex = texturesOf(real);
            plugin.scheduler().runGlobal(() -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null) return;
                plugin.scheduler().runForEntity(t, () -> {
                    PlayerProfile profile = t.getPlayerProfile();
                    profile.setName(realName);
                    profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
                    if (tex != null) profile.setProperty(tex);
                    t.setPlayerProfile(profile);
                    t.playerListName(null);
                    t.displayName(null);
                    resend(t);
                    plugin.audit().record(staff, "RESETPROFILE", realName, "");
                });
            });
        });
```

- [ ] **Step 3: `VanishManager` — per-entity hide/show.** Replace `hideFromNonOps` and `showToAll` (lines 41–48):
```java
    private void hideFromNonOps(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.isOp() && !other.equals(staff)) {
                Player viewer = other;
                plugin.scheduler().runForEntity(viewer, () -> viewer.hidePlayer(plugin, staff));
            }
        }
    }

    private void showToAll(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            Player viewer = other;
            plugin.scheduler().runForEntity(viewer, () -> viewer.showPlayer(plugin, staff));
        }
    }
```
(`applyOnJoin` runs in the join event on the joiner's own region; its `joiner.hidePlayer(...)` touches the joiner and stays as-is. The `hideFromNonOps(joiner)` call there now routes per-other-entity via the method above.)

- [ ] **Step 4: `PlayerActionsGui` — op toggle on the global region.** In the `OPTOGGLE` case, the line `target.setOp(makeOp);` mutates global op state. Wrap it:
```java
                boolean makeOp = !target.isOp();
                plugin.scheduler().runGlobal(() -> target.setOp(makeOp));
```
(Keep the surrounding `mod.sendMessage(...)` and `mod.closeInventory()` — `closeInventory` runs on the clicking moderator's own region, which is where the click handler already executes.)

- [ ] **Step 5: `ChatListener` — GUI input callback on the player's region.** The line:
```java
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(text));
```
Replace with (the callback opens GUIs / mutates the chatting player):
```java
                plugin.scheduler().runForEntity(event.getPlayer(), () -> callback.accept(text));
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --no-daemon --tests "*ModerationService*" --tests "*Moderation*" --tests "*Profile*" --tests "*Vanish*" --tests "*PlayerActions*" --tests "*ChatListener*" --tests "*AdminPanelGuiTest*"`
Expected: PASS (Paper scheduler runs all the hops; pump-tick tests still see the deferred work).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/ModerationService.java \
        src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java \
        src/main/java/de/derfakegamer/sentinel/manager/VanishManager.java \
        src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java \
        src/main/java/de/derfakegamer/sentinel/listener/ChatListener.java
git commit -m "refactor: route region-bound mutations through the scheduler"
```

---

### Task 7: Global-state + async sites (whitelist, AFK-return, backup) + full build

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/OwnerProtectionManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/WhitelistGui.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/ActivityListener.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/BackupManager.java`

- [ ] **Step 1: `LoginListener` — whitelist on the global region.** The line:
```java
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).setWhitelisted(true));
```
Replace with:
```java
            plugin.scheduler().runGlobal(() ->
                org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).setWhitelisted(true));
```

- [ ] **Step 2: `OwnerProtectionManager` — whitelist on the global region.** The line:
```java
                if (autoWhitelist) Bukkit.getScheduler().runTask(plugin, this::whitelistOwnerNow);
```
Replace with:
```java
                if (autoWhitelist) plugin.scheduler().runGlobal(this::whitelistOwnerNow);
```

- [ ] **Step 3: `WhitelistGui` — whitelist on the global region.** At line ~72 the add-callback wraps a `setWhitelisted(true)` in `Bukkit.getScheduler().runTask(plugin, () -> { … })`; change that wrapper to `plugin.scheduler().runGlobal(() -> { … })` (the body, which calls `t.setWhitelisted(true)`, is unchanged). At line ~85, the direct `t.setWhitelisted(false);` runs inside a GUI click handler; wrap it:
```java
            plugin.scheduler().runGlobal(() -> t.setWhitelisted(false));
```
(Keep any surrounding `sendMessage`/`closeInventory` as-is.)

- [ ] **Step 4: `ActivityListener` — AFK-return broadcast on the global region.** The line:
```java
            plugin.getServer().broadcast(plugin.messages().plain("afk-back", "player", p.getName()));
```
Replace with (this method is reached from the async chat event and from move events, so force the broadcast onto the global region):
```java
            plugin.scheduler().runGlobal(() -> plugin.getServer().broadcast(plugin.messages().plain("afk-back", "player", p.getName())));
```

- [ ] **Step 5: `BackupManager` — world save on global, zip on async, notify on global.** Replace the `backup` method body (lines 18–37) with:
```java
    public void backup(CommandSender requester, long stamp) {
        requester.sendMessage(plugin.messages().prefixed("backup-started"));
        plugin.scheduler().runGlobal(() -> {
            List<File> worldDirs = new ArrayList<>();
            for (org.bukkit.World w : Bukkit.getWorlds()) { w.save(); worldDirs.add(w.getWorldFolder()); }
            File dir = new File(plugin.getDataFolder(), "backups");
            dir.mkdirs();
            File zip = new File(dir, "backup-" + stamp + ".zip");
            int keep = plugin.getConfig().getInt("backup.keep", 5);
            plugin.scheduler().runAsync(() -> {
                try {
                    zipWorlds(worldDirs, zip);
                    prune(dir, keep);
                    plugin.scheduler().runGlobal(() ->
                        requester.sendMessage(plugin.messages().prefixed("backup-done", "file", zip.getName())));
                } catch (Exception e) {
                    plugin.scheduler().runGlobal(() ->
                        requester.sendMessage(plugin.messages().prefixed("backup-failed", "error", String.valueOf(e.getMessage()))));
                }
            });
        });
    }
```

- [ ] **Step 6: Full build**

Run: `./gradlew build --no-daemon`
Expected: BUILD SUCCESSFUL (all tests + spotlessCheck + shaded jar). If spotless flags formatting, run `./gradlew spotlessApply --no-daemon` and re-build. Confirm a working-tree grep finds **zero** remaining `getScheduler()` calls in `src/main` outside `scheduler/PaperScheduler.java`:
`grep -rn "getScheduler()" src/main/java | grep -v scheduler/PaperScheduler` → no output.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java \
        src/main/java/de/derfakegamer/sentinel/manager/OwnerProtectionManager.java \
        src/main/java/de/derfakegamer/sentinel/gui/WhitelistGui.java \
        src/main/java/de/derfakegamer/sentinel/listener/ActivityListener.java \
        src/main/java/de/derfakegamer/sentinel/manager/BackupManager.java
git commit -m "refactor: route whitelist, afk-return and backup through the scheduler"
```

---

## Self-Review notes

- **Spec coverage:** scheduler abstraction + Paper impl + detection → Task 1; Folia impl → Task 2; DB callback routing → Task 3; lifecycle (cancelAll, AFK, plugin.yml) → Task 4; repeating timers → Task 5; entity-region mutations → Task 6; global-state + async → Task 7. Every scheduler site from the inventory is assigned.
- **Scope decision honoured:** plain `sendMessage`/`broadcast` text sends are left unscheduled (thread-safe); only scheduler calls + hard region mutations are migrated. This is why `ReportManager` (staff alert is `sendMessage`) needs no change — its callback rides the updated `DatabaseExecutor.callback` → global.
- **No hung futures:** the only awaited entity work (`ModerationService` kick) is fire-and-forget on the entity scheduler from inside a global block, so a logged-off target never stalls the chain.
- **Tests stay green on Paper:** `Schedulers.isFolia()` is false under MockBukkit → `PaperScheduler` everywhere → existing scheduler/DB-callback tests pass unchanged; the full build is the regression gate. The Folia path is verified manually on a real Folia server (documented limitation).
- **Type consistency:** `Scheduler`/`TaskHandle` method names, `DatabaseExecutor.callbackFor(Entity,…)`, and `plugin.scheduler()` are referenced identically across tasks. `RestartManager`/`AutoAnnouncer` task fields change `BukkitTask` → `TaskHandle`, and their existing `task.cancel()` calls match `TaskHandle.cancel()`.
- **Manual Folia checklist (real server):** boot on Folia (no `UnsupportedOperationException`); ban/kick a player; toggle vanish (others stop seeing the staff); set name/skin then observe re-render; `/maintenance` is gone (prior feature) — instead verify `/restart 5` counts down and shuts down; `/backup` completes; auto-announcements fire; AFK broadcast fires.
