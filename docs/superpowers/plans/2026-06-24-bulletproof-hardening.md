# Bulletproof Hardening (v3.1.4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close four verified robustness gaps — non-atomic backups, non-atomic `messages.yml` writes, non-clamping config validation, and dropped DB writes under transient SQLite contention — with no feature or config-schema changes.

**Architecture:** Each item is a localized hardening of one existing class. Backups and the YAML save adopt the same temp-file → validate → atomic-move pattern already used by the auto-updater. ConfigValidator mutates the in-memory config to clamp bad numbers. The DB executor wraps writes in a bounded retry that fires only on transient (busy/locked) failures.

**Tech Stack:** Java 21, Paper/Folia 1.21, Gradle + shadow, JUnit 5, MockBukkit `mockbukkit-v1.21`, relocated xerial SQLite.

## Global Constraints

- No new dependencies. No config-schema changes, no message-key changes, no feature behaviour changes — pure patch release.
- `spotlessCheck` runs inside `build` and FAILS on unused imports; 4-space indent; no reformatting of untouched code.
- All scheduling via `plugin.scheduler()` (Folia-safe); never `Bukkit.getScheduler()` directly.
- DB writes use `execute()` / `submitWrite()`; reads use `submit()`. Do not change this routing.
- New helpers that must be unit-tested are `static` and package-private so tests in the same package call them without a running server.
- Final task bumps `version` to `3.1.4` in BOTH `build.gradle.kts` (line 8) and `src/main/resources/plugin.yml` (line 2).
- Release notes (post-merge, outside this plan) mention only generic reliability hardening — never the auto-updater or owner role.

---

### Task 1: Atomic, validated backups

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/BackupManager.java` (`zipWorlds`, `prune`; add `validateZip`, `moveIntoPlace`)
- Test: `src/test/java/de/derfakegamer/sentinel/manager/BackupManagerTest.java` (extend)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: package-private `static void validateZip(File) throws IOException`, `static void moveIntoPlace(File tmp, File dest) throws IOException`. `zipWorlds(List<File>, File)` and `prune(File, int)` keep their signatures. Existing imports already include `java.io.*`, `java.nio.file.*`, `java.util.*`, `java.util.zip.*` — no new imports needed.

- [ ] **Step 1: Write the failing tests** (append to `BackupManagerTest`, inside the class)

```java
    private static File goodZip(java.nio.file.Path dir) throws Exception {
        File f = dir.resolve("good.zip").toFile();
        try (var zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(f))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
        return f;
    }

    @Test void validateZipAcceptsGoodArchive(@TempDir Path tmp) throws Exception {
        assertDoesNotThrow(() -> BackupManager.validateZip(goodZip(tmp)));
    }

    @Test void validateZipRejectsGarbage(@TempDir Path tmp) throws Exception {
        File f = tmp.resolve("bad.zip").toFile();
        Files.write(f.toPath(), "not a zip at all".repeat(10).getBytes());
        assertThrows(java.io.IOException.class, () -> BackupManager.validateZip(f));
    }

    @Test void validateZipRejectsEmptyArchive(@TempDir Path tmp) throws Exception {
        File f = tmp.resolve("empty.zip").toFile();
        try (var zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(f))) { /* no entries */ }
        assertThrows(java.io.IOException.class, () -> BackupManager.validateZip(f));
    }

    @Test void moveIntoPlaceReplacesDestAndRemovesTmp(@TempDir Path tmp) throws Exception {
        File dest = tmp.resolve("backup-1.zip").toFile();
        Files.writeString(dest.toPath(), "OLD");
        File part = tmp.resolve("backup-1.zip.part").toFile();
        Files.writeString(part.toPath(), "NEW");
        BackupManager.moveIntoPlace(part, dest);
        assertEquals("NEW", Files.readString(dest.toPath()));
        assertFalse(part.exists(), "tmp must be gone after the move");
    }

    @Test void zipWorldsLeavesNoPartFileOnSuccess(@TempDir Path tmp) throws Exception {
        BackupManager m = new BackupManager(plugin);
        File world = tmp.resolve("world").toFile();
        new File(world, "region").mkdirs();
        Files.writeString(new File(world, "level.dat").toPath(), "data");
        File zip = tmp.resolve("backup-9.zip").toFile();
        m.zipWorlds(java.util.List.of(world), zip);
        assertTrue(zip.exists());
        assertFalse(new File(tmp.toFile(), "backup-9.zip.part").exists(), "no .part left behind");
    }

    @Test void pruneLogsWhenDeleteFails(@TempDir Path tmp) throws Exception {
        File d = tmp.resolve("backup-0.zip").toFile();
        new File(d, "child").mkdirs();            // non-empty dir → File.delete() returns false
        d.setLastModified(0L);
        for (int i = 1; i <= 3; i++) {
            File f = tmp.resolve("backup-" + i + ".zip").toFile();
            Files.writeString(f.toPath(), "x"); f.setLastModified(1000L * i);
        }
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var h = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord r) { records.add(r); }
            public void flush() {} public void close() {}
        };
        plugin.getLogger().addHandler(h);
        new BackupManager(plugin).prune(tmp.toFile(), 2);
        plugin.getLogger().removeHandler(h);
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.WARNING
                && r.getMessage().contains("could not delete")), "expected a warning for the failed delete");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.BackupManagerTest'`
