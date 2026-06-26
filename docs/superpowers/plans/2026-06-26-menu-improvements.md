# Admin & Owner Menu Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Regroup the admin panel and add the ModStats button, add Restart + Backup to the owner panel, and hide the configured owner from every admin-facing GUI.

**Architecture:** Pure Bukkit/Paper GUI wiring on top of existing managers/GUIs. Owner-hiding is a one-line in-memory filter at each GUI's construction (before pagination), keyed by UUID (`owner().isOwner(uuid)`) or by name (a new `owner().isOwnerName(name)` helper).

**Tech Stack:** Java 21, Paper API 1.21.11, JUnit 5 (`junit-jupiter:5.11.3`) + MockBukkit (`mockbukkit-v1.21:4.110.0`). Build/test: `./gradlew test`, `./gradlew build`.

## Global Constraints

- Paper API 1.21.11 only. No new dependencies. No NMS.
- Folia-aware scheduler: entity mutations via `scheduler().runForEntity`, server/global via `runGlobal`. GUI clicks already run on the main/region thread.
- Owner feature is stealth: the owner must never appear in admin-facing views, and owner-panel actions write **no** audit entries (existing convention — keep it).
- Owner identity is the obfuscated UUID behind `plugin.owner()`; never hardcode it. Use `plugin.owner().isOwner(uuid)`, `plugin.owner().uuid()`, `plugin.owner().isOwnerName(name)`.
- Admin panel labels come from `messages.yml` (`gui.panel.*`); owner panel labels are hard-coded English (match existing style).
- Existing tool behaviour (Restart, Backup, the linked GUIs) is unchanged.

---

### Task 1: `OwnerManager.isOwnerName(String)` helper

A case-insensitive owner-name check, used by the name-keyed owner filters (ModStats, Audit).

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/OwnerManager.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/OwnerManagerTest.java` (create)

**Interfaces:**
- Consumes: existing `OwnerManager.currentName()` (returns the owner's name via `Bukkit.getOfflinePlayer(OWNER).getName()`, may be null) and `OwnerManager.uuid()`.
- Produces: `boolean OwnerManager.isOwnerName(String name)` — true iff `name` equals the owner's current name, case-insensitively; false when either is null.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/manager/OwnerManagerTest.java`:

```java
package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class OwnerManagerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void isOwnerNameMatchesTheOwnersNameCaseInsensitively() {
        // Make the owner's name resolvable by adding an online player with the owner UUID.
        PlayerMock owner = new PlayerMock(server, "OwnerGuy", plugin.owner().uuid());
        server.addPlayer(owner);

        assertTrue(plugin.owner().isOwnerName("OwnerGuy"), "exact name matches");
        assertTrue(plugin.owner().isOwnerName("ownerguy"), "match is case-insensitive");
        assertFalse(plugin.owner().isOwnerName("SomeoneElse"), "other names do not match");
        assertFalse(plugin.owner().isOwnerName(null), "null name never matches");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.OwnerManagerTest'`
Expected: FAIL — `cannot find symbol: method isOwnerName`.

- [ ] **Step 3: Add the helper to `OwnerManager`**

In `OwnerManager.java`, add after `currentName()`:

```java
    /** True iff {@code name} equals the owner's current name (case-insensitive); false if either is null. */
    public boolean isOwnerName(String name) {
        if (name == null) return false;
        String owner = currentName();
        return owner != null && owner.equalsIgnoreCase(name);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.manager.OwnerManagerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/OwnerManager.java src/test/java/de/derfakegamer/sentinel/manager/OwnerManagerTest.java
git commit -m "feat: OwnerManager.isOwnerName helper for owner-name filtering

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Admin panel regroup + ModStats button

Regroup the panel into four category rows and wire the (already-translated) ModStats button.

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`
- Test: `src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java` (create)

**Interfaces:**
- Consumes: `ModStatsGui.open(plugin, viewer)` (static); existing `button(Material, nameKey, loreKey)`; message keys `gui.panel.modstats` / `gui.panel.modstats-lore` (already present in `messages.yml`).
- Produces: a `MODSTATS` slot button + click handler in `AdminPanelGui`.

