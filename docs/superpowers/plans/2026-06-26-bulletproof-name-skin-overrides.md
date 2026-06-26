# Bulletproof Name & Skin Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make staff-set name/skin overrides survive every runtime condition — a self-healing reconciliation task repairs lost floating nametags, extra listeners cover the common detach events instantly, and the Mojang skin fetch retries with a short-lived texture cache.

**Architecture:** Pure Paper API, no NMS/ProtocolLib, no new dependencies. A repeating `globalTimer` walks only nicked online players and re-runs the already-self-correcting `NametagManager.refresh()` plus a tab/chat name re-assert. `ProfileManager` gains a static retry helper and a TTL texture cache around `PlayerProfile.complete(true)`. `NametagListener` gains teleport + vehicle handlers.

**Tech Stack:** Java 21, Paper API 1.21.11, JUnit 5 (`junit-jupiter:5.11.3`), MockBukkit (`mockbukkit-v1.21:4.110.0`). Build/test: `./gradlew test`.

## Global Constraints

- Minecraft / Paper API floor: **1.21.11** — Paper API only, **no NMS, no ProtocolLib, no new dependencies**.
- All entity mutations run on the entity thread (`scheduler().runForEntity`), server-wide/scoreboard state on `scheduler().runGlobal`, network/DB off-thread (`scheduler().runAsync`). The scheduler is Folia-aware — never touch Bukkit state directly off-thread.
- DB writes route through `plugin.db().submitWrite(...)` / `plugin.db().execute(...)`, never `submit(...)`.
- The GameProfile login **name** stays the real account name (avoids vanilla "(formerly known as …)"); the display name is applied live only.
- Vanish is sacred: a vanished player must never show a floating name. `refresh()` already enforces this — do not bypass it.
- Reconciliation interval: **40 ticks (~2s)**. Skin fetch: **3 attempts**, backoff **0 / 250 / 750 ms**. Texture cache TTL: **5 minutes**.

---

### Task 0: Hide the floating nametag from its own owner (fixes the reported follow bug)

**Do this task first — it fixes a real, observed bug.** The floating `TextDisplay` is mounted as a *passenger* of the player. The owner's client predicts its own movement locally, but the passenger's position is server-driven, so on the owner's own screen the name lags / stays in mid-air; for everyone else (player + passenger both server-positioned) it follows correctly. The fix — also the vanilla-correct behaviour, since you never see your own above-head name — is to hide the display entity from its owner with `Player#hideEntity(Plugin, Entity)`.

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/NametagManager.java`

**Interfaces:**
- Consumes: `org.bukkit.entity.Player#hideEntity(org.bukkit.plugin.Plugin, org.bukkit.entity.Entity)` (Paper API, present on 1.21.11).
- Produces: no new API — a behaviour change inside `NametagManager.show(...)`.

- [ ] **Step 1: Hide the display from its owner right after mounting it**

In `NametagManager.show(...)`, locate the two lines that mount the freshly-spawned display:

```java
                player.addPassenger(td); // ride the player so it follows with no per-tick work
                displays.put(player.getUniqueId(), td.getUniqueId());
```

Replace them with:

```java
                player.addPassenger(td); // ride the player so it follows with no per-tick work
                // The owner's own client predicts its movement locally while the passenger is server-driven,
                // so to the owner the name would lag / hang in the air. Hide it from the owner (vanilla never
                // shows you your own above-head name); everyone else sees it follow normally.
                player.hideEntity(plugin, td);
                displays.put(player.getUniqueId(), td.getUniqueId());
```

- [ ] **Step 2: Build to verify it compiles against Paper 1.21.11**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual check (cannot be asserted under MockBukkit — no TextDisplayMock)**

On a real server: SETNAME on yourself, then walk/run/jump. The floating name must no longer appear stuck in the air **for you** (you should not see your own floating name at all), while a second player still sees it follow your head smoothly.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/NametagManager.java
git commit -m "fix: hide own floating nametag from its owner (lagged/hung in air locally)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 1: Skin-fetch retry helper

