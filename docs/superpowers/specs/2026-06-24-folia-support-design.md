# Folia Compatibility (Paper + Folia in one jar) — Design

**Date:** 2026-06-24
**Status:** Approved (design)

## Problem

Sentinel uses the classic `BukkitScheduler` (`Bukkit.getScheduler()`) in 26 call sites across 16
files, and routes every async DB callback back to "the main thread". On Folia the main thread is
split into per-region threads + a global-region thread + an async scheduler; `BukkitScheduler`
throws `UnsupportedOperationException`, and any player/world/entity access must run on the correct
region thread. The plugin therefore cannot run on Folia today.

## Goal

Make a **single jar** that runs correctly on both **Paper/Spigot** and **Folia**, choosing the
right scheduling at runtime. No behaviour change on Paper. Declare `folia-supported: true`.

Non-goals: dropping Paper support (Folia-only was rejected); changing the DB executor's own
threading model; adding a third-party scheduler library (a hand-rolled adapter was chosen over
FoliaLib to keep the minimal-dependency philosophy).

## Decisions

- **Dual-platform via runtime detection** (chosen over Folia-only).
- **Hand-rolled scheduler adapter** (chosen over the FoliaLib dependency) — no new dependency,
  consistent with the plugin's small-jar ethos.
- The `FoliaScheduler` implementation stays **thin** (pure delegation) because it cannot be
  exercised under MockBukkit; it is verified manually on a real Folia server.

## 1. Scheduler abstraction (new `scheduler` package)

New package `de.derfakegamer.sentinel.scheduler`:

### `Scheduler` interface

Methods, grouped by thread domain:

- **Global region** (server-wide state: broadcast, shutdown, dispatchCommand, whitelist):
  - `void runGlobal(Runnable task)`
  - `void runGlobalLater(Runnable task, long delayTicks)`
  - `TaskHandle globalTimer(Runnable task, long delayTicks, long periodTicks)`
- **Entity region** (anything touching one player/entity: sendMessage, kick, setOp, teleport,
  openInventory, hide/show, setPlayerProfile):
  - `void runForEntity(org.bukkit.entity.Entity entity, Runnable task)`
  - `void runForEntityLater(org.bukkit.entity.Entity entity, Runnable task, long delayTicks)`
- **Async** (no Bukkit state: HTTP, DB I/O):
  - `void runAsync(Runnable task)`
  - `TaskHandle asyncTimer(Runnable task, long delayTicks, long periodTicks)`
- **Lifecycle:**
  - `void cancelAll()`

`TaskHandle` is a tiny interface with `void cancel()`, so managers that own a repeating task can
stop it individually (e.g. `RestartManager` cancelling its countdown). `cancelAll()` stops every
task owned by the plugin on disable.

> Tick semantics: Paper uses ticks for all timers. Folia's async scheduler uses real time units;
> the `FoliaScheduler` converts `periodTicks` to milliseconds (`ticks * 50`) for the async
> scheduler, and uses ticks directly for global/entity schedulers.

### `PaperScheduler implements Scheduler`

Delegates to `plugin.getServer().getScheduler()`:
- `runGlobal`/`runForEntity` → `runTask(plugin, task)` (one main thread; entity == global here).
- `runGlobalLater`/`runForEntityLater` → `runTaskLater(plugin, task, delayTicks)`.
- `globalTimer` → `runTaskTimer(...)` wrapped in a `TaskHandle` over the `BukkitTask`.
- `runAsync` → `runTaskAsynchronously`; `asyncTimer` → `runTaskTimerAsynchronously`.
- `cancelAll` → `getScheduler().cancelTasks(plugin)`.

### `FoliaScheduler implements Scheduler`

Delegates to Folia's schedulers (thin):
- `runGlobal` → `Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run())`.
- `runGlobalLater` → `getGlobalRegionScheduler().runDelayed(plugin, t -> …, delayTicks)`
  (delay must be ≥ 1; a 0 delay is bumped to 1).
