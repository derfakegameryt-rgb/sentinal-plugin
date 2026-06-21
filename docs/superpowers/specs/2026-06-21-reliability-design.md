# Reliability & Data — Schema Migrations + DB-Error Surfacing — Design

**Date:** 2026-06-21
**Status:** Approved (design)

## Problem

Schema changes are handled ad-hoc: the baseline runs `CREATE TABLE/INDEX IF NOT EXISTS`, and the
one historical change (`players.playtime`) is a `try/catch`-swallowed `ALTER TABLE` in
`SqliteDatabase` only — MySQL never got it. There is no version tracking, so future schema changes
have no safe, ordered, dialect-symmetric path. Separately, when an async DB read fails, the
`DatabaseExecutor.callback(future, onMain)` delivers `null`, so GUIs render an empty inventory with
no indication anything went wrong.

## Goal

Make schema upgrades safe and symmetric across SQLite and MySQL via a versioned, idempotent
migration runner; make DB failures visible to the user instead of producing silent empty GUIs.
Both are fail-soft and must not change behaviour on a healthy database.

Non-goals: a full migration framework replacing `schemaStatements()` (the idempotent baseline
stays); rolling back migrations (forward-only); surfacing internal error details to non-ops.

## 1. Schema migration runner

A `storage/SchemaMigrator` runs AFTER the existing idempotent baseline `createSchema()` in both
`SqliteDatabase` and `MysqlDatabase`.

- **Migration model:** an ordered, immutable list of migrations, each with an `int version` (1, 2, …)
  and the SQL to run for a given `SqlDialect` (a method `List<String> statements(SqlDialect.Type)`
  so SQLite/MySQL can differ). Defined in one place (e.g. `SchemaMigrator.MIGRATIONS`).
- **Version storage:** the existing `settings` table (`key TEXT PRIMARY KEY, value TEXT`). Key
  `schema_version`, integer-as-text, default `0` when absent.
- **Run algorithm (on the executor's owning connection, single-threaded at startup):**
  1. Read current version from `settings` (0 if missing).
  2. For each migration with `version > current`, in ascending order: execute its statements for the
     active dialect, then write `schema_version = version` to `settings`. SQLite wraps each migration
     in a transaction; MySQL DDL auto-commits per statement (so statements must be individually
     idempotent — see next point).
  3. Stop at the latest version.
- **Idempotency:** every migration statement is idempotent-guarded so re-running is safe even if the
  version counter is behind reality (e.g. a pre-migrator DB at version 0): "duplicate column" / 
  "already exists" errors are caught and ignored (SQLite by message contains, MySQL by error code —
  `ER_DUP_FIELDNAME` 1060, `ER_DUP_KEYNAME` 1061). A NON-idempotent, unexpected failure aborts:
  log SEVERE with the migration version + error and disable the plugin (no half-migrated schema in
  service). The version is an optimisation to skip already-applied migrations; correctness rests on
  idempotency.
- **Migration V1:** `ALTER TABLE players ADD COLUMN playtime <INTEGER/BIGINT> NOT NULL DEFAULT 0`
  for BOTH dialects (idempotent-guarded). This replaces the ad-hoc `ALTER` in `SqliteDatabase`
  (removed from `createSchema`) and gives MySQL parity. Fresh installs already have the column from
  the baseline `CREATE TABLE`, so V1 is a no-op there.
- After running, an unknown/legacy DB ends at the latest version recorded in `settings`.

## 2. DB-error surfacing

`storage/DatabaseExecutor` gains an error-aware path:

- New overload `<T> void callback(CompletableFuture<T> future, Consumer<T> onMain, Consumer<Throwable> onError)`
  — on normal completion runs `onMain(value)` on the main thread; on exceptional completion runs
  `onError(throwable)` on the main thread (and logs SEVERE). The existing 2-arg
  `callback(future, onMain)` is kept for back-compat (logs + delivers `null` as today) but is no
  longer used by data-loading call sites.
- Convenience for GUIs/commands: `void callbackOrError(Player viewer, CompletableFuture<T> future, Consumer<T> onSuccess)`
  — wraps the 3-arg form; on error it sends `viewer` the message `db-error` and does NOT run
  `onSuccess` (so no empty GUI opens / no stale action), and logs SEVERE.
- New message key `db-error` in `messages.yml` (English default, e.g.
  `"<red>Something went wrong — please try again in a moment."`), auto-merged into existing installs.
- Call-site migration: the static `open(...)` data-loaders in the GUIs and the value-returning
  write consumers in commands/GUIs switch from `callback(future, onMain)` to
  `callbackOrError(viewer, future, onSuccess)`. Done in batches. Fire-and-forget writes (no future,
  via `execute`) keep their existing SEVERE-log behaviour — unchanged.

## Error handling

- Migrator: idempotent statements swallow expected duplicate errors; unexpected errors abort startup
  with a clear SEVERE log + `disablePlugin` (consistent with the existing "Failed to open database"
  path in `Sentinel.onEnable`). No partial-migration state is left running.
- Callback error path: always on the main thread; never throws into the scheduler; logs SEVERE so
  ops can diagnose, shows the generic `db-error` to the viewer (no internal detail leak).

## Testing

- `SchemaMigratorTest` (SQLite real DB): a fresh DB ends at the latest version; pending migrations
  apply in order; re-running applies nothing and leaves the version unchanged; an already-present
  column (idempotent V1) does not error; `schema_version` is written to `settings`. MySQL specifics
  (error-code idempotency, BIGINT) covered by a dialect-level assertion / fake where a real MySQL is
  unavailable.
- DB-error path (MockBukkit): a future completing exceptionally runs `onError` / sends the viewer
  `db-error` on the main thread and does NOT run `onSuccess`; a normal future runs `onSuccess` and
  not the error path.
- Existing DAO/GUI/manager tests stay green (healthy-DB behaviour unchanged).

## Risks

- **Existing-install migration** — a legacy DB at version 0 re-runs V1. Mitigation: V1 is
  idempotent-guarded; tests cover the already-present-column case.
- **MySQL DDL auto-commit** — a migration with multiple statements can partially apply before a
  failure. Mitigation: keep migrations small and individually idempotent; abort + SEVERE on
  unexpected failure so the admin notices and can re-run (idempotency makes re-run safe).
- **Call-site breadth for error surfacing** — many `open(...)` loaders touched. Mitigation: batched,
  mechanical change to one helper call; existing tests guard behaviour on the success path.