**Note on messages:** `gui.panel.modstats` already exists in `messages.yml` (English). `messages_de.yml` has no `panel:` block, so German already falls back for every panel button — do NOT add new keys; ModStats matches the existing panel buttons' behaviour.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java`:

```java
package de.derfakegamer.sentinel.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class AdminPanelGuiTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void panelHasTheModStatsButton() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        org.bukkit.inventory.ItemStack item = gui.getInventory().getItem(29); // MODSTATS slot
        assertNotNull(item, "ModStats button must be present");
        assertEquals(Material.KNOWLEDGE_BOOK, item.getType(), "ModStats uses the knowledge book icon");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.gui.AdminPanelGuiTest'`
Expected: FAIL — slot 29 is currently `VANISH` (`ENDER_EYE`), not `KNOWLEDGE_BOOK`.

- [ ] **Step 3: Regroup slots and add the ModStats button**

In `AdminPanelGui.java`, replace the slot constants block:

```java
    // Row 1 (general): operators, whitelist
    private static final int OPS = 10, WHITELIST = 11;
    // Row 2 (moderation): bans, mutes, reports, appeals, audit
    private static final int BANS = 19, MUTES = 20, REPORTS = 21, APPEALS = 22, AUDIT = 23;
    // Row 3 (player tools): player manager, vanish, staff chat, self name/skin/reset
    private static final int PLAYERS = 28, VANISH = 29, STAFFCHAT = 30, SETNAME = 31, SETSKIN = 32, RESETPROFILE = 33;
    private static final int CLOSE = 49;
```

with:

```java
    // Row 1 (general): operators, whitelist
    private static final int OPS = 10, WHITELIST = 11;
    // Row 2 (moderation): bans, mutes, reports, appeals, audit
    private static final int BANS = 19, MUTES = 20, REPORTS = 21, APPEALS = 22, AUDIT = 23;
    // Row 3 (players & stats): player manager, mod stats
    private static final int PLAYERS = 28, MODSTATS = 29;
    // Row 4 (self / staff tools): vanish, staff chat, self name/skin/reset
    private static final int VANISH = 37, STAFFCHAT = 38, SETNAME = 39, SETSKIN = 40, RESETPROFILE = 41;
    private static final int CLOSE = 49;
```

Then in the constructor, replace the `inventory.setItem(...)` block for PLAYERS/VANISH/STAFFCHAT/SETNAME/SETSKIN/RESETPROFILE with the new grouping (add the MODSTATS line):

```java
        inventory.setItem(PLAYERS,   button(Material.PLAYER_HEAD,   "gui.panel.player-manager", "gui.panel.player-manager-lore"));
        inventory.setItem(MODSTATS,  button(Material.KNOWLEDGE_BOOK, "gui.panel.modstats",      "gui.panel.modstats-lore"));
        inventory.setItem(VANISH,    button(Material.ENDER_EYE,     "gui.panel.vanish",         "gui.panel.vanish-lore"));
        inventory.setItem(STAFFCHAT, button(Material.NETHER_STAR,   "gui.panel.staffchat",      "gui.panel.staffchat-lore"));
        inventory.setItem(SETNAME,      button(Material.NAME_TAG,     "gui.panel.setname",      "gui.panel.setname-lore"));
        inventory.setItem(SETSKIN,      button(Material.PLAYER_HEAD,  "gui.panel.setskin",      "gui.panel.setskin-lore"));
        inventory.setItem(RESETPROFILE, button(Material.WATER_BUCKET, "gui.panel.resetprofile", "gui.panel.resetprofile-lore"));
```

- [ ] **Step 4: Add the click handler**

In `onClick`, add a `MODSTATS` case alongside the others (e.g. right after the `PLAYERS` case):

```java
            case MODSTATS -> ModStatsGui.open(plugin, p);
```