- `globalTimer` → `getGlobalRegionScheduler().runAtFixedRate(plugin, t -> …, max(1,delay), period)`,
  wrapped in a `TaskHandle` over the returned `ScheduledTask`.
- `runForEntity` → `entity.getScheduler().run(plugin, t -> task.run(), null)` — the call returns
  `null` if the entity has been removed; that is treated as a no-op (the task is simply skipped).
- `runForEntityLater` → `entity.getScheduler().runDelayed(plugin, t -> …, null, max(1,delayTicks))`.
- `runAsync` → `getAsyncScheduler().runNow(plugin, t -> task.run())`.
- `asyncTimer` → `getAsyncScheduler().runAtFixedRate(plugin, t -> …, delayMs, periodMs,
  TimeUnit.MILLISECONDS)` (ticks × 50).
- `cancelAll` → `getGlobalRegionScheduler().cancelTasks(plugin)` and
  `getAsyncScheduler().cancelTasks(plugin)` (entity tasks die with their entity / are short-lived).

### `Schedulers.detect(Sentinel)`

Static factory: returns `new FoliaScheduler(plugin)` when
`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` succeeds, else
`new PaperScheduler(plugin)`. A static `boolean isFolia()` helper memoises the class check.

`Sentinel` gains a `Scheduler scheduler` field set as the **first** thing in `onEnable`
(before any manager that schedules), exposed via `plugin.scheduler()`.

## 2. DB callback routing (`DatabaseExecutor`)

The callback hop currently uses `runTask`. It needs a thread context:

- `callbackOrError(Player viewer, future, onSuccess)` — already carries the viewer; routes both the
  success consumer and the error `sendMessage` through `scheduler.runForEntity(viewer, …)`. Covers
  the GUI-heavy paths. (If `viewer` is offline at delivery, the success/error body is skipped, as
  today via the `isOnline()` guard.)
- `callback(future, onMain)` and `callback(future, onMain, onError)` — no player context; route
  through `scheduler.runGlobal(…)` (server-wide side effects).
- New `callbackFor(org.bukkit.entity.Entity entity, future, onMain)` — for player-bound callbacks
  that lack a `viewer` argument today; routes through `scheduler.runForEntity(entity, …)`.

Each existing `callback`/`callbackOrError` call site is audited during migration and pointed at the
variant matching what its body touches (global vs a specific entity). The `DatabaseExecutor` keeps
its own writer-thread / reader-pool model unchanged — only the *delivery hop* changes. When
`plugin == null` (pure DAO unit tests), the body still runs inline as today.

## 3. Migration of the 26 scheduler sites (by category)

**Entity region** (`runForEntity`): `ProfileManager` (resend hide/show at +2t, `setPlayerProfile`,
`setSkin`/`reset` async→entity second hop), `VanishManager` (hide/show iteration — each target on
its own entity scheduler), `PlayerActionsGui` setOp, `UpdateChecker` requester/online-player
notifies, `ChatListener` GUI-input callback, every DB callback whose body touches a single player.

**Global region** (`runGlobal`/`globalTimer`): the AFK loop in `Sentinel.onEnable`, `AutoAnnouncer`,
`RestartManager` (broadcasts + `Bukkit.shutdown()`), `CronManager` (`dispatchCommand`),
`ActivityListener` AFK-return broadcast, whitelist writes (`LoginListener` pre-login hop,
`WhitelistGui`, `OwnerProtectionManager`), `ModerationService` broadcasts.

**Mixed** (`ModerationService` `onMain`): split — `Bukkit.broadcast(...)` → `runGlobal`; the
single-target `online.kick(...)` → `runForEntity(online, …)`.

**Async** (`runAsync`/`asyncTimer`): `UpdateChecker` HTTP poll + on-demand check, `BackupManager`
zip, `AuditManager` flush timer, `ChatLogManager` flush timer. Each async task's follow-up notify
hops to the right entity/global scheduler.

