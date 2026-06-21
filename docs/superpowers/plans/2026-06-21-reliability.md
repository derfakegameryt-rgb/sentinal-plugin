# Reliability — Schema Migrations + DB-Error Surfacing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a versioned, idempotent, dialect-symmetric schema-migration runner and make DB failures visible to users instead of producing silent empty GUIs.

**Architecture:** A `SchemaMigrator` runs after the idempotent baseline `createSchema()` in both Database impls, tracking `schema_version` in the existing `settings` table and applying ordered idempotent migrations. `DatabaseExecutor` gains an error-aware callback so data-loaders show a `db-error` message on failure.

**Tech Stack:** Java 21, Paper API, JDBC (SQLite/MariaDB), JUnit 5, MockBukkit.

## Global Constraints

- Forward-only migrations. Each migration statement MUST be idempotent-guarded (duplicate-column/index errors swallowed): SQLite by message-substring, MySQL by error code (1060 ER_DUP_FIELDNAME, 1061 ER_DUP_KEYNAME). `schema_version` is an optimisation to skip applied migrations; correctness rests on idempotency.
- An UNEXPECTED (non-duplicate) migration failure aborts startup: log SEVERE with the version + disable the plugin (mirror the existing `Sentinel.onEnable` "Failed to open database" path). No half-migrated schema in service.
- Healthy-DB behaviour is unchanged; existing tests stay green.
- Fire-and-forget writes (via `execute`, no future) keep their SEVERE-log behaviour — do NOT change them.
- Do NOT `git add -A` (gitignored `.claude/` + `.superpowers/`). Stage explicit paths. Run `./gradlew test` before each commit.

---