Expected: FAIL — `validateZip` / `moveIntoPlace` do not exist (compile error), and `pruneLogsWhenDeleteFails` would fail (no warning emitted).

- [ ] **Step 3: Implement the hardening** — replace `zipWorlds` and `prune` and add the two static helpers in `BackupManager.java`

```java
    void zipWorlds(List<File> dirs, File zip) throws IOException {
        // Write to a sibling temp file, validate it, then atomically move it into place — a dropped
        // connection, a full disk, or a crash can never leave a truncated/corrupt backup behind.
        File part = new File(zip.getParentFile(), zip.getName() + ".part");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)))) {
                for (File d : dirs) addDir(d.getParentFile().toPath(), d, zos);
            }
            validateZip(part);
            moveIntoPlace(part, zip);
        } finally {
            Files.deleteIfExists(part.toPath()); // no-op once the move succeeded
        }
    }

    /** Opens the archive to prove it is a complete, readable zip with at least one entry. */
    static void validateZip(File f) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) { // throws on a truncated/corrupt file
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) { en.nextElement(); count++; }
            if (count == 0) throw new IOException("backup archive has no entries: " + f.getName());
        }
    }

    /** Atomically replaces {@code dest} with {@code tmp}; falls back to a plain replace where an
     *  atomic move is unsupported (e.g. some Windows setups). */
    static void moveIntoPlace(File tmp, File dest) throws IOException {
        try {
            Files.move(tmp.toPath(), dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Keeps the newest {@code keep} backup zips, deletes the rest. */
    void prune(File dir, int keep) {
        File[] zips = dir.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (zips == null || zips.length <= keep) return;
        Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < zips.length - keep; i++) {
            if (!zips[i].delete())
                plugin.getLogger().warning("Sentinel backup: could not delete old backup " + zips[i].getName());
        }
    }
```

