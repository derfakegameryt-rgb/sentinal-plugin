# Reliability Finish (v3.1.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make schema migrations atomic (all-or-nothing per migration) and make read queries retry transient SQLite busy/locked failures, closing the last two audit reliability gaps.

**Architecture:** `SchemaMigrator` wraps each migration's statements plus its `schema_version` bump in one SQLite transaction (SQLite supports transactional DDL), with a package-private injectable-migrations seam for testing rollback. `DatabaseExecutor.submit` reuses the existing `runWithRetry` so reads get the same bounded retry writes already have.

**Tech Stack:** Java 21, Paper/Folia 1.21, Gradle + shadow, JUnit 5, relocated xerial SQLite.

## Global Constraints

- SQLite-only backend (`DatabaseFactory` always builds `SqliteDatabase`); transactional DDL is safe — no MySQL caveat.
- No new dependencies. No config-schema/message/feature changes — pure reliability patch.
- `spotlessCheck` runs in `build` and FAILS on unused imports (4-space indent, no reformatting of untouched code).
- Reuse the existing retry mechanism (`runWithRetry`, `isTransient`, `WRITE_RETRIES`, `WRITE_BACKOFF_MS`) — do NOT add a second retry path or new constants.
- DB write/read routing is unchanged; only `submit`'s per-attempt behaviour gains the existing retry.
- Final task bumps `version` to **3.1.5** in BOTH `build.gradle.kts` (line 8) and `src/main/resources/plugin.yml` (line 2).
- Release notes (post-merge, outside this plan) mention only generic reliability hardening — never the auto-updater or owner role.

---

### Task 1: Transactional schema migrations

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/SchemaMigrator.java`
- Test: `src/test/java/de/derfakegamer/sentinel/storage/SchemaMigratorTest.java` (extend)

**Interfaces:**
- Consumes: `Database.connection()` (the single SQLite `java.sql.Connection`), `SettingsDao.set/get`.
- Produces: package-private `record Migration(int version, java.util.function.Function<Database,java.util.List<String>> statements)`; package-private `static void migrate(Database db, Logger log, List<Migration> migrations)`. Public `static void migrate(Database db, Logger log)` unchanged in signature, now delegating with `MIGRATIONS`.

- [ ] **Step 1: Write the failing tests** (append to `SchemaMigratorTest`, inside the class; add `import java.util.List;` and `import java.util.function.Function;` at the top of the test file if not present)

```java
    private boolean tableExists(String name) throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + name + " LIMIT 0")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Test
    void failedMigrationRollsBackAndKeepsVersion() {
        int target = SchemaMigrator.latestVersion() + 50;
        SchemaMigrator.Migration bad = new SchemaMigrator.Migration(target,
            d -> List.of("CREATE TABLE mig_rollback_test (id INTEGER)", "THIS IS NOT VALID SQL"));
        assertThrows(RuntimeException.class,
            () -> SchemaMigrator.migrate(db, java.util.logging.Logger.getLogger("test"), List.of(bad)));
        assertFalse(tableExists("mig_rollback_test"), "first statement must be rolled back on failure");
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version(),
            "schema_version must be untouched after a failed migration");
    }

    @Test
    void successfulInjectedMigrationCommitsAndBumpsVersion() throws Exception {
        int target = SchemaMigrator.latestVersion() + 50;
        SchemaMigrator.Migration ok = new SchemaMigrator.Migration(target,
            d -> List.of("CREATE TABLE mig_ok_test (id INTEGER)"));
        SchemaMigrator.migrate(db, java.util.logging.Logger.getLogger("test"), List.of(ok));
        assertTrue(tableExists("mig_ok_test"));
        assertEquals(String.valueOf(target), version());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.storage.SchemaMigratorTest'`
Expected: FAIL — `SchemaMigrator.Migration` is `private` and the 3-arg `migrate` overload does not exist (compile error).

- [ ] **Step 3: Implement transactional migrations** — in `SchemaMigrator.java`

Add `import java.sql.Connection;` next to the existing `import java.sql.SQLException;` / `import java.sql.Statement;`.

Change the `Migration` record from `private record` to package-private:

```java
    record Migration(int version, Function<Database, List<String>> statements) {}
```

Replace the entire existing `migrate(Database db, Logger log)` method with the delegating public method plus the package-private overload and two helpers:

```java
    public static void migrate(Database db, Logger log) {
        migrate(db, log, MIGRATIONS);
    }

    /** Test seam: runs the given migrations (the public overload passes the real {@link #MIGRATIONS}). */
    static void migrate(Database db, Logger log, List<Migration> migrations) {
        SettingsDao settings = new SettingsDao(db);
        int current;
        try {
            current = Integer.parseInt(settings.get("schema_version", "0"));
        } catch (NumberFormatException e) {
            current = 0;
        }
        for (Migration m : migrations) {
            if (m.version() <= current) continue;
            applyMigration(db, settings, m, log);
        }
    }

    /**
     * Applies one migration atomically: all of its statements AND the schema_version bump commit
     * together, or the whole migration rolls back. SQLite supports transactional DDL, so a statement
     * that fails partway can never leave a half-migrated schema (which the next startup would re-run
     * against). A duplicate-column/already-exists error is still swallowed for idempotency.
     */
    private static void applyMigration(Database db, SettingsDao settings, Migration m, Logger log) {
        Connection c = db.connection();
        boolean prevAutoCommit;
        try {
            prevAutoCommit = c.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException("Schema migration V" + m.version() + " failed: cannot read autocommit", e);
        }
        try {
            c.setAutoCommit(false);
            for (String sql : m.statements().apply(db)) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate(sql);
                } catch (SQLException e) {
                    if (isDuplicate(e)) {
                        log.fine("Migration V" + m.version() + ": already applied (" + e.getMessage() + ")");
                    } else {
                        throw new RuntimeException("Schema migration V" + m.version() + " failed: " + sql, e);
                    }
                }
            }
            settings.set("schema_version", String.valueOf(m.version()));
            c.commit();
            log.info("Applied schema migration V" + m.version());
        } catch (RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new RuntimeException("Schema migration V" + m.version() + " failed to commit", e);
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
        }
    }

    private static void rollbackQuietly(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) {}
    }
```

`isDuplicate` and `latestVersion` are unchanged.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.storage.SchemaMigratorTest'`
Expected: PASS — all existing tests (`freshDbEndsAtLatestVersion`, `playtimeColumnExistsAfterMigration`, `reRunningMigratorIsNoOpAndKeepsVersion`, `migratorReappliesIdempotentlyFromVersionZero`) plus the 2 new ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/SchemaMigrator.java \
        src/test/java/de/derfakegamer/sentinel/storage/SchemaMigratorTest.java
git commit -m "feat: run each schema migration in a transaction (atomic, rolls back on failure)"
```

---

### Task 2: Bounded retry for reads

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java` (`submit`)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java` (extend)

**Interfaces:**
- Consumes: the existing private `runWithRetry(Callable<T>)` (added in v3.1.4) and `isTransient`.
- Produces: no signature change. `submit` now retries transient (busy/locked) read failures.

- [ ] **Step 1: Write the failing tests** (append to `DatabaseExecutorTest`, inside the class — `AtomicInteger`, `CompletableFuture`, `ExecutionException`, `TimeUnit` are already imported)

```java
    @Test void readRetriesTransientFailureThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Integer result = exec.submit(() -> {
            if (calls.incrementAndGet() < 3) throw new java.sql.SQLException("database is locked", "SQLITE", 5);
            return 42;
        }).get(5, TimeUnit.SECONDS);
        assertEquals(42, result);
        assertEquals(3, calls.get(), "two transient failures then success = 3 attempts");
    }

    @Test void readDoesNotRetryNonTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Object> f = exec.submit(() -> {
            calls.incrementAndGet();
            throw new java.sql.SQLException("UNIQUE constraint failed", "23000", 19);
        });
        assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get(), "non-transient read failure must not be retried");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.storage.DatabaseExecutorTest'`
