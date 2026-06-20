# Async DB Executor & Thread-Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every SQLite access through one dedicated background thread so the shared `Connection` is never used concurrently, and move blocking DB work off the Bukkit main thread.

**Architecture:** A new `DatabaseExecutor` owns the `Database` and runs a single-thread `ExecutorService`; it is the only thread that touches the `Connection`. DAOs stay synchronous and are only invoked inside executor tasks. The 8 manager classes (the sole DB-access boundary) are migrated: writes become fire-and-forget `execute(...)`, value-returning reads return `CompletableFuture<T>` delivered to callers via callback or `.join()`. The small, synchronously-read orbital allow-list/code move to an in-memory cache.

**Tech Stack:** Java 21, Paper API 1.21.x, JUnit 5, MockBukkit 4.110 (`org.mockbukkit`), SQLite (`org.xerial:sqlite-jdbc`). Build/test: `./gradlew test`.

## Global Constraints

- No new runtime dependencies. Only `java.util.concurrent` + existing Bukkit scheduler.
- DAOs remain synchronous and unchanged; their existing tests must keep passing untouched.
- Only the `DatabaseExecutor` thread may call `Database.connection()` (directly or via a DAO), except the one-time synchronous load at `onEnable`.
- Manager method names stay the same; only return types change (value-returning reads → `CompletableFuture<T>`). In-memory-cache reads (`OrbitalAccess.isAllowed`, `code`) keep synchronous signatures.
- Callbacks that mutate Bukkit state (open GUI, kick player, send message) MUST run on the main thread via `executor.callback(...)`.
- Every executor task catches `Exception`, logs via `plugin.getLogger()`, and never propagates to the executor thread loop.
- Run `./gradlew test` after each task; commit only with a green build.

---

### Task 1: `DatabaseExecutor` core

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java`
- Test: `src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java`

**Interfaces:**
- Consumes: existing `Database` (constructor `Database(File)`, `Connection connection()`, `void close()`).
- Produces:
  - `DatabaseExecutor(Database db, java.util.logging.Logger logger, org.bukkit.plugin.Plugin plugin)`
  - `Database database()`
  - `<T> CompletableFuture<T> submit(Callable<T> work)` — runs `work` on the DB thread; on exception logs and completes exceptionally.
  - `void execute(Runnable work)` — fire-and-forget; on exception logs.
  - `<T> void callback(CompletableFuture<T> future, java.util.function.Consumer<T> onMain)` — when `future` completes, schedules `onMain.accept(value)` on the Bukkit main thread via `plugin.getServer().getScheduler().runTask(plugin, ...)`; on exceptional completion delivers `null`.
  - `void shutdown()` — `executor.shutdown()`, `awaitTermination(5, SECONDS)`, then `database().close()`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseExecutorTest {
    Database db;
    DatabaseExecutor exec;

    @BeforeEach
    void setUp() throws Exception {
        File f = Files.createTempFile("sentinel-exec", ".db").toFile();
        f.deleteOnExit();
        db = new Database(f);
        // plugin == null is fine for tests that never call callback()
        exec = new DatabaseExecutor(db, Logger.getLogger("test"), null);
    }

    @AfterEach
    void tearDown() { exec.shutdown(); }

    @Test
    void submitRunsWorkAndReturnsValue() throws Exception {
        assertEquals(42, exec.submit(() -> 42).get(2, TimeUnit.SECONDS));
    }

    @Test
    void allWorkRunsOnTheSameSingleThread() throws Exception {
        var names = ConcurrentHashMap.newKeySet();
        var futures = new java.util.ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < 50; i++)
            futures.add(exec.submit(() -> Thread.currentThread().getName()));
        for (var fu : futures) names.add(fu.get(2, TimeUnit.SECONDS));
        assertEquals(1, names.size(), "all DB work must run on one thread");
    }

    @Test
    void submitFailureCompletesExceptionallyAndDoesNotKillThread() {
        CompletableFuture<Object> bad = exec.submit(() -> { throw new java.sql.SQLException("boom"); });
        assertThrows(ExecutionException.class, () -> bad.get(2, TimeUnit.SECONDS));
        // thread still alive: a subsequent submit still works
        assertDoesNotThrow(() -> exec.submit(() -> 1).get(2, TimeUnit.SECONDS));
    }

    @Test
    void executeFailureIsSwallowed() throws Exception {
        exec.execute(() -> { throw new RuntimeException("boom"); });
        // queue still processes later work
        assertEquals(7, exec.submit(() -> 7).get(2, TimeUnit.SECONDS));
    }

    @Test
    void shutdownDrainsQueuedWriteBeforeClosing() throws Exception {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 20; i++) exec.execute(counter::incrementAndGet);
        exec.shutdown();
        assertEquals(20, counter.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*DatabaseExecutorTest'`
