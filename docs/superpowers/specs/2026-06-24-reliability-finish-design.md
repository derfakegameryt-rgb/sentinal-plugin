# Reliability Finish (v3.1.5) — Design Spec

**Date:** 2026-06-24
**Status:** Approved
**Goal:** Close the last two reliability gaps from the audit — half-applied schema migrations and read queries that surface `db-error` on a transient SQLite busy/locked — so the DB layer is atomic on migration and resilient on every query path.

## Background

The v3.1.4 hardening made writes durable (bounded retry) and backups/config atomic. Two audit items were deliberately deferred and are now in scope:

1. **Schema migrations are not atomic.** `SchemaMigrator.migrate` runs each migration statement in autocommit, then bumps `schema_version`. A migration with more than one statement that fails partway (on a non-duplicate error) leaves the earlier statements committed and the version unchanged, so the next startup re-runs the whole migration against a partially-mutated schema.
2. **Reads do not retry transient failures.** The bounded retry added in v3.1.4 covers only the write paths (`execute`, `submitWrite`). A read (`submit`) that hits a transient `SQLITE_BUSY`/`SQLITE_LOCKED` immediately surfaces `db-error` to staff (e.g. an empty GUI), even though retrying would have succeeded.

The backend is **SQLite only** (`DatabaseFactory.open` always builds `SqliteDatabase`; `SqlDialect` has only the SQLITE implementation). SQLite supports **transactional DDL**, so wrapping a migration's statements in one transaction makes it all-or-nothing with no MySQL implicit-commit caveat.

## Global Constraints

- Target Minecraft 1.21 (Paper/Folia), Java 21, Gradle + shadow. `spotlessCheck` runs in `build` and FAILS on unused imports (4-space indent, no reformatting of untouched code).
- No new dependencies. No config-schema, message, or feature-behaviour changes — pure reliability patch.
- DB writes use `execute()` / `submitWrite()`; reads use `submit()`. This routing does not change; only `submit`'s per-attempt behaviour gains the existing retry.
- Reuse the existing retry mechanism (`runWithRetry`, `isTransient`, `WRITE_RETRIES`, `WRITE_BACKOFF_MS`) — do NOT introduce a second retry path or new constants.
- Version bumped to **3.1.5** in BOTH `build.gradle.kts` (line 8) and `src/main/resources/plugin.yml` (line 2) as the final step.
- Tests: JUnit 5; storage tests use a real `SqliteDatabase` on a temp file (existing pattern in `SchemaMigratorTest` / `DatabaseExecutorTest`). No MockBukkit needed for either item.
- Release notes (post-merge) describe only generic reliability hardening — never the auto-updater or owner role.

---

## Item A — Transactional schema migrations

**File:** `src/main/java/de/derfakegamer/sentinel/storage/SchemaMigrator.java`

**Problem:** Each migration's statements run in autocommit; a multi-statement migration that fails on statement N (non-duplicate) leaves statements 1..N-1 committed and `schema_version` unchanged.

**Design:**

- Make the nested `Migration` record **package-private** (drop `private`) so a same-package test can construct one.
- Add a **package-private** test seam: `static void migrate(Database db, Logger log, List<Migration> migrations)`. The existing public `static void migrate(Database db, Logger log)` delegates to it with the real `MIGRATIONS` list. All migration logic lives in the 3-arg form.
- Run each migration in ONE transaction on the single SQLite connection:
  1. `java.sql.Connection c = db.connection();`
  2. save `boolean prevAutoCommit = c.getAutoCommit();`
  3. `c.setAutoCommit(false);`
  4. for each statement: `executeUpdate`; if it throws and `isDuplicate(e)` → log `fine` and continue (idempotent baseline columns), else throw `RuntimeException("Schema migration V<n> failed: <sql>", e)`.
  5. `settings.set("schema_version", String.valueOf(m.version()));` — same connection, so it joins the transaction (`SettingsDao.set` calls `db.connection()`, which returns the same physical SQLite connection).
  6. `c.commit();` then `log.info("Applied schema migration V<n>")`.
  - On any exception in 3–6: `c.rollback()` (ignore a rollback failure), then rethrow (wrap non-`RuntimeException` in `RuntimeException`).
  - `finally`: restore `c.setAutoCommit(prevAutoCommit)` (ignore failure).
