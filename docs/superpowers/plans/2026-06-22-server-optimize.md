# One-Click Server Optimize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ServerInfoGui` button that recommends RAM-based view/simulation distance (current in red, recommended in green) and on click applies it to every world at runtime + persists to `server.properties`.

**Architecture:** A pure, testable `ServerOptimizer` holds the RAM→preset table and a line-based `server.properties` editor; `ServerInfoGui` gains the button (gated, audited, refreshes on apply).

**Tech Stack:** Java 21, Paper API (`World.setViewDistance/setSimulationDistance`), JUnit 5, MockBukkit.

## Global Constraints

- Preset table (max heap rounded to nearest GB; highest tier ≤ that; <1 GB → 1 GB tier; >32 GB → 32 GB tier). sim < view in every tier; view capped 12, sim capped 8:
  1→(4,3) 2→(6,4) 4→(7,5) 6→(8,5) 8→(9,6) 12→(10,6) 16→(11,7) 24→(12,7) 32→(12,8).
- Apply = runtime (all worlds) + best-effort `server.properties` line edit (skip silently if file absent/unreadable; never throw into the click handler; failures only via `plugin.debug(...)`, never console).
- Button click re-checks `sentinel.use` (defense in depth), writes an audit entry, refreshes the lore.
- No new config keys. New message keys auto-merge. Do NOT `git add -A` (gitignored `.claude/`+`.superpowers/`). Stage explicit paths. `./gradlew test` before each commit.

---

### Task 1: `ServerOptimizer` (recommendation + apply + properties edit)

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/util/ServerOptimizer.java`
- Test: `src/test/java/de/derfakegamer/sentinel/util/ServerOptimizerTest.java`

**Interfaces:**
- Produces: `ServerOptimizer.Preset(int view, int sim)`; static `recommend(long maxMemoryBytes) → Preset`,
  `roundedGb(long) → long`, `replaceProperty(String content, String key, int value) → String`; instance
  `ServerOptimizer(Sentinel)`, `recommended() → Preset`, `ramGb() → long`, `currentView()/currentSim() → int`
  (−1 if no worlds), `apply(Preset)`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerOptimizerTest {
    private static long gb(double n) { return (long) (n * 1073741824.0); }

    @Test void roundsXmxLikeValues() {
        assertEquals(4, ServerOptimizer.roundedGb(gb(3.9)));   // -Xmx4G reports ~3.9
        assertEquals(6, ServerOptimizer.roundedGb(gb(5.8)));
    }

    @Test void recommendsTableValuesAtTiers() {
        assertEquals(new ServerOptimizer.Preset(4, 3), ServerOptimizer.recommend(gb(1)));
        assertEquals(new ServerOptimizer.Preset(6, 4), ServerOptimizer.recommend(gb(2)));
        assertEquals(new ServerOptimizer.Preset(7, 5), ServerOptimizer.recommend(gb(4)));
        assertEquals(new ServerOptimizer.Preset(8, 5), ServerOptimizer.recommend(gb(6)));
        assertEquals(new ServerOptimizer.Preset(9, 6), ServerOptimizer.recommend(gb(8)));
        assertEquals(new ServerOptimizer.Preset(10, 6), ServerOptimizer.recommend(gb(12)));
        assertEquals(new ServerOptimizer.Preset(11, 7), ServerOptimizer.recommend(gb(16)));
        assertEquals(new ServerOptimizer.Preset(12, 7), ServerOptimizer.recommend(gb(24)));
        assertEquals(new ServerOptimizer.Preset(12, 8), ServerOptimizer.recommend(gb(32)));
    }

    @Test void recommendsBetweenTiersUsesLowerTierAndClamps() {
        assertEquals(new ServerOptimizer.Preset(7, 5), ServerOptimizer.recommend(gb(5)));   // 5 -> 4-tier
        assertEquals(new ServerOptimizer.Preset(9, 6), ServerOptimizer.recommend(gb(10)));  // 10 -> 8-tier
        assertEquals(new ServerOptimizer.Preset(4, 3), ServerOptimizer.recommend(gb(0.5))); // <1 -> 1-tier
        assertEquals(new ServerOptimizer.Preset(12, 8), ServerOptimizer.recommend(gb(64))); // >32 -> 32-tier
    }

    @Test void simAlwaysBelowView() {
        for (double g : new double[]{1,2,4,6,8,12,16,24,32}) {
            var p = ServerOptimizer.recommend(gb(g));
            assertTrue(p.sim() < p.view(), "sim<view at " + g + "GB");
        }
    }

    @Test void replacePropertyReplacesAndPreserves() {
        String in = "#comment\nview-distance=10\nsimulation-distance=10\nmotd=hi\n";
        String out = ServerOptimizer.replaceProperty(in, "view-distance", 7);
        assertTrue(out.contains("view-distance=7"));
        assertTrue(out.contains("simulation-distance=10"));
        assertTrue(out.contains("motd=hi"));
        assertTrue(out.contains("#comment"));
    }

    @Test void replacePropertyAppendsWhenAbsent() {
        String out = ServerOptimizer.replaceProperty("motd=hi\n", "view-distance", 7);
        assertTrue(out.contains("motd=hi"));
        assertTrue(out.contains("view-distance=7"));
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests '*ServerOptimizerTest'` → FAIL.

