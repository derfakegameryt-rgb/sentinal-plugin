# Bulletproof Hardening (v3.1.4) — Design Spec

**Date:** 2026-06-24
**Status:** Approved
**Goal:** Eliminate four robustness gaps surfaced by a code audit — non-atomic backups, non-atomic message-file writes, non-clamping config validation, and dropped DB writes under transient contention — without changing any feature behaviour or config schema.

## Background

A four-dimension audit (file I/O, DB layer, concurrency/lifecycle, input validation) of the Sentinel plugin was run and its findings verified against the real code. Several alarming-sounding findings were confirmed **already handled** and are explicitly out of scope:

- Scheduled tasks ARE cancelled on disable — `onDisable()` calls `scheduler.cancelAll()` → `Bukkit.getScheduler().cancelTasks(plugin)`, which cancels every plugin task. Managers need not store handles.
- Reload does NOT leave stale listeners — listeners read managers live via `plugin.chatModeration()` / `plugin.punishments()` (volatile fields) on every event; `reloadAll()` swaps those fields and the change takes effect immediately.
- `onEnable` does not half-initialize with active listeners — all managers are constructed before any listener is registered, so a manager constructor throwing aborts before listeners exist.
- `BatchWriter` dropping a snapshot on flush failure is a documented fail-soft tradeoff with a hard cap; left as-is.

The four items below are the genuine, verified gaps.

## Global Constraints

- Target Minecraft 1.21 (Paper/Folia), Java 21. Build with Gradle + shadow; `spotlessCheck` runs in `build` and FAILS on unused imports (4-space indent, no reformat).
- No new dependencies.
- No config-schema changes, no message-key removals, no feature behaviour changes. This is a pure patch release.
- All scheduling goes through `plugin.scheduler()` (Folia-safe); never `Bukkit.getScheduler()` directly.
- DB writes use `execute()` / `submitWrite()`; reads use `submit()`. Do not change this routing.
- Version bumped to **3.1.4** in BOTH `build.gradle.kts` (line 8) and `src/main/resources/plugin.yml` (line 2) as the final step.
- Tests use JUnit 5; server-dependent paths use MockBukkit (`org.mockbukkit.mockbukkit:mockbukkit-v1.21`). Prefer extracting pure helpers that are testable without a running server.

---

## Item 1 — Atomic, validated backups

**File:** `src/main/java/de/derfakegamer/sentinel/manager/BackupManager.java`

**Problem:** `zipWorlds` writes the zip directly to its final path (`backup-<stamp>.zip`). If zipping aborts (disk full, crash, a world file vanishing mid-copy), a truncated/corrupt zip is left on disk and the `catch` block does NOT delete it — the admin believes a backup exists when it does not. `prune()` ignores `File.delete()` failures, so a locked old backup silently survives and the backups folder grows unbounded.

**Design:**

1. `zipWorlds(List<File> dirs, File zip)` writes to a sibling temp file `new File(zip.getParentFile(), zip.getName() + ".part")`, then:
   - closes the `ZipOutputStream` (via try-with-resources),
   - calls `validateZip(part)`,
   - calls `moveIntoPlace(part, zip)`,
   - in a `finally`, `Files.deleteIfExists(part.toPath())` (a no-op once the atomic move succeeded).
2. `static void validateZip(File f) throws IOException` — opens `new java.util.zip.ZipFile(f)` (throws on a truncated/corrupt archive), iterates `entries()` to force the central directory to be read, and requires at least one entry. Throws `IOException` describing the failure otherwise. Rationale: this proves the archive itself is complete and readable; it does not (and cannot cheaply) verify per-world game-state consistency.
3. `static void moveIntoPlace(File tmp, File dest) throws IOException` — `Files.move(tmp, dest, ATOMIC_MOVE, REPLACE_EXISTING)`, catching `AtomicMoveNotSupportedException` and falling back to `Files.move(tmp, dest, REPLACE_EXISTING)`. Mirrors `UpdateChecker.moveIntoPlace` exactly (same semantics, separate copy — these are different subsystems, no shared util introduced).
4. `prune(File dir, int keep)` — check the boolean return of `delete()`; on `false`, `plugin.getLogger().warning(...)` naming the file. (Prune is not on a hot path; a warning is the appropriate signal.)
5. `w.save()` stays on the global/region thread inside `runGlobal(...)` (Bukkit requires world saves on the main/region thread). Documented with a comment; NOT moved off-thread.