**Iterating online players with mutation** (`ProfileManager.resend`, `VanishManager`,
`ReportManager` staff alert, `ModerationService` broadcasts to all): on Folia each *per-player*
mutation must run on that player's entity scheduler — loop and `runForEntity(p, …)` per player,
rather than touching them all from one thread. Read-only iteration (e.g. the AFK loop reading idle
state) may stay on the global scheduler; only the per-player *mutation* (broadcast/sendMessage)
hops per entity.

## 4. Lifecycle

- Managers holding repeating tasks (`AuditManager`, `ChatLogManager`, `AutoAnnouncer`,
  `CronManager`, `UpdateChecker`, `RestartManager`, the AFK loop in `Sentinel`) store the
  `TaskHandle` returned by the adapter (replacing the stored `BukkitTask`), and cancel it where they
  cancel today (e.g. `RestartManager.cancel()`).
- `Sentinel.onDisable` calls `plugin.scheduler().cancelAll()` **instead of**
  `getServer().getScheduler().cancelTasks(this)` (the latter throws on Folia). The existing
  `playerDirectory`/`chatLogManager`/`auditManager` flushes and `db.shutdown()` stay.
- `plugin.yml` gains `folia-supported: true`.

## 5. Testing

- `Schedulers.detect` returns `PaperScheduler` under MockBukkit (no Folia class on the test
  classpath), so **all existing tests run unchanged** through the Paper path (MockBukkit implements
  `BukkitScheduler`). This is the safety net for the whole migration.
- New unit tests: `Schedulers.isFolia()` is `false` without the Folia class; `PaperScheduler`
  actually schedules on the `BukkitScheduler` (assert a `runGlobal`/`runForEntity`/`runAsync` task
  executes after `server.getScheduler().performTicks(...)`); `cancelAll()` cancels a repeating
  task (assert it stops firing after cancel).
- `FoliaScheduler` is deliberately thin (pure delegation) and **cannot** be unit-tested under
  MockBukkit — it is verified **manually on a real Folia server** (documented as a manual check).
- After migration, the full `./gradlew build` (all tests + spotlessCheck + shaded jar) must be
  GREEN — proving the Paper path is unbroken.

## 6. Risks (honest)

- The Folia path is not automatically testable → manual verification on a Folia server is required.
- Region-threading mistakes surface only at runtime on Folia (`IllegalStateException` for
  wrong-thread access). The categorical migration (every site consciously sorted into
  global/entity/async) is the mitigation.
- Large surface (~16 files). The implementation plan slices it into coherent tasks: adapter core →
  DB callback routing → per-area migration (lifecycle timers, moderation, profile/vanish,
  whitelist/owner, restart/cron/backup/announcer/afk/update-checker) → plugin.yml.

## 7. Files touched (summary)

- **New:** `scheduler/Scheduler`, `scheduler/TaskHandle`, `scheduler/PaperScheduler`,
  `scheduler/FoliaScheduler`, `scheduler/Schedulers`; tests `SchedulersTest`, `PaperSchedulerTest`.
- **Changed:** `Sentinel` (field + accessor + onEnable init + onDisable cancelAll + AFK loop),
  `storage/DatabaseExecutor` (callback routing), `manager/ModerationService`,
  `manager/ProfileManager`, `manager/VanishManager`, `manager/ReportManager`,
  `manager/AuditManager`, `manager/ChatLogManager`, `manager/AutoAnnouncer`,
  `manager/CronManager`, `manager/RestartManager`, `manager/BackupManager`,
  `manager/OwnerProtectionManager`, `updater/UpdateChecker`, `listener/LoginListener`,
  `listener/ChatListener`, `listener/ActivityListener`, `gui/WhitelistGui`,
  `gui/PlayerActionsGui`, `resources/plugin.yml`.
- **Unchanged:** DAOs, `DatabaseExecutor`'s own executor/reader-pool model, config/messages.