Expected: FAIL — `DatabaseExecutor` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package de.derfakegamer.sentinel.storage;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-thread owner of the {@link Database}. Every DB operation runs on this one
 * thread, so the shared JDBC Connection is never used concurrently.
 */
public final class DatabaseExecutor {
    private final Database database;
    private final Logger logger;
    private final Plugin plugin;
    private final ExecutorService exec;

    public DatabaseExecutor(Database database, Logger logger, Plugin plugin) {
        this.database = database;
        this.logger = logger;
        this.plugin = plugin;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Sentinel-DB");
            t.setDaemon(false);
            return t;
        });
    }

    public Database database() { return database; }

    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB read failed", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    public void execute(Runnable work) {
        exec.execute(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed", t);
            }
        });
    }

    public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain) {
        future.whenComplete((value, error) -> {
            T delivered = error == null ? value : null;
            if (plugin == null) { onMain.accept(delivered); return; }
            plugin.getServer().getScheduler().runTask(plugin, () -> onMain.accept(delivered));
        });
    }

    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
        }
        try { database.close(); } catch (Exception e) {
            logger.log(Level.WARNING, "closing database failed", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*DatabaseExecutorTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java \
        src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java
git commit -m "feat: add single-thread DatabaseExecutor for thread-safe DB access"
```

---

### Task 2: Wire `DatabaseExecutor` into `Sentinel`

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (fields ~17, `onEnable` 66–99, `onDisable` 176–182, accessors, `reloadAll` 239–246)

**Interfaces:**
- Consumes: `DatabaseExecutor` from Task 1.
- Produces: `Sentinel#db()` returning `DatabaseExecutor`, used by every later task.

This task only changes wiring; the build stays green because DAOs still take a `Database` and managers are unchanged. We swap the field `database` for `db` (the executor) and feed `db.database()` everywhere a `Database` was passed.

- [ ] **Step 1: Replace the `database` field and add the executor**

In `Sentinel.java`, change line 17:

```java
    private de.derfakegamer.sentinel.storage.DatabaseExecutor db;
```

- [ ] **Step 2: Build the executor in `onEnable` and wire DAOs through it**

Replace lines 66–72 (the `try { this.database = new Database(...) }` block) with:

```java
        try {
            Database raw = new Database(new File(getDataFolder(), "sentinel.db"));
            this.db = new de.derfakegamer.sentinel.storage.DatabaseExecutor(raw, getLogger(), this);
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
```

Then, everywhere in `onEnable` a DAO is constructed with `database` (lines 76–99), replace `database` with `db.database()`. Exact replacements:
- `new de.derfakegamer.sentinel.storage.SettingsDao(db.database())`
- `new de.derfakegamer.sentinel.storage.OrbitalAllowDao(db.database())`
- `new de.derfakegamer.sentinel.storage.PlayerDao(db.database())`
- `new de.derfakegamer.sentinel.storage.NoteDao(db.database())`
- `new PunishmentDao(db.database())`
- `new de.derfakegamer.sentinel.storage.ReportDao(db.database())`
- `new de.derfakegamer.sentinel.storage.AppealDao(db.database())`
- `new de.derfakegamer.sentinel.storage.ScheduledStrikeDao(db.database())`
- `new de.derfakegamer.sentinel.storage.ChatLogDao(db.database())`

- [ ] **Step 3: Add the `db()` accessor**

After the `pluginJar()` method (line 237), add:

```java
    public de.derfakegamer.sentinel.storage.DatabaseExecutor db() { return db; }
```

- [ ] **Step 4: Graceful drain in `onDisable`**

Replace lines 179–181 (the `if (database != null) { database.close(); }` block) with:

```java
        if (db != null) {
            try { db.shutdown(); } catch (Exception e) {
                getLogger().warning("database shutdown failed: " + e.getMessage());
            }
        }
```

`playerDirectory.flushSessions()` already runs just before this (line 176–178); after Task 4 its writes are queued, and `db.shutdown()` drains them before closing.

- [ ] **Step 5: Fix `reloadAll`**

In `reloadAll` (line 242), replace `new PunishmentDao(database)` with `new PunishmentDao(db.database())`.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: PASS — no behavior change yet; everything still compiles and runs.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/Sentinel.java
git commit -m "refactor: own Database through DatabaseExecutor, graceful drain on disable"
```

---

### Migration recipe (applies to Tasks 3–10)

For every manager, classify each public DB-touching method and transform it:

- **Write, void return (Pattern 1):** wrap body in `plugin.db().execute(() -> { ...dao... });`. Signature stays `void`. Callers unaffected.
- **Write that returned a value/Result (Pattern 1+):** change return type to `CompletableFuture<T>`, body returns `plugin.db().submit(() -> { ...dao...; return value; })`. In-memory guard checks (e.g. `isExempt`) stay outside the lambda when they need no DB.
- **Read returning a value (Pattern 2/3):** change return type to `CompletableFuture<T>`, body `return plugin.db().submit(() -> ...dao...);`.
- **In-memory-cache read (synchronous):** serve from a field; no executor (OrbitalAccess only).

For each manager whose method gained a `CompletableFuture`, update its callers:
- **Main-thread caller that opens a GUI / sends a message:** `plugin.db().callback(manager.method(...), value -> { ...build & open GUI on main thread... });`
- **Async caller (`AsyncPlayerPreLoginEvent`, `AsyncChatEvent`):** `manager.method(...).join();`

Managers that take a DAO directly (`PunishmentManager`, `PlayerDirectory`, `NoteManager`, `ChatLogManager`) must also receive the executor. Change their constructor to also accept `Sentinel plugin` (or `DatabaseExecutor db`) and store it. Update the construction site in `Sentinel#onEnable` accordingly.

Test updates: where a manager test asserted a synchronous return, change it to `.get(2, SECONDS)` on the future, or for callbacks use MockBukkit's scheduler and run `server.getScheduler().performTicks(1)` to flush, then assert.

---

### Task 3: Migrate `PunishmentManager` + consumers

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java`
- Modify callers: `src/main/java/de/derfakegamer/sentinel/command/PunishmentCommands.java`, `src/main/java/de/derfakegamer/sentinel/manager/ModerationService.java`, `src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java`, `src/main/java/de/derfakegamer/sentinel/listener/ChatListener.java`, `src/main/java/de/derfakegamer/sentinel/gui/HistoryGui.java`, `src/main/java/de/derfakegamer/sentinel/gui/ActiveBansGui.java`, `src/main/java/de/derfakegamer/sentinel/gui/ActiveMutesGui.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java` and related GUI/command tests

**Interfaces:**
- Consumes: `Sentinel#db()`.
- Produces (new signatures):
  - `CompletableFuture<Result> ban/ipBan/mute/warn/kick/shadowMute(...)`
  - `CompletableFuture<Punishment> activeBan/activeMute/activeIpBan/activeShadowMute(...)`
  - `CompletableFuture<Boolean> unban/unmute/unShadowMute(...)`
  - `CompletableFuture<List<Punishment>> activeList(type, now)`, `history(uuid)`
  - `CompletableFuture<Integer> warnCount(uuid)`
  - `boolean isExempt(UUID)` — unchanged (in-memory).

- [ ] **Step 1: Update the failing test first**

In `PunishmentManagerTest.java`, change the constructor call to pass the plugin/executor and adapt assertions to futures. Example for an existing ban-then-find test:

```java
// given a MockBukkit server + Sentinel plugin `plugin` with db() wired
pm.ban(target, "Bob", issuer, "Mod", "spam", 0L).get(2, TimeUnit.SECONDS);
Punishment active = pm.activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS);
assertNotNull(active);
assertEquals("spam", active.reason());
```

(Repeat the `.get(2, SECONDS)` adaptation for each existing assertion in the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PunishmentManagerTest'`
Expected: FAIL — methods still return `Result`/`Punishment`, not `CompletableFuture`.

- [ ] **Step 3: Migrate the manager**

Add a `Sentinel plugin` field + constructor param, and wrap bodies. Concrete transformations:

```java
private final Sentinel plugin;
public PunishmentManager(Sentinel plugin, PunishmentDao dao, Set<UUID> exempt) {
    this.plugin = plugin; this.dao = dao; this.exempt = exempt;
}

public CompletableFuture<Result> kick(UUID target, String targetName, UUID issuer, String issuerName, String reason) {
    if (isExempt(target)) return CompletableFuture.completedFuture(Result.fail("exempt"));
    return plugin.db().submit(() -> {
        dao.insert(Punishment.builder().type(PunishmentType.KICK).targetUuid(target)
            .targetName(targetName).reason(reason).issuerUuid(issuer).issuerName(issuerName)
            .createdAt(System.currentTimeMillis()).expiresAt(0).active(false).build());
        return Result.ok();
    });
}

private CompletableFuture<Result> record(PunishmentType type, UUID target, String targetName, String ip,
        UUID issuer, String issuerName, String reason, long expiresAt) {
    if (isExempt(target)) return CompletableFuture.completedFuture(Result.fail("exempt"));
    return plugin.db().submit(() -> {
        dao.insert(Punishment.builder().type(type).targetUuid(target).targetName(targetName)
            .targetIp(ip).reason(reason).issuerUuid(issuer).issuerName(issuerName)
            .createdAt(System.currentTimeMillis()).expiresAt(expiresAt).active(true).build());
        return Result.ok();
    });
}
// ban/ipBan/mute/warn/shadowMute now `return record(...);` (already returns the future)

public CompletableFuture<Punishment> activeBan(UUID target, long now) {
    return plugin.db().submit(() -> activeOrExpire(PunishmentType.BAN, target, now));
}
// activeMute/activeShadowMute: same, with their type. activeIpBan: wrap its body in submit() too.
// activeOrExpire(...) and the dao.deactivate inside it now run ON the DB thread — keep it PRIVATE and synchronous (no submit inside).

public CompletableFuture<Boolean> unban(UUID target, String remover, long now) {
    return plugin.db().submit(() -> {
        Punishment p = dao.findActive(PunishmentType.BAN, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    });
}
// unmute/unShadowMute: identical shape with their type.

public CompletableFuture<List<Punishment>> activeList(PunishmentType type, long now) {
    return plugin.db().submit(() -> {
        List<Punishment> out = new java.util.ArrayList<>();
        for (Punishment p : dao.findActiveByType(type)) {
            if (p.isExpired(now)) dao.deactivate(p.id(), "SYSTEM", now);
            else out.add(p);
        }
        return out;
    });
}

public CompletableFuture<Integer> warnCount(UUID target) { return plugin.db().submit(() -> dao.countWarns(target)); }
public CompletableFuture<List<Punishment>> history(UUID target) { return plugin.db().submit(() -> dao.findHistory(target)); }
```

Update `Sentinel#onEnable` line 82 and `reloadAll` line 242: `new PunishmentManager(this, new PunishmentDao(db.database()), loadExempt())`.

- [ ] **Step 4: Update callers**

- `LoginListener` (async `AsyncPlayerPreLoginEvent`): replace `plugin.punishments().activeBan(id, now)` with `plugin.punishments().activeBan(id, now).join()` (and the ipBan check). Wrap the whole ban-resolution in `try { ... } catch (Exception e) { plugin.getLogger().warning("ban check failed, allowing: " + e); return; }` — **fail open**.
- `ChatListener` (async `AsyncChatEvent`): replace `activeMute(...)`/`activeShadowMute(...)` with `.join()`.
- `PunishmentCommands` (main thread): for each action, e.g.
  ```java
  plugin.db().callback(plugin.punishments().ban(target, name, issuer, iName, reason, expires), result -> {
      if (result.isSuccess()) sender.sendMessage(...); else sender.sendMessage(...);
  });
  ```
  For `history` (line 104): `plugin.db().callback(pm.history(t.id), entries -> { ...render... });`
- `HistoryGui` (line 35), `ActiveBansGui`, `ActiveMutesGui`: move the fetch out of the constructor. Add a static opener:
  ```java
  public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
      plugin.db().callback(plugin.punishments().history(target.getUniqueId()),
          all -> new HistoryGui(plugin, target, all).open(viewer));
  }
  ```
  Change the constructor to accept the already-fetched `List<Punishment>` instead of fetching. Update the call sites that previously did `new HistoryGui(...).open(p)` to `HistoryGui.open(plugin, target, p)`. (Same shape for `ActiveBansGui` using `activeList(BAN, now)` and `ActiveMutesGui` using `activeList(MUTE, now)`.)

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*Punishment*' --tests '*HistoryGui*' --tests '*ActiveBans*' --tests '*ActiveMutes*' --tests '*LoginListener*' --tests '*ChatListener*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: route PunishmentManager through DatabaseExecutor"
```

---

### Task 4: Migrate `PlayerDirectory` + consumers

**Files:**
- Modify: `manager/PlayerDirectory.java`
- Modify callers: `listener/LoginListener.java` (`record`), `listener/JoinQuitListener.java` (sessions), `gui/SearchResultsGui.java`, `gui/AltsGui.java`, `gui/StatsGui.java`, `gui/OrbitalUsersGui.java`, `gui/PlayersGui.java`, `command/PlaytimeCommand.java`, `command/PunishmentCommands.java:159`
- Test: `manager/PlayerDirectoryTest.java`, `storage/PlaytimeDaoTest.java` (DAO test stays sync — do not change), GUI tests

**Interfaces:**
- Produces:
  - `void record(uuid, name, ip)` — Pattern 1 (`execute`), unchanged signature.
  - `void startSession(uuid)` (in-memory, unchanged), `void endSession(uuid)` — Pattern 1, wraps the `dao.addPlaytime` write in `execute`.
  - `void flushSessions()` — iterates and calls `execute` per session (queued, drained on shutdown).
  - `CompletableFuture<Long> playtime(uuid)`, `CompletableFuture<List<PlayerRecord>> topByPlaytime(int)`, `CompletableFuture<PlayerRecord> byUuid/byName(...)`, `CompletableFuture<List<PlayerRecord>> alts(uuid)`.

- [ ] **Step 1: Update tests to futures**

In `PlayerDirectoryTest.java`, adapt: `assertEquals(rec, dir.byName("Bob").get(2, TimeUnit.SECONDS));` and similar for `alts`, `playtime`, `topByPlaytime`. Pass the wired plugin/executor to the constructor.

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew test --tests '*PlayerDirectoryTest'`
Expected: FAIL (type mismatch / missing constructor arg).

- [ ] **Step 3: Migrate the manager**

```java
private final Sentinel plugin;
public PlayerDirectory(Sentinel plugin, PlayerDao dao) { this.plugin = plugin; this.dao = dao; }

public void endSession(UUID uuid) {
    Long start = sessions.remove(uuid);
    if (start != null) {
        long elapsed = System.currentTimeMillis() - start;
        plugin.db().execute(() -> dao.addPlaytime(uuid, elapsed));
    }
}
public void record(UUID uuid, String name, String ip) {
    long now = System.currentTimeMillis();
    plugin.db().execute(() -> dao.upsert(uuid, name, ip, now));
}
public CompletableFuture<Long> playtime(UUID uuid) { return plugin.db().submit(() -> dao.playtime(uuid)); }
public CompletableFuture<List<PlayerRecord>> topByPlaytime(int limit) { return plugin.db().submit(() -> dao.topByPlaytime(limit)); }
public CompletableFuture<PlayerRecord> byUuid(UUID uuid) { return plugin.db().submit(() -> dao.byUuid(uuid)); }
public CompletableFuture<PlayerRecord> byName(String name) { return plugin.db().submit(() -> dao.byName(name)); }
public CompletableFuture<List<PlayerRecord>> alts(UUID uuid) {
    return plugin.db().submit(() -> {
        PlayerRecord self = dao.byUuid(uuid);
        if (self == null || self.lastIp() == null) return List.<PlayerRecord>of();
        List<PlayerRecord> out = new java.util.ArrayList<>();
        for (PlayerRecord r : dao.byIp(self.lastIp())) if (!r.uuid().equals(uuid)) out.add(r);
        return out;
    });
}
```

Update `Sentinel#onEnable` line 78–79: `new PlayerDirectory(this, new PlayerDao(db.database()))`.

- [ ] **Step 4: Update callers**

- `LoginListener.record(...)` (async): `plugin.players().record(...)` — now fire-and-forget, no change needed at call site (still `void`).
- `JoinQuitListener`: `startSession`/`endSession` unchanged signatures — no change.
- GUIs (`SearchResultsGui`, `AltsGui`, `StatsGui`, `OrbitalUsersGui`): convert to the static-opener + callback pattern (as in Task 3 Step 4). E.g. `AltsGui`:
  ```java
  public static void open(Sentinel plugin, OfflinePlayer target, Player viewer) {
      plugin.db().callback(plugin.players().alts(target.getUniqueId()),
          alts -> new AltsGui(plugin, target, alts).open(viewer));
  }
  ```
- `PlaytimeCommand`: `plugin.db().callback(plugin.players().playtime(id), ms -> sender.sendMessage(...));`
- `PunishmentCommands:159` (`byName`): `plugin.db().callback(plugin.players().byName(name), rec -> { ... });`

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*PlayerDirectory*' --tests '*Alts*' --tests '*SearchResults*' --tests '*Stats*' --tests '*Playtime*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: route PlayerDirectory through DatabaseExecutor"
```

---

### Task 5: Migrate `NoteManager` + `NotesGui`

**Files:** Modify `manager/NoteManager.java`, `gui/NotesGui.java`; Test `manager/` note tests + `gui/NotesGuiTest.java`.

**Interfaces:** `void add(target, author, text)` (Pattern 1 `execute`); `CompletableFuture<List<Note>> list(target)`.

- [ ] **Step 1: Update test** — `notes.list(id).get(2, SECONDS)` assertions; pass plugin to constructor.
- [ ] **Step 2: Run, expect FAIL.** `./gradlew test --tests '*NotesGui*' --tests '*Note*'`
- [ ] **Step 3: Migrate:**
```java
private final Sentinel plugin;
public NoteManager(Sentinel plugin, NoteDao dao) { this.plugin = plugin; this.dao = dao; }
public void add(UUID target, String author, String text) {
    long now = System.currentTimeMillis();
    plugin.db().execute(() -> dao.insert(new Note(0, target, author, text, now)));
}
public CompletableFuture<List<Note>> list(UUID target) { return plugin.db().submit(() -> dao.listFor(target)); }
```
Update `Sentinel#onEnable` line 80–81: `new NoteManager(this, new NoteDao(db.database()))`. Convert `NotesGui` to static-opener + callback over `list(...)`.
- [ ] **Step 4: Run tests, expect PASS.**
- [ ] **Step 5: Commit** — `git commit -am "refactor: route NoteManager through DatabaseExecutor"`

---

### Task 6: Migrate `ReportManager` + consumers

**Files:** Modify `manager/ReportManager.java`, `gui/ReportsGui.java`, `command/ReportCommand.java`; Test `manager/ReportManagerTest.java`, `gui/ReportsGuiTest.java`.

**Interfaces:** `CompletableFuture<Boolean> file(...)`; `CompletableFuture<List<Report>> open()`; `void handle(id, staffName)` (Pattern 1 `execute`). `ReportManager` already takes `Sentinel plugin` — no constructor change.

- [ ] **Step 1: Update tests to futures.**
- [ ] **Step 2: Run, expect FAIL.** `./gradlew test --tests '*Report*'`
- [ ] **Step 3: Migrate:**
```java
public CompletableFuture<Boolean> file(CommandSender reporter, UUID targetId, String targetName, String reason) {
    UUID reporterId = reporter instanceof org.bukkit.entity.Player p ? p.getUniqueId() : new UUID(0,0);
    return plugin.db().submit(() -> {
        dao.insert(new Report(0, reporterId, reporter.getName(), targetId, targetName, reason,
            System.currentTimeMillis(), false, null));
        return true;
    });
}
public CompletableFuture<List<Report>> open() { return plugin.db().submit(() -> dao.findOpen()); }
public void handle(long id, String staffName) { plugin.db().execute(() -> dao.markHandled(id, staffName)); }
```
(Keep the existing reporterId-derivation logic exactly as currently written in the file; the snippet shows the shape.) Convert `ReportsGui` to static-opener + callback over `open()`; in `ReportCommand` use `plugin.db().callback(plugin.reports().file(...), ok -> sender.sendMessage(...));`.
- [ ] **Step 4: Run tests, expect PASS.**
- [ ] **Step 5: Commit** — `git commit -am "refactor: route ReportManager through DatabaseExecutor"`

---

### Task 7: Migrate `AppealManager` + consumers

**Files:** Modify `manager/AppealManager.java`, `gui/AppealsGui.java`, `command/AppealCommand.java`; Test `manager/AppealManagerTest.java`, `gui/AppealsGuiTest.java`.

**Interfaces:** `CompletableFuture<Boolean> hasOpen(uuid)`, `CompletableFuture<List<Appeal>> open()`, `CompletableFuture<Boolean> submit(...)`, `void accept(Appeal, staff, now)` + `void deny(...)` (Pattern 1 `execute`). Already takes `Sentinel plugin`.

Note: `submit` does a read (`hasOpenForTarget`) then a write — keep both inside ONE `db().submit(...)` lambda so the check+insert run atomically on the DB thread:
```java
public CompletableFuture<Boolean> submit(UUID uuid, String name, long punishmentId, PunishmentType type, String text, long now) {
    return plugin.db().submit(() -> {
        if (dao.hasOpenForTarget(uuid)) return false;
        dao.insert(new Appeal(0, punishmentId, uuid, name, type, text, "OPEN", now, null, 0));
        return true;
    });
}
public CompletableFuture<Boolean> hasOpen(UUID uuid) { return plugin.db().submit(() -> dao.hasOpenForTarget(uuid)); }
public CompletableFuture<List<Appeal>> open() { return plugin.db().submit(() -> dao.findOpen()); }
public void accept(Appeal a, String staff, long now) {
    plugin.db().execute(() -> { /* keep existing body (e.g. unban) */ dao.setStatus(a.id(), "ACCEPTED", staff, now); });
}
public void deny(Appeal a, String staff, long now) { plugin.db().execute(() -> dao.setStatus(a.id(), "DENIED", staff, now)); }
```
If `accept` currently calls `plugin.punishments().unban(...)` (now a future), chain it: keep the unban + setStatus inside the same `execute` lambda by calling the DAO directly is not possible across managers — instead do `plugin.punishments().unban(...).thenRun(() -> plugin.db().execute(() -> dao.setStatus(...)))`. Check the actual current body when implementing.

- [ ] **Step 1: Update tests to futures.**
- [ ] **Step 2: Run, expect FAIL.** `./gradlew test --tests '*Appeal*'`
- [ ] **Step 3: Migrate (above); convert `AppealsGui` to static-opener+callback over `open()`; `AppealCommand` uses `callback` over `submit(...)`/`hasOpen(...)`.**
- [ ] **Step 4: Run tests, expect PASS.**
- [ ] **Step 5: Commit** — `git commit -am "refactor: route AppealManager through DatabaseExecutor"`

---

### Task 8: Migrate `ChatLogManager` + consumers

**Files:** Modify `manager/ChatLogManager.java`, `gui/ChatLogGui.java`, `listener/ChatListener.java`; Test `manager/` chatlog tests, `gui/ChatLogGuiTest.java`.

**Interfaces:** `void logChat(...)`, `void logCommand(...)` (Pattern 1 `execute` — the hot per-message path); `CompletableFuture<List<ChatLogEntry>> recent(uuid, limit)`; `CompletableFuture<Integer> prune(retentionDays)`.

```java
private final Sentinel plugin;
public ChatLogManager(Sentinel plugin, ChatLogDao dao) { this.plugin = plugin; this.dao = dao; }
public void logChat(UUID uuid, String name, String text) {
    long now = System.currentTimeMillis();
    plugin.db().execute(() -> dao.log(uuid, name, "CHAT", text, now));
}
public void logCommand(UUID uuid, String name, String cmd) {
    long now = System.currentTimeMillis();
    plugin.db().execute(() -> dao.log(uuid, name, "COMMAND", cmd, now));
}
public CompletableFuture<List<ChatLogEntry>> recent(UUID uuid, int limit) { return plugin.db().submit(() -> dao.recent(uuid, limit)); }
public CompletableFuture<Integer> prune(int retentionDays) {
    long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L; // keep existing cutoff math
    return plugin.db().submit(() -> dao.deleteOlderThan(cutoff));
}
```
Update `Sentinel#onEnable` line 98–99: `new ChatLogManager(this, new ChatLogDao(db.database()))`. The prune call at line 100 becomes fire-and-forget: `this.chatLogManager.prune(getConfig().getInt("logging.retention-days", 30));` (ignore the returned future). Convert `ChatLogGui` to static-opener + callback over `recent(...)`.

- [ ] **Step 1–5:** same TDD cycle; tests `./gradlew test --tests '*ChatLog*' --tests '*ChatListener*'`; commit `"refactor: route ChatLogManager through DatabaseExecutor"`.

---

### Task 9: Migrate `OrbitalAccess` (in-memory cache for sync reads)

**Files:** Modify `manager/OrbitalAccess.java`; consumers `gui/OrbitalUsersGui.java`, `command/OrbitalStrikeCommand.java`, `listener/OrbitalAccessListener.java`; Test `manager/OrbitalAccessTest.java`.

**Interfaces (synchronous, served from cache):**
- `boolean isAllowed(Player)`, `boolean isAllowed(UUID)` — read from in-memory set.
- `String code()` — read from in-memory field.
- `void setCode(String)`, `void add(UUID, String)`, `void remove(UUID)` — update cache **and** `execute(...)` to persist.
- `Map<UUID,String> list()` — return a copy of the cache.

- [ ] **Step 1: Update test** to assert cache+persistence: after `add`, `isAllowed` is immediately true (sync), and a fresh `OrbitalAccess` built on the same DB (after ticking the executor) also sees it.
- [ ] **Step 2: Run, expect FAIL.** `./gradlew test --tests '*OrbitalAccess*'`
- [ ] **Step 3: Migrate:**
```java
private final java.util.Map<UUID,String> allowed = new java.util.concurrent.ConcurrentHashMap<>();
private volatile String code;

public OrbitalAccess(Sentinel plugin, SettingsDao settings, OrbitalAllowDao allow) {
    this.plugin = plugin; this.settings = settings; this.allow = allow;
    // one-time synchronous load at construction (onEnable) — acceptable at startup
    this.code = settings.get(CODE_KEY, DEFAULT_CODE);
    this.allowed.putAll(allow.all());
}
public String code() { return code; }
public void setCode(String c) { this.code = c; plugin.db().execute(() -> settings.set(CODE_KEY, c)); }
public boolean isAllowed(Player p) { return plugin.owner().isOwner(p) || allowed.containsKey(p.getUniqueId()); }
public boolean isAllowed(UUID uuid) { return allowed.containsKey(uuid); }
public void add(UUID uuid, String name) { allowed.put(uuid, name); plugin.db().execute(() -> allow.add(uuid, name)); }
public void remove(UUID uuid) { allowed.remove(uuid); plugin.db().execute(() -> allow.remove(uuid)); }
public Map<UUID,String> list() { return new java.util.HashMap<>(allowed); }
```
`OrbitalAccess` is constructed at `Sentinel#onEnable` lines 75–77 — the DAOs there now use `db.database()`; the synchronous `settings.get`/`allow.all()` in the constructor run on the main thread at enable, which is fine. Consumers of `list()`/`isAllowed`/`code` need no change (signatures unchanged).
- [ ] **Step 4: Run tests, expect PASS.** `./gradlew test --tests '*Orbital*'`
- [ ] **Step 5: Commit** — `git commit -am "refactor: OrbitalAccess in-memory cache, async persistence"`

---

### Task 10: Migrate `ScheduledStrikeManager`

**Files:** Modify `manager/ScheduledStrikeManager.java`; consumer `gui/ScheduledStrikesGui.java`; Test `manager/ScheduledStrikeManagerTest.java` (if present) / `storage/ScheduledStrikeDaoTest.java` (DAO stays sync).

**Interfaces:** `schedule(...)` does insert then `arm` (a scheduler call). `rearmAll()` runs at enable. `pending()` → `CompletableFuture<List<ScheduledStrike>>`. `cancel(id)` → `CompletableFuture<Boolean>`.

Caution: `schedule` returns an id used to `arm` a Bukkit task. Keep arm on the main thread:
```java
public CompletableFuture<Long> schedule(World world, int x, int z, OrbitalPayload payload, long fireAt) {
    return plugin.db().submit(() -> dao.insert(world.getName(), x, z, payload.name(), fireAt));
    // caller arms via callback, OR keep arm() inside a .thenAccept on the main thread
}
public CompletableFuture<List<ScheduledStrike>> pending() { return plugin.db().submit(() -> dao.pending()); }
public CompletableFuture<Boolean> cancel(long id) { return plugin.db().submit(() -> dao.delete(id) > 0); }
```
`rearmAll()` is called once at `onEnable` (line 97) before the server is ticking heavily — it may keep a synchronous read via `plugin.db().submit(() -> dao.pending()).join()` followed by arming each on the main thread, OR convert to `callback`. Use `callback` to avoid blocking enable:
```java
public void rearmAll() { plugin.db().callback(plugin.db().submit(() -> dao.pending()), list -> list.forEach(this::arm)); }
```
The `arm` task's fire body that calls `dao.delete(s.id())` (line 71) must be wrapped: `plugin.db().execute(() -> dao.delete(s.id()));`. Convert `ScheduledStrikesGui` to static-opener + callback over `pending()`; `cancel` via callback.

- [ ] **Step 1–5:** TDD cycle; `./gradlew test --tests '*Scheduled*' --tests '*Orbital*'`; commit `"refactor: route ScheduledStrikeManager through DatabaseExecutor"`.

---

### Task 11: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew test`
Expected: PASS — entire suite green.

- [ ] **Step 2: Grep for any remaining direct connection use off the executor**

Run: `grep -rn "\.connection()" src/main/java | grep -v storage/`
Expected: no matches (only DAOs/Database reference the connection, and they run inside executor tasks).

- [ ] **Step 3: Build the plugin jar**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke test (server)**

On a Paper 1.21 test server: join, get banned by a second account's command, reconnect (kick on login works), open History/Alts/Reports/Appeals/ChatLog GUIs (data loads without freezing), `/stop` and confirm no `SQLITE_BUSY` and chat-log writes flushed.

- [ ] **Step 5: Final commit (if any cleanup)**

```bash
git commit -am "test: full async-db verification pass" --allow-empty
```

---

## Self-Review

- **Spec coverage:** DatabaseExecutor (Task 1), wiring/lifecycle/drain (Task 2), three patterns (recipe + Tasks 3–8,10), in-memory cache for sync reads (Task 9), fail-open login (Task 3 Step 4), error logging (Task 1), tests including scheduler ticking (each task). All spec sections map to a task.
- **Placeholder scan:** Tasks reference real method names and show concrete bodies. Where a current method body has logic not fully shown (AppealManager.accept's possible unban, ReportManager.file's reporterId), the plan flags "keep existing body" and shows the wrapping shape — the implementer reads the actual current body. No "TBD/TODO".
- **Type consistency:** `db()` returns `DatabaseExecutor`; `submit→CompletableFuture<T>`, `execute→void`, `callback(future,Consumer)` used consistently across all tasks.