- [ ] **Step 3: Implement**

```java
package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** RAM-tiered view/simulation-distance presets + one-click apply. Pure parts are static + testable. */
public final class ServerOptimizer {
    public record Preset(int view, int sim) {}

    // {gbThreshold, view, sim} ascending; sim < view; view capped 12, sim capped 8.
    private static final int[][] TIERS = {
        {1, 4, 3}, {2, 6, 4}, {4, 7, 5}, {6, 8, 5}, {8, 9, 6},
        {12, 10, 6}, {16, 11, 7}, {24, 12, 7}, {32, 12, 8}
    };

    public static long roundedGb(long maxMemoryBytes) {
        return Math.round(maxMemoryBytes / 1073741824.0);
    }

    public static Preset recommend(long maxMemoryBytes) {
        long gb = roundedGb(maxMemoryBytes);
        int[] chosen = TIERS[0];               // floor: 1 GB tier
        for (int[] t : TIERS) if (gb >= t[0]) chosen = t;   // highest threshold <= gb
        return new Preset(chosen[1], chosen[2]);
    }

    /** Replaces the value of {@code key=...} in properties text (preserves other lines + comments); appends if absent. */
    public static String replaceProperty(String content, String key, int value) {
        String[] lines = content.split("\n", -1);
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(key + "=")) { lines[i] = key + "=" + value; found = true; }
        }
        String joined = String.join("\n", lines);
        if (!found) joined = joined + (joined.isEmpty() || joined.endsWith("\n") ? "" : "\n") + key + "=" + value + "\n";
        return joined;
    }

    private final Sentinel plugin;
    public ServerOptimizer(Sentinel plugin) { this.plugin = plugin; }

    public long ramGb() { return roundedGb(Runtime.getRuntime().maxMemory()); }
    public Preset recommended() { return recommend(Runtime.getRuntime().maxMemory()); }

    public int currentView() {
        List<World> w = Bukkit.getWorlds();
        return w.isEmpty() ? -1 : w.get(0).getViewDistance();
    }
    public int currentSim() {
        List<World> w = Bukkit.getWorlds();
        return w.isEmpty() ? -1 : w.get(0).getSimulationDistance();
    }

    /** Applies to every world (runtime) and persists to server.properties (best-effort, never throws). */
    public void apply(Preset p) {
        for (World w : Bukkit.getWorlds()) {
            try { w.setViewDistance(p.view()); w.setSimulationDistance(p.sim()); }
            catch (Throwable t) { plugin.debug("optimize: world " + w.getName() + " failed: " + t.getMessage()); }
        }
        try {
            Path f = Path.of("server.properties");
            if (Files.isRegularFile(f)) {
                String c = Files.readString(f);
                c = replaceProperty(c, "view-distance", p.view());
                c = replaceProperty(c, "simulation-distance", p.sim());
                Files.writeString(f, c);
            }
        } catch (Throwable t) {
            plugin.debug("optimize: persist failed: " + t.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**, then full suite.
- [ ] **Step 5: Commit** — `git add src/main/java/de/derfakegamer/sentinel/util/ServerOptimizer.java src/test/java/de/derfakegamer/sentinel/util/ServerOptimizerTest.java && git commit -m "feat: ServerOptimizer RAM-tiered view/sim distance presets"`

---

### Task 2: Optimize button in `ServerInfoGui`

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/ServerInfoGui.java`
- Modify: `src/main/resources/messages.yml` (gui.serverinfo.optimize* + optimize-applied)
- Modify: `README.md` (short note + preset table)
- Test: `src/test/java/de/derfakegamer/sentinel/gui/ServerInfoGuiTest.java` (create if absent)

**Interfaces:**
- Consumes: `ServerOptimizer` (Task 1), `plugin.staffPerms().canUse(sender, "sentinel.use")`,
  `plugin.audit().record(actor, action, target, details)`, `plugin.messages().plain/prefixed`.

- [ ] **Step 1: Write the failing test** (MockBukkit; mirror the existing GUI-test idiom — check a sibling `*GuiTest`):