Adds a static, unit-testable retry wrapper around a profile-completion attempt. No behaviour wired in yet — that happens in Task 2.

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface ProfileManager.ProfileCompleter { boolean complete(); }` (package-private nested)
  - `interface ProfileManager.Sleeper { void sleep(long millis) throws InterruptedException; }` (package-private nested)
  - `static final long[] ProfileManager.SKIN_FETCH_BACKOFF_MS` = `{0L, 250L, 750L}`
  - `static boolean ProfileManager.completeWithRetry(ProfileCompleter completer, Sleeper sleeper)` — tries up to 3 times (one per backoff entry), sleeping the backoff before attempts 2 and 3; returns `true` on first success, `false` if all attempts fail or throw, or the thread is interrupted.

- [ ] **Step 1: Write the failing tests**

Add these methods to the top-level body of `ProfileManagerTest` (the class already lives in package `de.derfakegamer.sentinel.manager`, so package-private members are visible):

```java
    @Test
    void skinFetchRetriesUntilSuccess() {
        int[] calls = {0};
        boolean ok = ProfileManager.completeWithRetry(() -> { calls[0]++; return calls[0] >= 2; }, ms -> {});
        assertTrue(ok, "succeeds once an attempt returns true");
        assertEquals(2, calls[0], "stops at the first successful attempt");
    }

    @Test
    void skinFetchGivesUpAfterThreeAttempts() {
        int[] calls = {0};
        boolean ok = ProfileManager.completeWithRetry(() -> { calls[0]++; return false; }, ms -> {});
        assertFalse(ok, "all attempts failed");
        assertEquals(3, calls[0], "exactly three attempts (matches the backoff table length)");
    }

    @Test
    void skinFetchTreatsAThrowAsAFailedAttempt() {
        int[] calls = {0};
        boolean ok = ProfileManager.completeWithRetry(() -> { calls[0]++; throw new RuntimeException("boom"); }, ms -> {});
        assertFalse(ok, "a throwing attempt must not abort the retry loop or escape");
        assertEquals(3, calls[0], "a throw counts as a failed attempt and retries continue");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.ProfileManagerTest'`
Expected: FAIL — compile error / `cannot find symbol: method completeWithRetry`.

- [ ] **Step 3: Add the helper to `ProfileManager`**

Insert these members into `ProfileManager` (place them just below the `NAME` pattern field near the top of the class):

```java
    // ---- skin fetch retry (Mojang completion is a flaky network call) ----

    /** One profile-completion attempt; returns true if the profile completed with usable textures. */
    @FunctionalInterface
    interface ProfileCompleter { boolean complete(); }

    /** Pluggable sleep so tests run without real delays. */
    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    // One entry per attempt; the value is the backoff slept BEFORE that attempt (so attempt 1 is immediate).
    static final long[] SKIN_FETCH_BACKOFF_MS = {0L, 250L, 750L};

    /**
     * Completes a profile with up to {@link #SKIN_FETCH_BACKOFF_MS}.length attempts, sleeping the backoff
     * before each retry. Async-only (it blocks the calling thread on the sleep). A throwing or false attempt
     * is retried; returns true on the first success, false if every attempt fails or the thread is interrupted.
     */
    static boolean completeWithRetry(ProfileCompleter completer, Sleeper sleeper) {
        for (long backoff : SKIN_FETCH_BACKOFF_MS) {
            if (backoff > 0) {
                try { sleeper.sleep(backoff); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            try { if (completer.complete()) return true; }
            catch (Throwable ignored) { /* transient: fall through to the next attempt */ }
        }
        return false;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.ProfileManagerTest'`
Expected: PASS (all ProfileManagerTest tests green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java
git commit -m "feat: retry helper for flaky Mojang skin fetch

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Texture cache + wire retry/cache into the skin paths

Adds a 5-minute texture cache keyed by source name and uses the Task 1 retry helper in both `setSkin` and the reset skin-restore path.

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java`

**Interfaces:**
- Consumes: `completeWithRetry` (Task 1).
- Produces:
  - `record ProfileManager.CachedTexture(String value, String signature, long fetchedAt)` (package-private nested)
  - `static final long ProfileManager.SKIN_CACHE_TTL_MS` = `5 * 60 * 1000L`
  - `static boolean ProfileManager.isFresh(long fetchedAt, long now)` — `now - fetchedAt < SKIN_CACHE_TTL_MS`
  - `CachedTexture ProfileManager.cacheGet(String key, long now)` — fresh entry or null (package-private)
  - `void ProfileManager.cachePut(String key, String value, String signature, long now)` (package-private)
  - `setSkin` unchanged signature: `void setSkin(Player, String sourceName, String staff, Consumer<Boolean> done)`.

- [ ] **Step 1: Write the failing test**

Add to the top-level body of `ProfileManagerTest`:

```java
    @Test
    void cachedSkinIsFreshOnlyWithinTtl() {
        assertTrue(ProfileManager.isFresh(1_000L, 1_000L), "same instant is fresh");
        assertTrue(ProfileManager.isFresh(1_000L, 1_000L + ProfileManager.SKIN_CACHE_TTL_MS - 1), "just inside the TTL is fresh");
        assertFalse(ProfileManager.isFresh(1_000L, 1_000L + ProfileManager.SKIN_CACHE_TTL_MS), "at the TTL boundary it is stale");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.ProfileManagerTest'`
Expected: FAIL — `cannot find symbol: method isFresh` / field `SKIN_CACHE_TTL_MS`.

- [ ] **Step 3: Add the cache members to `ProfileManager`**

Insert below the retry helper from Task 1:

```java
    // ---- texture cache (avoid re-hitting Mojang for repeated sets / survive a momentary outage) ----

    record CachedTexture(String value, String signature, long fetchedAt) {}

    static final long SKIN_CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private final java.util.concurrent.ConcurrentHashMap<String, CachedTexture> skinCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    static boolean isFresh(long fetchedAt, long now) { return now - fetchedAt < SKIN_CACHE_TTL_MS; }

    /** The cached textures for {@code key} if still fresh, else null. */
    CachedTexture cacheGet(String key, long now) {
        CachedTexture c = skinCache.get(key);
        return (c != null && isFresh(c.fetchedAt(), now)) ? c : null;
    }

    void cachePut(String key, String value, String signature, long now) {
        skinCache.put(key, new CachedTexture(value, signature, now));
    }
```

- [ ] **Step 4: Rewrite `setSkin` to use the cache + retry**

Replace the entire existing `setSkin(...)` method body with:

```java
    public void setSkin(org.bukkit.entity.Player target, String sourceName, String staff,
                        java.util.function.Consumer<Boolean> done) {
        java.util.UUID id = target.getUniqueId();
        plugin.scheduler().runAsync(() -> {
            String key = sourceName.toLowerCase(java.util.Locale.ROOT);
            long now = System.currentTimeMillis();
            String value;
            String signature;
            CachedTexture cached = cacheGet(key, now);
            if (cached != null) {
                value = cached.value();
                signature = cached.signature();
            } else {
                PlayerProfile src = org.bukkit.Bukkit.createProfile(sourceName);
                boolean ok = completeWithRetry(() -> src.complete(true), Thread::sleep);
                ProfileProperty tex = ok ? texturesOf(src) : null;
                if (tex == null) { plugin.scheduler().runGlobal(() -> done.accept(false)); return; }
                value = tex.getValue();
                signature = tex.getSignature();
                cachePut(key, value, signature, now);
            }
            final String fv = value;
            final String fs = signature;
            plugin.scheduler().runGlobal(() -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null || !t.isOnline()) { done.accept(false); return; }
                long ts = System.currentTimeMillis();
                // find + upsert on the single writer thread so the existing name override is preserved atomically.
                plugin.db().callbackFor(t, plugin.db().submitWrite(() -> {
                    de.derfakegamer.sentinel.model.ProfileOverride existing = dao.find(id);
                    String nm = existing != null ? existing.displayName() : null;
                    dao.upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, nm, fv, fs, staff, ts));
                    return nm;
                }), nm -> {
                    if (!t.isOnline()) { done.accept(false); return; }
                    applyLive(t, nm, fv, fs);
                    plugin.audit().record(staff, "SETSKIN", t.getName(), sourceName);
                    done.accept(true);
                });
            });
        });
    }
```

- [ ] **Step 5: Use the retry helper in the reset skin-restore path**

In `reset(...)`, inside the `runAsync` try-block, change the single-attempt completion to a retried one. Replace:

```java
                if (!real.complete(true)) return;
```

with:

```java
                if (!completeWithRetry(() -> real.complete(true), Thread::sleep)) return;
```

- [ ] **Step 6: Run the full test suite to verify it passes**

Run: `./gradlew test`
Expected: PASS — new `cachedSkinIsFreshOnlyWithinTtl` green; existing `ProfileManagerTest.LiveApply` (`setNameUpdates…`, `resetRemoves…`, `vanishHides…`) and `ProfileApplyOnLoginTest` / `ProfileLoginListenerTest` still green (setSkin signature and reset semantics unchanged).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java
git commit -m "feat: TTL texture cache + retried fetch in setSkin and reset

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Self-healing nametag reconciliation

Adds a ~2s repeating task that re-runs `refresh()` and re-asserts the tab/chat name for every nicked online player — the catch-all that repairs any nametag drift regardless of cause.

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/NametagManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java`

**Interfaces:**
- Consumes: `NametagManager.refresh(Player)`, `ProfileManager.overrideJoinName(UUID)`, `ProfileManager.renderName(String)`, `Scheduler.globalTimer / runForEntity`, `TaskHandle`.
- Produces:
  - `void ProfileManager.reassertNameDisplay(Player player)` — re-applies the cached override name to `playerListName`/`displayName` on the entity thread; no-op if no cached override or player offline.
  - `void NametagManager.startReconciliation()` / `void NametagManager.stopReconciliation()` — start/stop the repeating reconcile timer (idempotent).

- [ ] **Step 1: Write the failing test**

Add this test inside the `LiveApply` nested class in `ProfileManagerTest` (it has `server`, `plugin`, `flush()`, `plain(...)`):

```java
        @Test void reconciliationReassertsAnOverwrittenTabName() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            // A TAB/prefix plugin clobbers the tab + chat name after we set it.
            p.playerListName(net.kyori.adventure.text.Component.text("Hijacked"));
            p.displayName(net.kyori.adventure.text.Component.text("Hijacked"));

            plugin.profile().reassertNameDisplay(p); // what the reconciliation pass calls per player
            flush();

            assertEquals("Renamed", plain(p.playerListName()), "reconciliation restores the override tab name");
            assertEquals("Renamed", plain(p.displayName()), "reconciliation restores the override chat name");
        }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.ProfileManagerTest'`
Expected: FAIL — `cannot find symbol: method reassertNameDisplay`.

- [ ] **Step 3: Add `reassertNameDisplay` to `ProfileManager`**

Add this public method to `ProfileManager` (e.g. just after `applyNameOnJoin`):

```java
    /**
     * Re-applies the cached display-name override to the live tab + chat name (reconciliation). Uses the
     * cached name (no DB hit) and runs on the entity thread. No-op when there is no override or the player
     * is offline. The above-head floating name is handled separately by {@link NametagManager#refresh}.
     */
    public void reassertNameDisplay(org.bukkit.entity.Player player) {
        String name = joinNames.get(player.getUniqueId());
        if (name == null) return;
        net.kyori.adventure.text.Component rendered = renderName(name);
        plugin.scheduler().runForEntity(player, () -> {
            if (!player.isOnline()) return;
            player.playerListName(rendered);
            player.displayName(rendered);
        });
    }
```

- [ ] **Step 4: Add the reconciliation timer to `NametagManager`**

Add a field for the handle (next to the existing `displays` / `priorTeam` maps):

```java
    private de.derfakegamer.sentinel.scheduler.TaskHandle reconcileTask; // ~2s self-heal of lost nametags
```

Add these methods (e.g. just after the constructor):

```java
    /** Starts the ~2s self-healing pass; idempotent. Called once from plugin enable. */
    public void startReconciliation() {
        if (reconcileTask != null) return;
        reconcileTask = plugin.scheduler().globalTimer(this::reconcileAll, 40L, 40L); // 40 ticks ~= 2s
    }

    /** Stops the self-healing pass; idempotent. Called from {@link #disableAll()}. */
    public void stopReconciliation() {
        de.derfakegamer.sentinel.scheduler.TaskHandle t = reconcileTask;
        reconcileTask = null;
        if (t != null) try { t.cancel(); } catch (Throwable ignored) { }
    }

    // Catch-all repair: for every nicked online player, re-run refresh() (remounts a detached/dead
    // TextDisplay, re-asserts the no-nametag team, respects vanish) and re-assert the tab/chat name.
    private void reconcileAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.profile().overrideJoinName(p.getUniqueId()) == null) continue;
            try {
                refresh(p);
                plugin.profile().reassertNameDisplay(p);
            } catch (Throwable t) {
                plugin.getLogger().fine("nametag reconcile failed for " + p.getName() + ": " + t.getMessage());
            }
        }
    }
```

Then make `disableAll()` stop the timer — add as the FIRST line of the existing `disableAll()` method body:

```java
        stopReconciliation();
```

- [ ] **Step 5: Start the timer from `Sentinel` enable**

In `src/main/java/de/derfakegamer/sentinel/Sentinel.java`, immediately after the existing daily prune timer block (the `scheduler.asyncTimer(() -> punishmentManager.pruneWarns(...), 1_728_000L, 1_728_000L);` statement near line 114-115), add:

```java
        nametagManager.startReconciliation(); // ~2s self-heal of staff-set floating nametags
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: PASS — `reconciliationReassertsAnOverwrittenTabName` green; all existing tests still green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/NametagManager.java src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java src/main/java/de/derfakegamer/sentinel/Sentinel.java src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java
git commit -m "feat: self-healing reconciliation for staff-set nametags

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Fast event-path additions (teleport + vehicle)

Extends `NametagListener` so the common detach events re-apply the floating name instantly rather than waiting up to ~2s for the reconciliation pass.

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/NametagListener.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java`

**Interfaces:**
- Consumes: the existing private `NametagListener.reapplyNextTick(Player)`; `NametagManager.refresh(Player)`.
- Produces: new `@EventHandler` methods on `NametagListener` for `PlayerTeleportEvent`, `VehicleEnterEvent`, `VehicleExitEvent`. No new public API.

- [ ] **Step 1: Write the failing test**

Add this test inside the `LiveApply` nested class in `ProfileManagerTest`:

```java
        @Test void teleportKeepsTheCustomNametagActive() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            assertTrue(nickTeam().hasEntry("RealName"), "nametag active before teleport");

            org.bukkit.Location to = p.getLocation().clone().add(1000, 0, 1000);
            server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerTeleportEvent(p, p.getLocation(), to));
            flush();

            assertTrue(nickTeam().hasEntry("RealName"),
                "after a teleport the custom nametag is re-applied (vanilla stays suppressed)");
        }
```

(The plugin registers `NametagListener` on enable, so the fired event reaches the new handler. This is a smoke test: it proves the handler runs without error and the reconciled state holds; the floating `TextDisplay` itself is not assertable under MockBukkit — the no-nametag team membership is the established proxy.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.ProfileManagerTest'`
Expected: FAIL — compile error: no constructor/handler usage matches, OR the test passes only after the handler is added. If it already passes (because refresh state happened to persist), proceed — the implementation below is still required for the real-server fast path.

- [ ] **Step 3: Add the handlers to `NametagListener`**

Add these imports at the top of `NametagListener.java` (alongside the existing `PlayerChangedWorldEvent` / `PlayerRespawnEvent` imports):

```java
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
```

Add these handler methods to the class body (next to `onRespawn` / `onWorldChange`):

```java
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        // Long-distance / cross-world teleports can drop the mounted TextDisplay passenger.
        reapplyNextTick(event.getPlayer());
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        // Mounting a boat/horse/minecart re-stacks passengers and can eject the floating name.
        if (event.getEntered() instanceof Player player) reapplyNextTick(player);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) reapplyNextTick(player);
    }
```

- [ ] **Step 4: Run the full suite to verify it passes**

Run: `./gradlew test`
Expected: PASS — `teleportKeepsTheCustomNametagActive` green; all existing tests still green.

- [ ] **Step 5: Build the plugin jar**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — confirms the new imports/handlers compile against Paper 1.21.11.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/NametagListener.java src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java
git commit -m "feat: re-apply floating nametag on teleport and vehicle enter/exit

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Manual verification (on a real Paper 1.21.11 server)

After all tasks, build the jar (`./gradlew build`, artifact under `build/libs/`) and verify on a test server:

1. `/sn` admin panel → SETNAME on yourself → confirm the floating name shows above the head, tab + chat updated.
2. Die and respawn → floating name returns (existing path).
3. Teleport across worlds (`/mv tp` or `/execute in <dim>`) → floating name returns within a tick or two (new fast path).
4. Mount and dismount a boat/horse → floating name survives / is restored.
5. Force-eject the passenger (e.g. another plugin's `/ride dismount`) and wait ~2s → reconciliation re-mounts it (catch-all).
6. SETSKIN copying another player's skin → skin renders for other clients; repeat the same source within 5 min → no second Mojang lookup (cache hit; confirm via no network/log delay).
7. `/vanish` while nicked → floating name disappears immediately and stays gone across a reconciliation pass.
8. RESETPROFILE → vanilla name + skin return; prior TAB-team membership restored.

## Self-Review

- **Spec coverage:** §1 self-healing reconciliation → Task 3. §2 fast event additions (teleport, vehicle enter/exit) → Task 4. §3 skin retry-with-backoff → Task 1 (helper) + Task 2 (wired into setSkin & reset); texture cache (5-min TTL) → Task 2. §4 unchanged items (login injection, two-phase name, MiniMessage validation, vanish, audit, DB routing) → untouched by all tasks. §5 testing (retry helper, cache, manual nametag/skin) → Task 1/2/3/4 tests + Manual verification section. All spec sections covered.
- **Placeholder scan:** no TBD/TODO/"handle edge cases"; every code step shows full code; commands have expected output. Clean.
- **Type consistency:** `completeWithRetry(ProfileCompleter, Sleeper)` defined in Task 1, consumed identically in Task 2 (`completeWithRetry(() -> src.complete(true), Thread::sleep)`). `cacheGet(String,long)` / `cachePut(String,String,String,long)` / `isFresh(long,long)` / `SKIN_CACHE_TTL_MS` consistent across Task 2. `reassertNameDisplay(Player)` defined in Task 3, called from `reconcileAll` in the same task. `startReconciliation`/`stopReconciliation` defined and wired (Sentinel enable, `disableAll`) in Task 3. `reapplyNextTick(Player)` is the existing private method reused in Task 4. Consistent.