**Interfaces:** `zipWorlds` keeps its signature (package-private, called from `backup`). `validateZip` and `moveIntoPlace` are `static`, package-private, for direct unit testing. No change to the public `backup(CommandSender, long)` signature.

**Failure handling:** Any `IOException` from zipping/validation/move propagates to the existing `catch (Exception e)` in `backup()`, which already reports `backup-failed` to the requester. The temp file is always cleaned up. No partial `.zip` ever appears.

**Tests (`BackupManagerTest`, new):**
- `validateZip` accepts a well-formed zip with ≥1 entry.
- `validateZip` rejects a truncated/garbage file (non-zip bytes).
- `validateZip` rejects an empty (zero-entry) zip.
- `zipWorlds` produces the final `.zip` and leaves no `.part` behind on success.
- `zipWorlds` leaves NO final `.zip` and NO `.part` when a source becomes unreadable mid-zip (simulate by passing a dir containing a path that fails to copy) — i.e. the destination is never created from a failed run. (If simulating a mid-copy failure is impractical, assert the temp-then-move ordering by verifying that a pre-existing stale `.part` is cleaned up and that a successful run's bytes equal a direct zip of the inputs.)
- `prune` keeps newest `keep`, deletes the rest, and returns/logs on a delete failure (use a read-only file or a directory entry to force `delete()==false`; assert via a captured log handler).

---

## Item 2 — Atomic YAML save for `messages.yml`

**File:** `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (`mergeMessagesDefaults`, around line 311)

**Problem:** `onDisk.save(file)` writes the merged messages YAML directly to the admin's `messages.yml`. A crash or disk-full mid-write truncates the file; on next load the admin's custom translations are lost (parse fails → English fallback).

**Design:**

- Add a private helper:
  ```java
  private void saveYamlAtomically(org.bukkit.configuration.file.FileConfiguration cfg, File dest) throws java.io.IOException {
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
- `mergeMessagesDefaults` calls `saveYamlAtomically(onDisk, file)` inside its existing `try { ... } catch (IOException e) { getLogger().warning("Could not migrate " + name + ": " + e.getMessage()); }`. Same catch, same warning — only the write becomes atomic.

**Scope note:** Only `messages.yml` (admin-customized, holds non-reproducible data). `config.yml`'s first-enable `saveConfig()` is left as Bukkit default — it is regenerated from bundled defaults, so a truncation is fully recoverable and not worth special-casing (YAGNI).

**Tests (`AtomicYamlSaveTest` or fold into an existing Sentinel/Messages test):**
- Saving a config to a fresh dest writes the expected keys and leaves no `.tmp`.
- Saving over an existing dest replaces it (new content present) and leaves no `.tmp`.
- Since MockBukkit is needed to construct `Sentinel`, prefer testing the move semantics via a small static/extracted equivalent if the helper can be made static; otherwise test through a MockBukkit-loaded plugin reflection-free entry point. (Implementer may extract the move into a package-private static `moveIntoPlace`-style helper if that makes it unit-testable; keep it independent from BackupManager's copy.)

---

## Item 3 — ConfigValidator clamps invalid values

**File:** `src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java`

**Problem:** `checkNonNegativeInt` (and the other range checks) only log a warning when a value is out of range; the bad value stays in the config and is read verbatim by callers (e.g. a negative `report.cooldown-seconds` → negative ms → cooldown effectively disabled). `validate()` runs at `onEnable` before any manager reads config.

**Design:**

- For each numeric range check (`checkNonNegativeInt` and any sibling like a positive-int / interval check present in the file), when the value violates the constraint:
  1. log the existing warning, AND
  2. `cfg.set(path, fallback)` where `fallback` is the method's existing default/clamp target (the same default the check already references). This mutates the **in-memory** `FileConfiguration` only — the on-disk file is untouched.
- Result: every downstream `getInt(path, ...)` returns the safe clamped value.
- Clamp target: the value the check already treats as the floor/default (e.g. for a non-negative check, clamp a negative value to its documented default rather than to a raw `0`, to match the config's intended baseline). The implementer uses whatever default constant/argument that method already has; do not invent new magic numbers.
- Apply consistently to all numeric validators in the file; string/enum validators are unchanged (no clamp concept).

**Tests (extend existing `ConfigValidatorTest` if present, else new `ConfigValidatorClampTest`):**
- A negative int at a validated path is replaced in the returned config by the default, and a warning is emitted.
- A valid int is left untouched (no mutation, no warning).
- The on-disk file is not required to change (we only assert the in-memory `FileConfiguration` value).

---

## Item 4 — Bounded retry for DB writes

**File:** `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java` (`execute`, `submitWrite`)

**Problem:** `execute()` logs a failed write as "operation dropped" and `submitWrite()` completes exceptionally on the first failure. Under transient SQLite contention (`SQLITE_BUSY` / `SQLITE_LOCKED`), a write that could have succeeded on a quick retry is lost — for a moderation plugin, a dropped ban/mute write means the punishment silently does not persist.

**Design:**

- Add a private retry helper on the writer path:
  ```java
  private static final int WRITE_RETRIES = 3;            // total attempts = 1 + retries
  private static final long[] BACKOFF_MS = {50L, 100L, 200L};
  ```
- A `runWriteWithRetry(Callable<T>)` (used by `submitWrite`) and `runWriteWithRetry(Runnable)` (used by `execute`, or implement `execute` in terms of the Callable form returning `null`) that:
  1. binds the connection (as today),
  2. attempts `work`; on success returns,
  3. on a **transient** exception, sleeps `BACKOFF_MS[attempt]` and retries, up to `WRITE_RETRIES` times,
  4. on a **non-transient** exception, or after retries are exhausted, rethrows so the existing catch logs SEVERE / completes the future exceptionally.
- `static boolean isTransient(Throwable t)` — walk the cause chain; return true if any cause is a `java.sql.SQLException` whose `getErrorCode()` is 5 (`SQLITE_BUSY`) or 6 (`SQLITE_LOCKED`), OR whose message (lower-cased) contains `"busy"` or `"locked"`. This stays dialect-agnostic (works for the relocated xerial SQLite driver without importing it) and also covers MySQL lock-wait timeouts loosely via the message check.
- The backoff sleep runs on the single writer thread (`Sentinel-DB`), which is correct: it is a background thread, never the server tick, and serializing writes means a brief sleep only delays subsequent queued writes slightly — acceptable for durability. `InterruptedException` during backoff → restore the interrupt flag and abort the retry (treat as failure).
- Idempotency: a `SQLITE_BUSY`/`SQLITE_LOCKED` failure means the statement never acquired the lock and therefore did not modify the database, so re-running it cannot double-apply. We deliberately do NOT retry non-transient errors (constraint violations, syntax, disk I/O) — those are not safe or useful to repeat.
- Reads (`submit`) are unchanged (out of scope; a failed read surfaces `db-error` to the user, no durability loss).

**Tests (`DatabaseExecutorRetryTest`, new — or extend existing executor test):**
- A write whose `work` throws a transient `SQLException` (errorCode 5) twice, then succeeds, ultimately runs and (for `submitWrite`) completes the future normally. Assert attempt count == 3.
- A write whose `work` throws a non-transient `SQLException` (e.g. errorCode 19 constraint) is attempted exactly once and the future completes exceptionally / `execute` logs and drops.
- A write that throws transient every time is attempted exactly `1 + WRITE_RETRIES` times and then fails.
- `isTransient` returns true for errorCode 5/6 and for messages containing "database is locked"; false for a generic `RuntimeException`.
- Use a fake `Database` (the executor's existing test seam) so no real SQLite is required; keep backoff fast (the test tolerates ~350ms total, or the implementer parameterizes backoff for tests if trivial — but do NOT change production timings).

---

## Release

After all four items pass review and the full suite is green:

- Bump `version` to `3.1.4` in `build.gradle.kts:8` and `src/main/resources/plugin.yml:2`.
- `./gradlew clean build` (must be BUILD SUCCESSFUL, spotlessCheck included).
- Release notes describe ONLY generic reliability hardening (backups, config, internal DB robustness) — consistent with the secrecy cleanup already done: **no mention of the auto-updater or owner role**.

## Out of Scope (explicitly)

- Backup game-state consistency (live worlds change during async zip) — inherent to hot backups; not addressed.
- Moving `w.save()` off the main thread — Bukkit forbids it.
- DB dead-letter persistence / write replay — rejected (writes are opaque lambdas; would need a large restructure). Bounded retry only.
- `config.yml` atomic save — recoverable from defaults; YAGNI.
- Read retries, BatchWriter re-queue, schema-migration transactions — not requested; left as documented fail-soft.