```java
// ServerInfoGuiTest:
// - optimizeButtonShowsCurrentAndRecommended: open as op, assert slot 22 item exists and its lore
//   contains the recommended view/sim numbers from ServerOptimizer.recommend(Runtime max memory).
// - clickingOptimizeRecordsAudit: op clicks slot 22; assert an audit entry with action "OPTIMIZE"
//   exists (via plugin.audit().recent(...) drained), and the player got the optimize-applied message.
// - nonOpCannotOptimize: non-op clicks slot 22; assert no-permission message and NO "OPTIMIZE" audit.
// Use ConfirmGuiTest.clickSlot(player, gui, 22) like other GUI tests; drain the DB executor for audit reads.
```

Write these three tests concretely against the real audit/message APIs (read a sibling GUI test for the click + drain idiom). The recommended-numbers assertion uses `ServerOptimizer.recommend(Runtime.getRuntime().maxMemory())` so it matches whatever the test JVM reports.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** — in `ServerInfoGui`:
  - Add `private static final int OPTIMIZE = 22;` and a field `private final ServerOptimizer optimizer = new ServerOptimizer(plugin);` (set after `super(plugin)`).
  - Add a helper that builds the button (called in the constructor BEFORE `border()`, and again after apply to refresh):
```java
private void optimizeButton() {
    ServerOptimizer.Preset rec = optimizer.recommended();
    int cv = optimizer.currentView(), cs = optimizer.currentSim();
    java.util.List<net.kyori.adventure.text.Component> lore = java.util.List.of(
        plugin.messages().plain("gui.serverinfo.optimize-current", "view", String.valueOf(cv), "sim", String.valueOf(cs)),
        plugin.messages().plain("gui.serverinfo.optimize-recommended",
            "ram", String.valueOf(optimizer.ramGb()), "view", String.valueOf(rec.view()), "sim", String.valueOf(rec.sim())),
        plugin.messages().plain("gui.serverinfo.optimize-hint"));
    inventory.setItem(OPTIMIZE, Items.button(Material.ANVIL, plugin.messages().plain("gui.serverinfo.optimize"), lore));
}
```
   Call `optimizeButton();` in the constructor right before `border();`.
  - In `onClick`, add the OPTIMIZE branch:
```java
else if (event.getRawSlot() == OPTIMIZE) {
    if (!plugin.staffPerms().canUse(p, "sentinel.use")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
    ServerOptimizer.Preset rec = optimizer.recommended();
    optimizer.apply(rec);
    plugin.audit().record(p.getName(), "OPTIMIZE", "server", "view=" + rec.view() + " sim=" + rec.sim());
    p.sendMessage(plugin.messages().prefixed("optimize-applied", "view", String.valueOf(rec.view()), "sim", String.valueOf(rec.sim())));
    optimizeButton(); // refresh lore — current now matches recommendation
}
```
  - Add to `messages.yml` (scalars, read via `plain`):
```yaml
gui:
  serverinfo:
    optimize: "<aqua>Optimize server"
    optimize-current: "<red>Current — View: <view>  Sim: <sim>"
    optimize-recommended: "<green>Recommended (<ram> GB) — View: <view>  Sim: <sim>"
    optimize-hint: "<gray>Click to apply"
```
  and a top-level message:
```yaml
optimize-applied: "<#60A5FA>Server optimised — view-distance <white><view></white>, simulation-distance <white><sim></white>."
```
  (Place the gui.serverinfo.* keys under the existing `gui: serverinfo:` block; do not duplicate the block.)
  - README: add a short "Optimize" note with the preset table.

- [ ] **Step 4: Run tests**, then full suite → green.
- [ ] **Step 5: Commit** — `git add src/main/java/de/derfakegamer/sentinel/gui/ServerInfoGui.java src/main/resources/messages.yml README.md src/test/java/de/derfakegamer/sentinel/gui/ServerInfoGuiTest.java && git commit -m "feat: one-click Optimize button in Server Info GUI"`

---

## Self-Review

- **Spec coverage:** RAM-tier preset + rounding + properties edit (Task 1); runtime+persist apply
  (Task 1 `apply`); button with current-red/recommended-green lore + gate + audit + refresh (Task 2);
  messages/README (Task 2). All spec sections mapped.
- **Placeholder scan:** Task 1 full code; Task 2 full code + concrete test intents against real APIs
  (the test bodies are described with the exact slot/keys/assertions to write — no vague placeholders;
  the implementer reads one sibling GUI test for the click/drain idiom).
- **Type consistency:** `Preset(view,sim)`, `recommend`, `recommended()`, `ramGb()`, `currentView/Sim`,
  `apply` from Task 1 used in Task 2; audit `record(actor,action,target,details)` matches existing call
  sites; message keys consistent between Task 2 code and yaml.
- **Slot 22** sits in the 27-slot bottom row between BACK(18) and CLOSE(26); set before `border()` so
  the accent fill (which only fills empty slots) doesn't overwrite it.