- The `SettingsDao settings = new SettingsDao(db)` and the `current = Integer.parseInt(settings.get("schema_version","0"))` read stay before the loop, unchanged.
- `isDuplicate` and `latestVersion` are unchanged.

**Result:** A migration applies fully (all statements + version bump) or not at all. A re-run after a clean failure starts from the unchanged version against the unchanged schema.

**Interfaces:** public `migrate(Database, Logger)` signature unchanged. New package-private `migrate(Database, Logger, List<Migration>)` and package-private `Migration` record (fields `int version`, `Function<Database,List<String>> statements`).

**Tests (`SchemaMigratorTest`, extend):**
- **Rollback on failure:** call the 3-arg `migrate` with an injected migration (version `latestVersion()+50`) whose statements are `["CREATE TABLE mig_rollback_test (id INTEGER)", "THIS IS NOT VALID SQL"]`. Assert `migrate` throws, the `mig_rollback_test` table does NOT exist afterward (statement 1 rolled back), and `schema_version` is unchanged (still `latestVersion()`).
- **Commit on success:** call the 3-arg `migrate` with an injected migration (version `latestVersion()+50`) whose single statement is `"CREATE TABLE mig_ok_test (id INTEGER)"`. Assert the table exists and `schema_version` becomes `latestVersion()+50`.
- All existing tests (`freshDbEndsAtLatestVersion`, `playtimeColumnExistsAfterMigration`, `reRunningMigratorIsNoOpAndKeepsVersion`, `migratorReappliesIdempotentlyFromVersionZero`) must still pass — the public path is behaviourally unchanged for the real, single-statement, idempotent migration.

---

## Item B — Bounded retry for reads

**File:** `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java` (`submit`)

**Problem:** `submit` runs `work.call()` once; a transient busy/locked failure surfaces `db-error` instead of being retried.

**Design:**

- In `submit`, change the single line `f.complete(work.call());` to `f.complete(runWithRetry(work));`. Everything else in `submit` (reader-pool routing, connection acquire/bind/release in `finally`) is unchanged.
- This reuses the existing `runWithRetry` and `isTransient` — reads are trivially idempotent, so retrying a busy/locked read is always safe. Non-transient read failures still fail on the first attempt (so the existing `submitFailureCompletesExceptionallyAndDoesNotKillThread` test, which throws `SQLException("boom")` with errorCode 0, stays green).
- The backoff `Thread.sleep` runs on whichever thread the read runs on (the writer thread for SQLite, since `supportsConcurrentReads()` is false) — a background thread, never the server tick. Identical safety profile to the write retry.

**Interfaces:** `submit` signature unchanged.

**Tests (`DatabaseExecutorTest`, extend):**
- **Read retries then succeeds:** a `submit` whose work throws a transient `SQLException("database is locked", "SQLITE", 5)` twice then returns `42` ultimately returns `42`; assert attempt count == 3.
- **Read does not retry non-transient:** a `submit` whose work throws `SQLException("UNIQUE…", "23000", 19)` is attempted exactly once and the future completes exceptionally.

---

## Release

After both items pass review and `./gradlew clean build` is green:

- Bump `version` to `3.1.5` in `build.gradle.kts:8` and `src/main/resources/plugin.yml:2`.
- Release notes: generic reliability wording (atomic migrations, more resilient reads). No auto-updater or owner mention.

## Out of Scope (explicitly)

- BatchWriter re-queue on flush failure, dead-letter persistence — still rejected (fail-soft by design).
- Adding new migrations or changing the existing V1 — none needed.
- MySQL/MariaDB support — the backend is SQLite-only; no multi-engine migration concerns.
- Read retry for a hypothetical concurrent-read backend behaves identically (reuses the same helper); no special-casing.