Expected: FAIL — `readRetriesTransientFailureThenSucceeds` sees only 1 attempt (read currently runs `work.call()` once).

- [ ] **Step 3: Implement the read retry** — in `DatabaseExecutor.java`, inside the `submit` method, change exactly one line:

Find:

```java
                f.complete(work.call());
```

(inside `submit`, immediately after `database.bind(conn);`) and change it to:

```java
                f.complete(runWithRetry(work));
```

Nothing else in `submit` changes — the reader-pool routing and the `finally` that clears the bind and releases a pooled connection stay exactly as they are. (`runWithRetry` throws `Exception`, which the existing `catch (Throwable t)` already handles.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.storage.DatabaseExecutorTest'`
Expected: PASS — all existing tests plus the 2 new ones. The existing `submitFailureCompletesExceptionallyAndDoesNotKillThread` (throws `SQLException("boom")`, errorCode 0 → non-transient) still completes exceptionally on a single attempt.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java \
        src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java
git commit -m "feat: bounded retry for transient read failures too"
```

---

### Task 3: Version bump to 3.1.5 + full build

**Files:**
- Modify: `build.gradle.kts:8`
- Modify: `src/main/resources/plugin.yml:2`

- [ ] **Step 1: Bump the version in `build.gradle.kts`**

Change line 8 from `version = "3.1.4"` to:

```kotlin
version = "3.1.5"
```

- [ ] **Step 2: Bump the version in `plugin.yml`**

Change line 2 from `version: '3.1.4'` to:

```yaml
version: '3.1.5'
```

- [ ] **Step 3: Run the full build (tests + spotless + shadowJar)**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`; produces `build/libs/Sentinel-3.1.5.jar`; `spotlessCheck` passes (no unused imports — note Task 1 added `import java.sql.Connection;` which IS used).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/plugin.yml
git commit -m "release: v3.1.5"
```

(Actual `gh release create` publishing happens after the branch is merged, in the finishing step — not in this plan. Release notes describe only generic reliability hardening.)

---

## Self-Review

**Spec coverage:**
- Item A (transactional migrations: per-migration transaction, version bump inside it, rollback on failure, duplicate still swallowed, package-private `Migration` + 3-arg seam) → Task 1. ✓
- Item A tests (rollback keeps version + drops table; success commits + bumps) → Task 1 Steps 1–4. ✓
- Item B (reuse `runWithRetry` in `submit`, non-transient still single attempt) → Task 2. ✓
- Item B tests (read retry-then-success; read non-transient single attempt) → Task 2 Steps 1–4. ✓
- Release (v3.1.5 in both files, build green, secrecy in notes) → Task 3. ✓
- Out-of-scope (BatchWriter re-queue, new migrations, MySQL) → not touched. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N"; every code step shows complete code. ✓

**Type consistency:** `Migration(int, Function<Database,List<String>>)`, `migrate(Database,Logger,List<Migration>)`, `applyMigration(Database,SettingsDao,Migration,Logger)`, `rollbackQuietly(Connection)` are used consistently across Task 1 impl and tests. Task 2 reuses `runWithRetry(Callable<T>)` exactly as defined in v3.1.4. The `import java.sql.Connection;` added in Task 1 is used by `applyMigration`/`rollbackQuietly`, so spotless stays green (verified against Task 3's build expectation). ✓
