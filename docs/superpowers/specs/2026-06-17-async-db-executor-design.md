# Async DB Executor & Thread-Safety — Design

**Date:** 2026-06-17
**Status:** Approved (design)
**Author:** Sentinel team

## Problem

`storage/Database.java` holds a **single shared `java.sql.Connection`**. DAO calls happen
both on the Bukkit main thread (punishments, chat log, GUI lookups, etc.) and on the async
login thread (`LoginListener` handles `AsyncPlayerPreLoginEvent`). A `java.sql.Connection`
is **not safe for concurrent use by multiple threads**, so a player joining while a ban or
chat-log write runs on the main thread can hit `SQLITE_BUSY` / `SQLITE_MISUSE` or read
inconsistent state.

Additionally, ~56 DB call sites run synchronously. Synchronous reads/writes on the main
thread (per-message chat-log inserts, history/alts lookups when opening GUIs) block the
server tick and cause TPS drops under load.

## Goal

1. Make all DB access structurally thread-safe.
2. Move blocking DB work off the main thread.
3. Establish a clean DB-access abstraction that makes a future MySQL/network backend easy.

Non-goals: switching to MySQL now; adding a connection pool; refactoring unrelated code.

## Approach (chosen: A — single-thread DB executor)

All DB operations are routed through **one** dedicated background thread. Because every
access is serialized onto that single thread, the shared `Connection` is never touched
concurrently — the race is structurally impossible. This fits SQLite (single-writer at the
file level) and adds no new dependency.

Rejected alternatives:
- **B — HikariCP pool:** little benefit for SQLite (file-level single writer), adds a
  shaded dependency and complexity. Reconsider only when a MySQL backend lands.
- **C — connection-per-operation:** connection churn, loses the WAL/PRAGMA setup.

## Components

### `storage/DatabaseExecutor.java` (new)

Owns the `Database` (and therefore the `Connection`) and runs a single-thread
`ExecutorService`. It is the only object whose thread touches `connection()`.

API:

```
<T> CompletableFuture<T> submit(Callable<T> work)   // reading ops
void execute(Runnable work)                         // fire-and-forget writes
<T> void callback(CompletableFuture<T> f, Consumer<T> onMainThread)
                                                    // deliver result on the Bukkit main thread
void shutdown()                                     // graceful drain on plugin disable
Database database()                                 // accessor for wiring DAOs
```

- The executor thread is created with a named, non-daemon thread for clean shutdown.
- `callback` schedules the consumer via the Bukkit scheduler (`runTask`) so GUI/entity
  mutations happen on the main thread.

### DAOs (unchanged)

DAOs remain **synchronous** and keep taking `Database`. They are only ever invoked from
inside executor tasks, so their existing tests stay valid.

### Managers / call sites (migrated)

Managers obtain the executor (via `plugin.db()` for managers that already take `Sentinel`,
or via constructor injection for managers that take a DAO directly, e.g. `PunishmentManager`).
Each of the ~56 call sites is migrated to exactly one of three patterns.

## The three migration patterns

**Pattern 1 — write, result irrelevant** (chat-log insert, add note, update `last_seen`):
`executor.execute(() -> dao.insert(...))`. Never blocks the main thread.

**Pattern 2 — read on main thread, result feeds a GUI / follow-up** (history/alts on GUI
open, `findActive` in a command): `executor.submit(() -> dao.find(...))` then deliver via
`executor.callback(future, result -> ...)`. Control flow changes from "value returned
immediately" to "value delivered by callback". GUIs may show a brief loading state. This is
the bulk of the work and concentrates in the GUI and command classes.

**Pattern 3 — read already off the main thread** (`AsyncPlayerPreLoginEvent`,
`AsyncChatEvent`): `executor.submit(...).join()`. The caller thread may block because it is
not the main thread; ordering and consistency are preserved.

## Synchronous hot reads (in-memory cache)

A few reads happen inside **synchronous** event handlers that must return a value
immediately and cannot await a callback — notably `OrbitalAccess.isAllowed(...)` and
`OrbitalAccess.code()`, consulted while cancelling events or gating gameplay. Routing these
through the executor and blocking the main thread with `.join()` would reintroduce a tick
stall.

For these small, frequently-read, rarely-written datasets (the orbital allow-list and the
orbital code) the manager keeps an **in-memory cache as the read source of truth**:
- loaded once synchronously at `onEnable` (startup, so a blocking load is acceptable);
- reads (`isAllowed`, `code`) served from memory — no DB touch, stays synchronous;
- writes (`add`, `remove`, `setCode`) update the cache immediately **and** persist to the
  DB via `executor.execute(...)`.

This pattern applies only where a synchronous answer on the main thread is unavoidable.
Large or rarely-read data (history, alts, reports, chat log) uses the Pattern-2 callback
instead. The already-in-memory `exempt` set in `PunishmentManager` follows the same spirit
and needs no change.

## Error handling

- Every executor task catches `SQLException`, logs it with context via the plugin logger,
  and does not swallow it silently.
- Pattern 2: on failure, an empty/default result is delivered to the main thread so GUIs
  never hang.
- Login (Pattern 3): if the ban check fails due to a DB error, **fail open with a warning**
  (player is allowed in) rather than locking everyone out — a DB hiccup must not take the
  server down.

## Lifecycle

- `DatabaseExecutor` is created in `Sentinel#onEnable` immediately after the `Database`
  opens; it takes ownership of the `Database`. All DAO wiring uses `executor.database()`.
- `Sentinel` exposes `db()` returning the executor.
- `onDisable`: call `executor.shutdown()` which does `shutdown()` + `awaitTermination(timeout)`
  to drain pending writes (e.g. chat log), then closes the `Connection`. The existing
  `playerDirectory.flushSessions()` runs before the drain so its writes are queued.

## Testing (TDD)

- New `DatabaseExecutorTest`:
  - concurrent `submit` calls do not corrupt state (serialized execution);
  - `callback` result is delivered on the main thread (verified via MockBukkit scheduler);
  - `shutdown` drains queued work before closing;
  - a task that throws `SQLException` is logged, not propagated, and (for `submit`)
    completes the future exceptionally / with a default.
- Existing DAO tests remain unchanged (DAOs stay synchronous).
- Manager/GUI tests that assumed synchronous return values are updated to the callback
  pattern, ticking the MockBukkit scheduler to flush callbacks.

## Risks

- **Largest risk:** Pattern-2 control-flow conversion in GUIs/commands. Mitigation: migrate
  one call site at a time with its test green before moving on; the implementation plan
  enumerates every site with its assigned pattern.
- Tests that depend on synchronous DB results need scheduler ticking; missing a tick shows
  up as a flaky/empty result and is caught during migration.