Note: `StandardCopyOption` is already covered by the existing `import java.nio.file.*;`. The `w.save()` call in `backup(...)` stays on the global/region thread (Bukkit requires world saves there) — do not move it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.BackupManagerTest'`
Expected: PASS (all existing + 6 new tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/BackupManager.java \
        src/test/java/de/derfakegamer/sentinel/manager/BackupManagerTest.java
git commit -m "feat: atomic validated backups + prune delete-failure logging"
```

---

### Task 2: Atomic `messages.yml` save

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (add `saveYamlAtomically`; call it in `mergeMessagesDefaults`)
- Test: `src/test/java/de/derfakegamer/sentinel/AtomicYamlSaveTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: package-private `static void saveYamlAtomically(org.bukkit.configuration.file.FileConfiguration cfg, File dest) throws java.io.IOException`.

- [ ] **Step 1: Write the failing test** — create `src/test/java/de/derfakegamer/sentinel/AtomicYamlSaveTest.java`

```java
package de.derfakegamer.sentinel;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicYamlSaveTest {

    @Test void writesKeysAndLeavesNoTmp(@TempDir Path dir) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("a.b", "hello");
        File dest = dir.resolve("messages.yml").toFile();
        Sentinel.saveYamlAtomically(cfg, dest);
        assertTrue(dest.exists());
        assertEquals("hello", YamlConfiguration.loadConfiguration(dest).getString("a.b"));
        assertFalse(new File(dir.toFile(), "messages.yml.tmp").exists(), "no .tmp left behind");
    }

    @Test void replacesExistingFile(@TempDir Path dir) throws Exception {
        File dest = dir.resolve("messages.yml").toFile();
        Files.writeString(dest.toPath(), "old: 1\n");
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("fresh", "val");
        Sentinel.saveYamlAtomically(cfg, dest);
        YamlConfiguration back = YamlConfiguration.loadConfiguration(dest);
        assertEquals("val", back.getString("fresh"));
        assertFalse(back.contains("old"), "old content must be fully replaced");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.AtomicYamlSaveTest'`
Expected: FAIL — `Sentinel.saveYamlAtomically` does not exist (compile error).

- [ ] **Step 3: Implement the helper and wire it in** — in `Sentinel.java`, add the static method (place it next to `mergeMessagesDefaults`)

```java
    /** Saves {@code cfg} to {@code dest} atomically: write a sibling temp file, then move it into place.
     *  A crash or full disk mid-write can never truncate the admin's real file. */
    static void saveYamlAtomically(org.bukkit.configuration.file.FileConfiguration cfg, File dest) throws java.io.IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        cfg.save(tmp);
        try {
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp.toPath());
        }
    }
```

Then in `mergeMessagesDefaults`, replace the existing `onDisk.save(file);` line with:

```java
            saveYamlAtomically(onDisk, file);
```

(The surrounding `try { ... } catch (java.io.IOException e) { getLogger().warning("Could not migrate " + name + ": " + e.getMessage()); }` stays exactly as it is — only the write call changes.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.AtomicYamlSaveTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/AtomicYamlSaveTest.java
git commit -m "feat: atomic messages.yml save (temp file + atomic move)"
```

---

### Task 3: ConfigValidator clamps invalid numbers

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java` (`checkNonNegativeInt`, `checkAnnouncementsInterval`)
- Test: `src/test/java/de/derfakegamer/sentinel/util/ConfigValidatorTest.java` (extend)

**Interfaces:**
- Consumes: nothing.
- Produces: no signature changes. `validate(cfg, log)` now mutates `cfg` in-memory to replace out-of-range numbers with their defaults. Clamp targets are the existing defaults: `afk.minutes`→5, `backup.keep`→5, `logging.retention-days`→30, `warns.expiry-days`→7, `report.cooldown-seconds`→30, `appeals.cooldown-seconds`→60, `announcements.interval-seconds`→300.

- [ ] **Step 1: Write the failing tests** (append to `ConfigValidatorTest`, inside the class)

```java
    @Test void negativeBackupKeepIsClampedToDefault() {
        var cfg = load("backup:\n  keep: -1\n");
        ConfigValidator.validate(cfg, log);
        assertEquals(5, cfg.getInt("backup.keep"), "negative value must be clamped to the default");
    }

    @Test void validIntValueIsNotMutated() {
        var cfg = load("backup:\n  keep: 3\n");
        ConfigValidator.validate(cfg, log);
        assertEquals(3, cfg.getInt("backup.keep"), "valid value must be left untouched");
        assertEquals(0, warningCount());
    }

    @Test void enabledZeroIntervalIsClampedToDefault() {
        var cfg = load("announcements:\n  enabled: true\n  interval-seconds: 0\n");
        ConfigValidator.validate(cfg, log);
        assertEquals(300L, cfg.getLong("announcements.interval-seconds"),
                "non-positive interval must be clamped to the default");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.util.ConfigValidatorTest'`
Expected: FAIL — `backup.keep` stays `-1` and `interval-seconds` stays `0` (validator only warns today).

- [ ] **Step 3: Implement the clamp** — in `ConfigValidator.java`, update the two methods

```java
    private static void checkNonNegativeInt(FileConfiguration cfg, Logger log, String key, int defaultVal) {
        int value = cfg.getInt(key, defaultVal);
        if (value < 0) {
            log.warning("Sentinel config: " + key + " is " + value
                    + " — must be 0 or greater; using default (" + defaultVal + ") instead.");
            cfg.set(key, defaultVal); // clamp in-memory so downstream reads get the safe value
        }
    }
```

```java
    private static void checkAnnouncementsInterval(FileConfiguration cfg, Logger log) {
        if (!cfg.getBoolean("announcements.enabled", false)) return;
        long interval = cfg.getLong("announcements.interval-seconds", 300L);
        if (interval <= 0) {
            log.warning("Sentinel config: announcements.enabled is true but announcements.interval-seconds is "
                    + interval + " — must be > 0; using default (300) instead.");
            cfg.set("announcements.interval-seconds", 300L); // clamp in-memory
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.util.ConfigValidatorTest'`
Expected: PASS (all existing + 3 new tests; existing `negativeBackupKeepWarns` still passes — the warning is still emitted).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java \
        src/test/java/de/derfakegamer/sentinel/util/ConfigValidatorTest.java
git commit -m "feat: ConfigValidator clamps out-of-range numbers to defaults"
```

---

### Task 4: Bounded retry for DB writes

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java` (`execute`, `submitWrite`; add `runWithRetry`, `isTransient`, constants)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java` (extend)

**Interfaces:**
- Consumes: nothing.
- Produces: package-private `static boolean isTransient(Throwable)`. `execute(Runnable)` and `submitWrite(Callable<T>)` keep their signatures but now retry transient failures. Reads (`submit`) unchanged.

- [ ] **Step 1: Write the failing tests** (append to `DatabaseExecutorTest`, inside the class — `AtomicInteger`, `CompletableFuture`, `ExecutionException`, `TimeUnit` are already imported)

```java
    @Test void writeRetriesTransientFailureThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Integer result = exec.submitWrite(() -> {
            if (calls.incrementAndGet() < 3) throw new java.sql.SQLException("database is locked", "SQLITE", 5);
            return 42;
        }).get(5, TimeUnit.SECONDS);
        assertEquals(42, result);
        assertEquals(3, calls.get(), "two transient failures then success = 3 attempts");
    }

    @Test void writeDoesNotRetryNonTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Object> f = exec.submitWrite(() -> {
            calls.incrementAndGet();
            throw new java.sql.SQLException("UNIQUE constraint failed", "23000", 19);
        });
        assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get(), "non-transient failure must not be retried");
    }

    @Test void writeGivesUpAfterRetryBudgetExhausted() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Object> f = exec.submitWrite(() -> {
            calls.incrementAndGet();
            throw new java.sql.SQLException("database is busy", "SQLITE", 5);
        });
        assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertEquals(4, calls.get(), "1 initial attempt + 3 retries = 4 attempts");
    }

    @Test void isTransientDetectsBusyAndLocked() {
        assertTrue(DatabaseExecutor.isTransient(new java.sql.SQLException("x", "y", 5)));
        assertTrue(DatabaseExecutor.isTransient(new java.sql.SQLException("x", "y", 6)));
        assertTrue(DatabaseExecutor.isTransient(new java.sql.SQLException("database is locked")));
        assertTrue(DatabaseExecutor.isTransient(new RuntimeException(new java.sql.SQLException("BUSY", "y", 5))));
        assertFalse(DatabaseExecutor.isTransient(new RuntimeException("nope")));
        assertFalse(DatabaseExecutor.isTransient(new java.sql.SQLException("UNIQUE constraint failed", "23000", 19)));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.storage.DatabaseExecutorTest'`
Expected: FAIL — `isTransient` does not exist (compile error); the retry tests would otherwise see only 1 attempt.

- [ ] **Step 3: Implement the retry** — in `DatabaseExecutor.java`

Add the constants near the top of the class (after `READER_THREADS`):

```java
    /** Total write attempts on transient (busy/locked) failures = 1 + WRITE_RETRIES. */
    private static final int WRITE_RETRIES = 3;
    private static final long[] WRITE_BACKOFF_MS = {50L, 100L, 200L};
```

Replace `execute` and `submitWrite` so the work runs through the retry runner:

```java
    /** A write. Always runs on the single writer thread, keeping write/batch ordering stable. */
    public void execute(Runnable work) {
        writer.execute(() -> {
            try {
                database.ensureValid();
                database.bind(database.connection());
                runWithRetry(() -> { work.run(); return null; });
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed (operation dropped)", t);
            } finally {
                database.bind(null);
            }
        });
    }
```

In `submitWrite`, change the `f.complete(work.call());` line to:

```java
                f.complete(runWithRetry(work));
```

Add the two helpers (place them after `submitWrite`):

```java
    /**
     * Runs a write, retrying ONLY on transient failures (SQLITE_BUSY / SQLITE_LOCKED) with a short
     * backoff. A transient failure means the statement never acquired the lock and so did not modify
     * the database — re-running it cannot double-apply. Non-transient errors (constraint, syntax, I/O)
     * are rethrown immediately. Runs on the single writer thread, so the backoff only briefly delays
     * later queued writes — never the server tick.
     */
    private <T> T runWithRetry(Callable<T> work) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return work.call();
            } catch (Exception e) {
                if (!isTransient(e) || attempt >= WRITE_RETRIES) throw e;
                try {
                    Thread.sleep(WRITE_BACKOFF_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e; // abort the retry; surface the original failure
                }
                attempt++;
            }
        }
    }

    /** True for SQLite "database is busy/locked" conditions, which are safe and worthwhile to retry. */
    static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql) {
                int code = sql.getErrorCode();
                if (code == 5 || code == 6) return true; // SQLITE_BUSY / SQLITE_LOCKED
            }
            String msg = c.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(java.util.Locale.ROOT);
                if (m.contains("busy") || m.contains("locked")) return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.storage.DatabaseExecutorTest'`
Expected: PASS (all existing + 4 new; existing failure tests still pass because `SQLException("boom")` and `RuntimeException("boom")` are non-transient → single attempt).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java \
        src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java
git commit -m "feat: bounded retry for transient DB write failures"
```

---

### Task 5: Version bump to 3.1.4 + full build

**Files:**
- Modify: `build.gradle.kts:8`
- Modify: `src/main/resources/plugin.yml:2`

- [ ] **Step 1: Bump the version in `build.gradle.kts`**

Change line 8 from `version = "3.1.3"` to:

```kotlin
version = "3.1.4"
```

- [ ] **Step 2: Bump the version in `plugin.yml`**

Change line 2 from `version: '3.1.3'` to:

```yaml
version: '3.1.4'
```

- [ ] **Step 3: Run the full build (tests + spotless + shadowJar)**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`; produces `build/libs/Sentinel-3.1.4.jar`; `spotlessCheck` passes (no unused imports).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/plugin.yml
git commit -m "release: v3.1.4"
```

(Actual `gh release create` publishing happens after the branch is merged, in the finishing step — not in this plan. Release notes describe only generic reliability hardening; no auto-updater or owner mention.)

---

## Self-Review

**Spec coverage:**
- Item 1 (atomic/validated backups, prune delete check, w.save stays on-thread) → Task 1. ✓
- Item 2 (atomic messages.yml save; config.yml left as-is) → Task 2. ✓
- Item 3 (ConfigValidator clamps to defaults, in-memory only) → Task 3. ✓
- Item 4 (bounded retry on transient writes, reads unchanged, idempotency rationale) → Task 4. ✓
- Release (v3.1.4 in both files, build green, secrecy in notes) → Task 5. ✓
- Out-of-scope items (game-state consistency, off-thread w.save, dead-letter, config.yml atomic, read retries) → not implemented, as required. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N"; every code step shows complete code. ✓

**Type consistency:** `validateZip(File)`, `moveIntoPlace(File,File)`, `saveYamlAtomically(FileConfiguration,File)`, `isTransient(Throwable)`, `runWithRetry(Callable<T>)`, constants `WRITE_RETRIES`/`WRITE_BACKOFF_MS` are used consistently across each task's implementation and tests. Retry math verified: attempts at index 0,1,2,3 → throws at `attempt>=3` after sleeping `WRITE_BACKOFF_MS[0..2]`, so 1+3=4 total attempts and no array-index overflow. ✓