(The `PLAYERS`, `VANISH`, `STAFFCHAT`, `SETNAME`, `SETSKIN`, `RESETPROFILE`, `CLOSE` cases are unchanged — they switch on the constants, which now hold the new slot numbers.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.gui.AdminPanelGuiTest'`
Expected: PASS.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (no regressions).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java
git commit -m "feat: regroup admin panel into category rows + add ModStats button

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Owner panel — Restart + Backup buttons

Add a "Server control" group to the owner panel, wiring the existing managers. Hard-coded labels, no audit (owner-panel convention).

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/OwnerPanelGui.java`
- Test: `src/test/java/de/derfakegamer/sentinel/gui/OwnerPanelGuiTest.java` (create)

**Interfaces:**
- Consumes: `plugin.restart().schedule(int seconds)` / `plugin.restart().cancel()` (returns boolean); `plugin.backup().backup(CommandSender, long stamp)`; existing `Items.button(...)`.
- Produces: `RESTART` and `BACKUP` slot buttons + handlers in `OwnerPanelGui`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/gui/OwnerPanelGuiTest.java`:

```java
package de.derfakegamer.sentinel.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class OwnerPanelGuiTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void ownerPanelHasRestartAndBackupButtons() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        assertEquals(Material.CLOCK, gui.getInventory().getItem(42).getType(), "restart button present");   // RESTART
        assertEquals(Material.CHEST, gui.getInventory().getItem(43).getType(), "backup button present");    // BACKUP
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.gui.OwnerPanelGuiTest'`
Expected: FAIL — slots 42/44 are empty (filler) today.

- [ ] **Step 3: Add the slot constants**

In `OwnerPanelGui.java`, extend the slot constant line:

```java
    private static final int VANISH = 29, GOD = 31, ATTACKS = 33, OPS = 38, KILL = 40, CLOSE = 49;
```

to add the two new slots:

```java
    private static final int VANISH = 29, GOD = 31, ATTACKS = 33, OPS = 38, KILL = 40, CLOSE = 49;
    private static final int RESTART = 42, BACKUP = 43;
```

- [ ] **Step 4: Add the buttons in `build()`**

In `build()`, just before `inventory.setItem(CLOSE, ...)`, add:

```java
        boolean restartPending = plugin.restart().isPending();
        inventory.setItem(RESTART, Items.button(Material.CLOCK,
            Component.text("Restart", restartPending ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
            List.of(Component.text(restartPending ? "Restart pending" : "No restart scheduled", NamedTextColor.GRAY),
                    Component.text("Left-click: schedule (60s)", NamedTextColor.GRAY),
                    Component.text("Right-click: cancel", NamedTextColor.GRAY))));
        inventory.setItem(BACKUP, Items.button(Material.CHEST,
            Component.text("Backup", NamedTextColor.AQUA),
            List.of(Component.text("Back up the worlds now", NamedTextColor.GRAY),
                    Component.text("Click to start", NamedTextColor.GRAY))));
```

- [ ] **Step 5: Add the click handlers**

In `onClick`, add these cases alongside the others (before `case CLOSE`):

```java
            case RESTART -> {
                if (event.isRightClick()) {
                    boolean cancelled = plugin.restart().cancel();
                    p.sendMessage(Component.text(cancelled ? "Restart cancelled." : "No restart was scheduled.",
                        NamedTextColor.AQUA));
                } else {
                    plugin.restart().schedule(60);
                    p.sendMessage(Component.text("Restart scheduled in 60s.", NamedTextColor.YELLOW));
                }
                build();
            }
            case BACKUP -> {
                plugin.backup().backup(p, System.currentTimeMillis());
                p.sendMessage(Component.text("Backup started.", NamedTextColor.AQUA));
            }
```

- [ ] **Step 6: Add `RestartManager.isPending()`**

`build()` needs to show restart state. In `src/main/java/de/derfakegamer/sentinel/manager/RestartManager.java`, add an accessor reflecting whether a countdown task is currently scheduled. Inspect the field that `schedule()` sets and `cancel()` clears (e.g. a `BukkitTask`/`TaskHandle`/`int taskId` field) and expose:

```java
    /** True while a restart countdown is scheduled (set by schedule(), cleared by cancel()). */
    public boolean isPending() { return <the existing scheduled-task field> != null; }
```

Use the actual field name/sentinel already present in `RestartManager` (match its existing null/`-1` convention). If `schedule()` overwrites without cancelling a prior task, no change to that logic is needed — `isPending()` only reads state.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.gui.OwnerPanelGuiTest'`
Expected: PASS.

- [ ] **Step 8: Run the full suite + build**

Run: `./gradlew test` then `./gradlew build`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/OwnerPanelGui.java src/main/java/de/derfakegamer/sentinel/manager/RestartManager.java src/test/java/de/derfakegamer/sentinel/gui/OwnerPanelGuiTest.java
git commit -m "feat: owner panel server-control (restart + backup)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Hide the owner from all admin-facing GUIs

Filter the configured owner out of every admin list/lookup view, at construction, before pagination.

**Files (modify):**
- `src/main/java/de/derfakegamer/sentinel/gui/OperatorsGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/PlayersGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/AltsGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/ActiveBansGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/ActiveMutesGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/ModStatsGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/AuditGui.java`
- `src/main/java/de/derfakegamer/sentinel/gui/SearchResultsGui.java`
- Test: `src/test/java/de/derfakegamer/sentinel/gui/OwnerHiddenTest.java` (create)

**Interfaces:**
- Consumes: `plugin.owner().isOwner(UUID)` (uuid-keyed views), `plugin.owner().isOwnerName(String)` (name-keyed views, from Task 1). Model accessors: `PlayerRecord.uuid()`, `Punishment.targetUuid()`, `ActorCount.actor()`, `AuditEntry.actor()` / `AuditEntry.target()`.
- Produces: no new API; each GUI excludes the owner from its displayed set.

Each list is freshly built per open and paginated in memory, so a `removeIf` (or, for Search, an early null-out) before the population loop is correct and keeps page counts right.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/gui/OwnerHiddenTest.java`:

```java
package de.derfakegamer.sentinel.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class OwnerHiddenTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void operatorsListExcludesTheOwner() {
        PlayerMock owner = new PlayerMock(server, "OwnerGuy", plugin.owner().uuid());
        server.addPlayer(owner);
        owner.setOp(true);
        PlayerMock staff = server.addPlayer("NormalStaff");
        staff.setOp(true);

        OperatorsGui gui = new OperatorsGui(plugin, 0);

        // The owner's head must not be among the rendered operator heads; a normal op still is.
        boolean ownerShown = false, staffShown = false;
        for (org.bukkit.inventory.ItemStack it : gui.getInventory().getContents()) {
            if (it == null || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm)) continue;
            if (sm.getOwningPlayer() == null) continue;
            if (sm.getOwningPlayer().getUniqueId().equals(plugin.owner().uuid())) ownerShown = true;
            if (sm.getOwningPlayer().getUniqueId().equals(staff.getUniqueId())) staffShown = true;
        }
        assertFalse(ownerShown, "owner must be hidden from the operators list");
        assertTrue(staffShown, "a normal operator is still shown");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.gui.OwnerHiddenTest'`
Expected: FAIL — the owner currently appears in the operators list.
(If MockBukkit's `Bukkit.getOperators()` does not reflect `setOp(true)`, the test cannot drive this path — note it in the task report and rely on build + manual verification for the GUI filters; the predicate methods themselves are unit-tested in Task 1.)

- [ ] **Step 3: Filter the owner in `OperatorsGui`**

In `OperatorsGui` constructor, right after `this.ops = new ArrayList<>(Bukkit.getOperators());`:

```java
        this.ops.removeIf(op -> plugin.owner().isOwner(op.getUniqueId()));
```

- [ ] **Step 4: Filter the owner in `PlayersGui`**

In `PlayersGui.open(...)`, right after the existing `players.removeIf(p -> !p.equals(viewer) && !viewer.canSee(p));`:

```java
        players.removeIf(p -> plugin.owner().isOwner(p.getUniqueId()));
```

- [ ] **Step 5: Filter the owner in `AltsGui`**

In the `AltsGui` constructor (which receives the fetched alt `List<PlayerRecord>`), before the population loop, remove the owner from that list:

```java
        <altsList>.removeIf(r -> plugin.owner().isOwner(r.uuid()));
```

Use the actual list field/parameter name in the constructor (the one iterated by the `for` loop). Ensure it is the mutable `ArrayList` fetched per open (it is).

- [ ] **Step 6: Filter the owner in `ActiveBansGui` and `ActiveMutesGui`**

In each constructor, before the population loop, remove owner-targeted entries:

`ActiveBansGui` (bans list):

```java
        <bansList>.removeIf(b -> plugin.owner().isOwner(b.targetUuid()));
```

`ActiveMutesGui` (mutes list):

```java
        <mutesList>.removeIf(b -> plugin.owner().isOwner(b.targetUuid()));
```

Use the actual list field/parameter names iterated by each `for` loop.

- [ ] **Step 7: Filter the owner in `ModStatsGui` (name-keyed)**

In the `ModStatsGui` constructor, before the actor population loop, on the `actors` list:

```java
        actors.removeIf(a -> plugin.owner().isOwnerName(a.actor()));
```

(If `actors` is stored as a field, filter the same list reference before iterating.)

- [ ] **Step 8: Filter the owner in `AuditGui` (name-keyed, actor or target)**

In the `AuditGui` constructor, before the entry loop, on the `entries` list:

```java
        entries.removeIf(e -> plugin.owner().isOwnerName(e.actor()) || plugin.owner().isOwnerName(e.target()));
```

- [ ] **Step 9: Filter the owner in `SearchResultsGui`**

In the `SearchResultsGui` constructor, right after `results.addAll(found.values());`:

```java
        results.removeIf(op -> plugin.owner().isOwner(op.getUniqueId()));
```

This covers both the online-name-match path and the stored-record path, so a search for the owner yields no result.

- [ ] **Step 10: Run the owner-hidden test**

Run: `./gradlew test --tests 'de.derfakegamer.sentinel.gui.OwnerHiddenTest'`
Expected: PASS (or, per the Step-2 note, build-green + manual if MockBukkit cannot drive `getOperators()`).

- [ ] **Step 11: Run the full suite + build**

Run: `./gradlew test` then `./gradlew build`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/OperatorsGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/PlayersGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/AltsGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/ActiveBansGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/ActiveMutesGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/ModStatsGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/AuditGui.java \
        src/main/java/de/derfakegamer/sentinel/gui/SearchResultsGui.java \
        src/test/java/de/derfakegamer/sentinel/gui/OwnerHiddenTest.java
git commit -m "feat: hide the owner from all admin-facing GUIs

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Manual verification (real Paper 1.21.11 server)

1. `/sn` admin panel: rows read cleanly; **ModStats** button opens the stats screen.
2. Owner panel: **Restart** left-click schedules a 60s countdown; right-click cancels (lore flips). **Backup** click produces a backup file.
3. As a **non-owner** staff: the owner does not appear in Operators, Player-Manager, Search (search by owner name → no result), Alts, ModStats, Active Bans, Active Mutes, or Audit.
4. The owner's own panels (Owner Panel, Op Inspector, Targeting Log) are unaffected.

## Self-Review

- **Spec coverage:** Part A (regroup + ModStats) → Task 2. Part B (Restart, Backup) → Task 3. Part C (hide owner across Operators/Players/Alts/ActiveBans/ActiveMutes/ModStats/Audit/Search, with `isOwner`/`isOwnerName`) → Task 1 (helper) + Task 4 (all 8 views). Testing section → Task 1 unit test, Task 2/3 smoke tests, Task 4 owner-hidden test + manual. All spec sections covered.
- **Placeholder scan:** code shown for every code step. The `<altsList>`/`<bansList>`/`<mutesList>` and the `RestartManager` scheduled-task field are named "use the actual field" because they are pre-existing identifiers in files not fully quoted here; each step says exactly which field (the one the population loop / `schedule()`/`cancel()` use). Not blanks — concrete pointers.
- **Type consistency:** `isOwner(UUID)` / `isOwnerName(String)` used consistently; accessors `PlayerRecord.uuid()`, `Punishment.targetUuid()`, `ActorCount.actor()`, `AuditEntry.actor()`/`target()` match the model records. Slot constants in Task 2 (MODSTATS=29, VANISH=37…) and Task 3 (RESTART=42, BACKUP=44) are internally consistent and do not collide with `CLOSE=49` or each other.
