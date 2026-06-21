# Performance Optimizations — Design

**Date:** 2026-06-21
**Status:** Approved (design)

## Problem

After the v2.0.0 work the plugin is correct and thread-safe, but several hot paths hit the
database more than necessary, and the single-thread DB executor cannot exploit MySQL's
concurrency. Targets: per-message mute checks, per-message/per-action log inserts, serialized
MySQL reads, and a few missing indexes.

## Goal

Reduce database load and latency on hot paths without weakening correctness or the v2.0.0
network-wide consistency, via: (1) selective in-memory caching, (2) batched log writes, (3)
parallel MySQL reads, (4) better indexes/queries. Every optimization is fail-soft — a cache,
batch, or pool failure falls back to the plain DB path and never disrupts gameplay.

Non-goals: caching login ban checks (correctness-critical, infrequent); HikariCP (a lean
in-house pool is used instead); anti-xray (dropped); changing the SQLite single-connection model.

## 1. Selective caching (TTL + local invalidation)

A generic `util/TtlCache<K,V>` (get-with-loader, per-entry expiry, explicit `invalidate(key)`,
`clear()`), unit-tested.

- **Mute check (per chat message):** `ChatListener` calls `activeMute`/`activeShadowMute` on
  every message. Add a per-player cache (TTL ~3s) consulted by `PunishmentManager`. On
  `mute`/`unmute`/`shadowMute`/`unShadowMute` through `ModerationService`/`PunishmentManager`,
  the target's cache entry is **invalidated immediately** (so the issuing server is always
  correct; other servers are stale at most the TTL — acceptable for chat).
- **Login ban check:** NOT cached — always read fresh from the DB (rare per-join event;
  must never let a banned player in or keep an unbanned player out).
- **Player records (`byUuid`/`byName`):** cache **only for online players** — populated on
  join (`PlayerDirectory.record`/`startSession`), removed on quit. Online players are
  server-local, so there is no cross-server consistency issue. Saves the DB lookups when
  opening GUIs / alts / history for online targets; offline lookups still hit the DB.
- Caches are accelerators only: on miss/expiry/error → the normal async DB path runs.

## 2. Batched writes (chat log + audit)

A `storage/BatchWriter` buffers fire-and-forget inserts and flushes them as one batched
transaction (`PreparedStatement.addBatch`/`executeBatch`) when either a size threshold (~200)
or a time interval (~2s) is reached.

- Applies to `ChatLogManager.logChat`/`logCommand` and `AuditManager.record` (both pure
  fire-and-forget writes).
- **Flush on `onDisable`** before the executor drain, and **flush before any read** of the
  affected log (so `recent(...)` / audit views never miss just-buffered rows).
- Fail-soft: a batch error is logged and the buffer is not retained unbounded — a hard cap
  (e.g. 10 000) drops with a warning in the extreme case rather than growing forever.

## 3. MySQL read parallelism (SQLite unchanged)

The `DatabaseExecutor` is single-threaded because SQLite uses one connection. That stays for
SQLite. For MySQL (which handles concurrent connections):

- A small **reader pool** (e.g. 4 threads, each with its own connection) serves `submit`
  reads, so independent reads (GUI lookups, stats) run in parallel.
- **Writes** (`execute`) stay serialized on a single dedicated writer thread/connection, which
  also keeps the batch writer's ordering stable.
- Backend selection is automatic by `database.type`: SQLite → 1 writer thread + the single
  connection (today's behavior, unchanged); MySQL → 1 writer + a reader pool.
- A lean in-house pool (no HikariCP) to avoid further jar growth.

### Per-task connections (required for the pool)

DAOs currently call `db.connection()` (the one shared connection) inside `synchronized(db)`.
A reader pool makes `synchronized(db)` re-serialize everything, defeating the pool. So:

- The executor provides the **connection for the current task** (e.g. a `ThreadLocal<Connection>`
  bound to the running worker, exposed via `Database.connection()` resolving to the current
  worker's connection). Each task uses one connection for its whole lambda, so atomic
  read-then-write operations remain correct.
- The `synchronized(db)` blocks in the DAOs are **removed** — the executor/pool guarantees a
  connection is never used by two threads at once, and SQLite still runs single-threaded.

This is the largest and riskiest part; it touches every DAO's connection access.

## 4. Indexes, query tuning, misc

- Composite index `punishments(target_uuid, type, active)` for `findActive`, and `(type, active)`
  for `activeList`, added to BOTH dialects via `SqlDialect`. (`SqlDialectTest` extended.)
- Replace `SELECT *` with explicit column lists in the hot DAO reads.
- `warnCount` lookup only performed when warn-escalation is actually configured.
- No new per-tick allocations in hot paths.

## Error handling

All four mechanisms are fail-soft: cache miss/error → DB path; batch error → log + bounded
buffer; pool/connection error → the executor's existing error handling (reads complete
exceptionally → callback null → empty; writes log). Nothing throws into gameplay.

## Testing

- `TtlCacheTest`: expiry, `invalidate`, miss-runs-loader, `clear`.
- Mute-cache test: a second `activeMute` within the TTL does not hit the DB (verify via a
  counting fake or by asserting the cached value after the row is deleted out-of-band);
  `unmute` invalidates immediately.
- Player-record cache test: online player's record served from cache; removed on quit.
- `BatchWriterTest`: flush on size, flush on time, flush on `onDisable`, read-flushes-first,
  error logged + bounded.
- Reader pool: with SQLite, one connection/thread is used (unchanged); the executor exposes
  the current-task connection correctly. MySQL parallelism is covered by a focused test using
  a fake/in-memory backend where feasible; real MySQL stays a manual smoke test.
- All existing DAO/manager tests stay green after the per-task-connection change (the
  `synchronized(db)` removal must not change observable behavior on SQLite).
- `SqlDialectTest` asserts the new composite indexes in both dialects.

## Risks

- **Per-task-connection refactor (Section 3)** is broad — every DAO touched. Mitigation:
  keep `Database.connection()` returning the right connection so DAO bodies barely change
  (only the `synchronized(db)` wrapper is removed); rely on the full existing DAO test suite.
- **Cache staleness** across the network — bounded to the mute-check TTL and explicitly NOT
  applied to login bans; documented.
- **Batch + crash** could lose at most one unflushed buffer window of log rows on a hard
  crash; acceptable for chat/audit logs (not authoritative punishment state, which is written
  immediately by `PunishmentDao`, not batched).