### Task 1: `SchemaMigrator` + migration V1 (players.playtime), wired into both Databases

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/storage/SchemaMigrator.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/SqliteDatabase.java` (call migrator after baseline; REMOVE the ad-hoc `ALTER TABLE players ADD COLUMN playtime` try/catch)
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/MysqlDatabase.java` (call migrator after baseline)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/SchemaMigratorTest.java`

**Interfaces:**
- Consumes: `Database` (`connection()`, `dialect()`), `SettingsDao(Database)` with `get(key,def)`/`set(key,value)`.
- Produces: `SchemaMigrator.migrate(Database db, java.util.logging.Logger log)` — runs pending migrations; `SchemaMigrator.latestVersion()` (int) for tests.

**Before coding:** read `SqlDialect.java` to confirm how to test the dialect (enum/singleton constants `SqlDialect.SQLITE` / `SqlDialect.MYSQL`, returned by `db.dialect()`). Adapt the dialect check below to the real shape (`db.dialect() == SqlDialect.MYSQL`).

- [ ] **Step 1: Write the failing test** (SQLite real DB via the existing test DB pattern — check how `SqliteDatabase`/DAO tests construct a temp DB; reuse that):

```java
package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorTest {
    Path tmp; Database db;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel-mig", ".db");
        db = new SqliteDatabase(tmp.toString());     // adapt to the real SqliteDatabase ctor
    }
    @AfterEach void teardown() throws Exception {
        db.close();
        Files.deleteIfExists(tmp);
    }

    private String version() { return new SettingsDao(db).get("schema_version", "0"); }
    private boolean hasPlaytimeColumn() throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT playtime FROM players LIMIT 0")) { return true; }
        catch (SQLException e) { return false; }
    }

    @Test void freshDbEndsAtLatestVersion() {
        // SqliteDatabase ctor already ran baseline + migrator; version must be the latest.
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version());
        // baseline already has playtime; migrator must not have errored
    }

    @Test void playtimeColumnExistsAfterMigration() throws Exception {
        assertTrue(hasPlaytimeColumn());
    }

    @Test void reRunningMigratorIsNoOpAndKeepsVersion() throws Exception {
        SchemaMigrator.migrate(db, java.util.logging.Logger.getLogger("test")); // re-run
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version());
        assertTrue(hasPlaytimeColumn());
    }

    @Test void migratorReappliesIdempotentlyFromVersionZero() throws Exception {
        new SettingsDao(db).set("schema_version", "0");        // pretend legacy DB
        SchemaMigrator.migrate(db, java.util.logging.Logger.getLogger("test")); // V1 re-runs, column already exists -> swallowed
        assertEquals(String.valueOf(SchemaMigrator.latestVersion()), version());
        assertTrue(hasPlaytimeColumn());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests '*SchemaMigratorTest'` → FAIL (`SchemaMigrator` / `latestVersion` missing).

- [ ] **Step 3: Implement `SchemaMigrator`**

```java
package de.derfakegamer.sentinel.storage;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/** Forward-only, idempotent, dialect-aware schema migrations. Runs after the baseline createSchema. */
public final class SchemaMigrator {
    private SchemaMigrator() {}

    private record Migration(int version, Function<Database, List<String>> statements) {}

    private static final List<Migration> MIGRATIONS = List.of(
        // V1: players.playtime — baseline CREATE already includes it on fresh installs; this brings
        // pre-playtime SQLite/MySQL installs up to parity. Idempotent (duplicate-column swallowed).
        new Migration(1, db -> List.of(
            "ALTER TABLE players ADD COLUMN playtime "
                + (db.dialect() == SqlDialect.MYSQL ? "BIGINT" : "INTEGER")
                + " NOT NULL DEFAULT 0"))
    );

    public static int latestVersion() {
        int max = 0;
        for (Migration m : MIGRATIONS) max = Math.max(max, m.version());
        return max;
    }

    public static void migrate(Database db, Logger log) {
        SettingsDao settings = new SettingsDao(db);
        int current;
        try { current = Integer.parseInt(settings.get("schema_version", "0")); }
        catch (NumberFormatException e) { current = 0; }

        for (Migration m : MIGRATIONS) {
            if (m.version() <= current) continue;
            for (String sql : m.statements().apply(db)) {
                try (Statement st = db.connection().createStatement()) {
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
            log.info("Applied schema migration V" + m.version());
        }
    }

    /** True for "column/index already exists" errors that make a migration safe to re-run. */
    private static boolean isDuplicate(SQLException e) {
        int code = e.getErrorCode();
        if (code == 1060 || code == 1061) return true; // MySQL ER_DUP_FIELDNAME / ER_DUP_KEYNAME
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("duplicate column") || msg.contains("already exists");
    }
}
```

- [ ] **Step 4: Wire into the Databases.** In `SqliteDatabase.createSchema()` (or right after it in the constructor), REMOVE the ad-hoc `ALTER TABLE players ADD COLUMN playtime ... try/catch` block, and after the baseline statements call `SchemaMigrator.migrate(this, logger)`. Use the logger the class already has (or pass one in — check the ctor; if none, use `Logger.getLogger("Sentinel")`). Do the same in `MysqlDatabase` after its baseline `createSchema()`. The migrator must run AFTER the `settings` table exists (it is created by the baseline).

- [ ] **Step 5: Run** `./gradlew test --tests '*SchemaMigratorTest'` → PASS, then full suite → green (the removed SQLite ALTER is now covered by V1).
- [ ] **Step 6: Commit** — `git add src/main/java/de/derfakegamer/sentinel/storage/SchemaMigrator.java src/main/java/de/derfakegamer/sentinel/storage/SqliteDatabase.java src/main/java/de/derfakegamer/sentinel/storage/MysqlDatabase.java src/test/java/de/derfakegamer/sentinel/storage/SchemaMigratorTest.java && git commit -m "feat: versioned idempotent schema migrator (V1 players.playtime, both dialects)"`

---

### Task 2: error-aware callback + `callbackOrError` + `db-error` message

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java`
- Modify: `src/main/resources/messages.yml` (add `db-error`)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java` (extend)

**Interfaces:**
- Produces: `<T> void callback(CompletableFuture<T>, Consumer<T> onMain, Consumer<Throwable> onError)`;
  `<T> void callbackOrError(org.bukkit.entity.Player viewer, CompletableFuture<T>, Consumer<T> onSuccess)`.
- Consumes: `plugin.messages().prefixed("db-error")` (or `plain` — match how other user-facing errors are sent), `plugin.getLogger()`.

- [ ] **Step 1: Write the failing test** (MockBukkit; mirror the existing DatabaseExecutorTest setup):

```java
// In DatabaseExecutorTest:
@Test void callbackErrorPathRunsOnErrorNotOnMain() throws Exception {
    java.util.concurrent.CompletableFuture<String> failed = new java.util.concurrent.CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("boom"));
    java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
    plugin.db().callback(failed, v -> success.set(true), err::set);
    server.getScheduler().performTicks(2);
    assertFalse(success.get(), "onMain must not run on failure");
    assertNotNull(err.get(), "onError must receive the throwable");
}

@Test void callbackSuccessPathRunsOnMain() throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> got = new java.util.concurrent.atomic.AtomicReference<>();
    plugin.db().callback(java.util.concurrent.CompletableFuture.completedFuture("ok"),
        got::set, t -> {});
    server.getScheduler().performTicks(2);
    assertEquals("ok", got.get());
}
```

(For `callbackOrError`, assert the viewer received a message and `onSuccess` did not run — use a `PlayerMock` and check its next message, matching how other message-assertion tests in the suite read sent messages.)

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** — add to `DatabaseExecutor`:

```java
public <T> void callback(CompletableFuture<T> future, Consumer<T> onMain, Consumer<Throwable> onError) {
    future.whenComplete((value, error) -> {
        Runnable task = (error == null)
            ? () -> onMain.accept(value)
            : () -> { plugin.getLogger().log(java.util.logging.Level.SEVERE, "DB operation failed", error); onError.accept(error); };
        if (plugin == null) { task.run(); return; }
        plugin.getServer().getScheduler().runTask(plugin, task);
    });
}

public <T> void callbackOrError(org.bukkit.entity.Player viewer, CompletableFuture<T> future, Consumer<T> onSuccess) {
    callback(future, onSuccess, error -> {
        if (viewer != null && viewer.isOnline()) viewer.sendMessage(plugin.messages().prefixed("db-error"));
    });
}
```

Keep the existing 2-arg `callback(future, onMain)` as-is for back-compat. Add to `messages.yml`:
```yaml
db-error: "<red>Something went wrong — please try again in a moment."
```

- [ ] **Step 4: Run tests**, full suite → green.
- [ ] **Step 5: Commit** — `git add src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java src/main/resources/messages.yml src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java && git commit -m "feat: error-aware DB callback + callbackOrError + db-error message"`

---

## Call-site migration (Tasks 3–4)

**Shared procedure:** replace a data-loading `plugin.db().callback(future, value -> { ...build+open GUI / use value... })` with `plugin.db().callbackOrError(viewer, future, value -> { ...same body... })`, where `viewer` is the Player the GUI opens for / the command sender. The success body is UNCHANGED. This means: on DB failure the viewer gets the `db-error` message instead of an empty GUI / silent no-op.

Only migrate call-sites that LOAD data for display or read-before-act. Leave any `callback` whose body is purely incidental (none expected) — but every listed file below has viewer context.

**Worked example (HistoryGui-style loader):**
Before:
```java
plugin.db().callback(plugin.punishments().history(target), list ->
    new HistoryGui(plugin, target, list, page).open(viewer));
```
After:
```java
plugin.db().callbackOrError(viewer, plugin.punishments().history(target), list ->
    new HistoryGui(plugin, target, list, page).open(viewer));
```

After each batch: `./gradlew compileJava compileTestJava` then the full suite. Existing tests use successful DBs, so success-path behaviour is unchanged and they stay green.

---

### Task 3: migrate GUI data-loaders to `callbackOrError`

**Files (Modify):** the static `open(...)` loaders in `gui/`: `PlayersGui`, `ReportsGui`, `HistoryGui`,
`ActiveBansGui`, `ActiveMutesGui`, `TemplatesGui`, `ChatLogGui`, `AuditGui`, `ModStatsGui`,
`SearchResultsGui`, `AltsGui`, `NotesGui`, `StatsGui`, `PlayerActionsGui`, `AppealsGui`.
**Tests:** existing GUI tests must stay green; no new test required here (Task 2 covers the mechanism). If a GUI has a viewer-less internal callback, leave it and note it.

- [ ] **Step 1:** For each GUI, change its data-loading `callback(future, …)` to `callbackOrError(viewer, future, …)`. The viewer is the `Player` parameter of the static `open(...)` method. Do not change the success body.
- [ ] **Step 2: Run** `./gradlew compileJava compileTestJava` then `./gradlew test` → green.
- [ ] **Step 3: Commit** — `git add -- src/main/java/de/derfakegamer/sentinel/gui && git commit -m "feat: surface DB load errors in GUIs (callbackOrError)"`

---

### Task 4: migrate command / manager / discord consumers

**Files (Modify):** `command/PlaytimeCommand.java`, `command/SentinelCommand.java`,
`command/PunishmentCommands.java`, `command/ReportCommand.java`, `command/AppealCommand.java`,
`manager/ReportManager.java`, `discord/SlashCommandListener.java`.
**Tests:** existing command/manager tests stay green.

- [ ] **Step 1:** Migrate each data-loading/read-before-act `callback(future, …)` to `callbackOrError(sender, future, …)` where a Player sender is available. For non-player contexts (console sender, Discord — `SlashCommandListener` has no Bukkit Player viewer): use the 3-arg `callback(future, onMain, onError)` directly, where `onError` logs and (for Discord) replies with a generic error to the interaction; do NOT call `callbackOrError` with a null viewer expecting a Bukkit message. Keep success bodies unchanged.
- [ ] **Step 2: Run** `./gradlew compileJava compileTestJava` then `./gradlew test` → green.
- [ ] **Step 3: Commit** — `git add -- src/main/java/de/derfakegamer/sentinel/command src/main/java/de/derfakegamer/sentinel/manager/ReportManager.java src/main/java/de/derfakegamer/sentinel/discord/SlashCommandListener.java && git commit -m "feat: surface DB errors in commands and Discord"`

---

## Self-Review

- **Spec coverage:** versioned idempotent dialect-aware migrator + version in `settings` + V1 playtime
  for both dialects + remove ad-hoc ALTER + abort-on-unexpected-failure (Task 1); error-aware
  callback + callbackOrError + `db-error` message (Task 2); data-loaders surface errors instead of
  empty GUIs (Tasks 3–4); fire-and-forget writes unchanged (stated, untouched). All spec sections
  mapped.
- **Placeholder scan:** Tasks 1–2 carry full code. Tasks 3–4 are mechanical call-site swaps with a
  worked example + exact file lists.
- **Type consistency:** `SchemaMigrator.migrate(Database, Logger)` / `latestVersion()` used in tests;
  `callback(future, onMain, onError)` and `callbackOrError(viewer, future, onSuccess)` from Task 2
  used identically in Tasks 3–4; `db.dialect() == SqlDialect.MYSQL` dialect check (verify real shape
  in Task 1).
- **Ordering:** migrator (1) and callback mechanism (2) first; call-site batches (3–4) depend on (2).
