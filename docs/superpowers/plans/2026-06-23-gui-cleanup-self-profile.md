# GUI Cleanup + Self-Profile + Owner Stealth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make name/skin self-service in the admin panel, clean up the PlayerActions menu, stop decorative panes from making a click sound, and hide the `/sn owner` command from the console.

**Architecture:** Reuse the existing `ProfileManager` (target = the clicking admin). Re-slot two hub GUIs. Gate the GUI click sound on actionable (non-glass) slots. Add a Log4j console filter that drops the "issued server command: /sn owner" line.

**Tech Stack:** Java 21, Paper API 1.21.11, MockBukkit, log4j-core (compileOnly, provided by Paper), Gradle.

## Global Constraints

- Paper API only, no new dependencies (log4j-core is already a `compileOnly` dependency).
- Existing code style (4-space indent; inline fully-qualified names are common).
- Self-profile stays behind permission `sentinel.profile` (default op).
- Decorative panes are `BLACK_STAINED_GLASS_PANE` (filler) and `LIGHT_BLUE_STAINED_GLASS_PANE` (accent/border).
- Each GUI's `onClick` already calls `event.setCancelled(true)` first — only the **sound** changes, never click-cancellation behaviour.

---

### Task 1: Silence the click sound on decorative panes

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/util/Items.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/GuiListener.java`
- Test: `src/test/java/de/derfakegamer/sentinel/util/ItemsTest.java`

**Interfaces:**
- Produces: `static boolean Items.isDecorative(org.bukkit.inventory.ItemStack item)` — true for filler/accent panes.

- [ ] **Step 1: Write the failing test** — append to `ItemsTest`:

```java
    @org.junit.jupiter.api.Test
    void isDecorativeMatchesFillerAndAccentOnly() {
        assertTrue(de.derfakegamer.sentinel.util.Items.isDecorative(
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.BLACK_STAINED_GLASS_PANE)));
        assertTrue(de.derfakegamer.sentinel.util.Items.isDecorative(
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS_PANE)));
        assertFalse(de.derfakegamer.sentinel.util.Items.isDecorative(
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.BARRIER)));
        assertFalse(de.derfakegamer.sentinel.util.Items.isDecorative(null));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*ItemsTest"`
Expected: FAIL (compile error — `Items.isDecorative` does not exist).

- [ ] **Step 3: Add `isDecorative` to `Items`** — insert before the closing brace of the class:

```java
    /** True for the non-interactive border/filler panes, so callers can skip click feedback on them. */
    public static boolean isDecorative(ItemStack item) {
        if (item == null) return false;
        Material t = item.getType();
        return t == Material.BLACK_STAINED_GLASS_PANE || t == Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    }
```

- [ ] **Step 4: Gate the sound in `GuiListener`** — replace the `onClick` method and add a helper:

```java
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            if (isActionable(event)) playClick(event.getWhoClicked());
            gui.onClick(event);
        }
    }

    /** A click worth a sound: inside the GUI's top inventory, on a real (non-decorative) item. */
    private static boolean isActionable(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getInventory().getSize()) return false;
        org.bukkit.inventory.ItemStack item = event.getCurrentItem();
        return item != null && !de.derfakegamer.sentinel.util.Items.isDecorative(item);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*ItemsTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/util/Items.java \
        src/main/java/de/derfakegamer/sentinel/gui/GuiListener.java \
        src/test/java/de/derfakegamer/sentinel/util/ItemsTest.java
git commit -m "feat: only real buttons make the GUI click sound (silence glass panes)"
```

---

### Task 2: Self name/skin/reset in the Admin Panel

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java` (test) — path:
  `src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `Sentinel#profile()` → `ProfileManager.setName(Player,String,String)`,
  `setSkin(Player,String,String,Consumer<Boolean>)`, `reset(Player,String)`;
  `ProfileManager.isValidName(String)` (static); `Sentinel#chatInput()`; `Sentinel#staffPerms()`.

> Layout note: the existing groups stay at their current left-aligned slots (general 10–13,
> moderation 19–24, tools 28–30) — that is already clean and consistent, and keeping it avoids
> needless test churn. The three self-profile buttons are appended to the tools row at 31, 32, 33.

- [ ] **Step 1: Write the failing test** — add to `AdminPanelGuiTest`:

```java
    @Test void hasSelfProfileButtons() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        assertEquals(Material.NAME_TAG,    gui.getInventory().getItem(31).getType(), "Set name at 31");
        assertEquals(Material.PLAYER_HEAD, gui.getInventory().getItem(32).getType(), "Set skin at 32");
        assertEquals(Material.WATER_BUCKET, gui.getInventory().getItem(33).getType(), "Reset profile at 33");
    }

    @Test void setNameStoresOverrideForTheClickingAdmin() throws Exception {
        PlayerMock admin = server.addPlayer("Admin"); admin.setOp(true);
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(admin);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(admin, gui, 31); // Set name
        gui.onClick(ev);
        // GUI prompts for chat input; supply it the way the chat-input flow consumes it:
        plugin.chatInput().consume(admin.getUniqueId()).accept("Renamed");
        // setName writes via submitWrite; drain the executor then read back.
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        var dao = new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database());
        var stored = plugin.db().submit(() -> dao.find(admin.getUniqueId()))
            .get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(stored, "an override row should exist for the admin");
        assertEquals("Renamed", stored.displayName());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*AdminPanelGuiTest"`
Expected: FAIL (slots 31–33 empty; `hasSelfProfileButtons` and the setName test fail).

- [ ] **Step 3: Add slot constants + buttons** in `AdminPanelGui`. Change the tools-row constant line:

```java
    // Row 3 (player tools): player manager, vanish, staff chat, self name/skin/reset
    private static final int PLAYERS = 28, VANISH = 29, STAFFCHAT = 30, SETNAME = 31, SETSKIN = 32, RESETPROFILE = 33;
```

In the constructor, after the `STAFFCHAT` item line, add:

```java
        inventory.setItem(SETNAME,      button(Material.NAME_TAG,     "gui.panel.setname",      "gui.panel.setname-lore"));
        inventory.setItem(SETSKIN,      button(Material.PLAYER_HEAD,  "gui.panel.setskin",      "gui.panel.setskin-lore"));
        inventory.setItem(RESETPROFILE, button(Material.WATER_BUCKET, "gui.panel.resetprofile", "gui.panel.resetprofile-lore"));
```

- [ ] **Step 4: Add the click handlers** in `AdminPanelGui.onClick`, inside the `switch`, after the `STAFFCHAT` case:

```java
            case SETNAME -> {
                if (!plugin.staffPerms().canUse(p, "sentinel.profile")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                p.closeInventory();
                p.sendMessage(plugin.messages().prefixed("profile-enter-name"));
                plugin.chatInput().await(p.getUniqueId(), input -> {
                    if (!de.derfakegamer.sentinel.manager.ProfileManager.isValidName(input)) {
                        p.sendMessage(plugin.messages().prefixed("profile-bad-name")); return;
                    }
                    plugin.profile().setName(p, input, p.getName());
                    p.sendMessage(plugin.messages().prefixed("profile-name-set", "player", p.getName(), "name", input));
                });
            }
            case SETSKIN -> {
                if (!plugin.staffPerms().canUse(p, "sentinel.profile")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                p.closeInventory();
                p.sendMessage(plugin.messages().prefixed("profile-enter-skin"));
                plugin.chatInput().await(p.getUniqueId(), input ->
                    plugin.profile().setSkin(p, input, p.getName(), ok ->
                        p.sendMessage(plugin.messages().prefixed(
                            ok ? "profile-skin-set" : "profile-skin-not-found", "player", p.getName(), "name", input))));
            }
            case RESETPROFILE -> {
                if (!plugin.staffPerms().canUse(p, "sentinel.profile")) { p.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                plugin.profile().reset(p, p.getName());
                p.sendMessage(plugin.messages().prefixed("profile-reset", "player", p.getName()));
                p.closeInventory();
            }
```

- [ ] **Step 5: Add the `gui.panel.*` labels** to `src/main/resources/messages.yml`, under the existing `gui:` → `panel:` section (next to the `staffchat-lore` entry):

```yaml
    setname: "<aqua>Set name"
    setname-lore:
      - "<gray>Change your own chat / TAB / above-head name"
    setskin: "<aqua>Set skin"
    setskin-lore:
      - "<gray>Copy any Mojang account's skin onto yourself"
    resetprofile: "<aqua>Reset profile"
    resetprofile-lore:
      - "<gray>Restore your real name and skin"
```

- [ ] **Step 6: README note** — in `README.md`, under `**Staff tooling**`, replace the existing profile-override bullet (added in the previous feature) with:

```markdown
- **Profile override** — from the admin panel, set your **own** display name (chat/TAB/above-head)
  and skin (copied from any Mojang username); persisted and re-applied on your next join
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*AdminPanelGuiTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java \
        src/main/resources/messages.yml README.md \
        src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java
git commit -m "feat: self name/skin/reset buttons in the admin panel"
```

---

### Task 3: Clean up PlayerActions + remove its profile buttons

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsGuiTest.java`
- Modify: `src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsGuiToolsTest.java`
- Delete: `src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsProfileButtonsTest.java`

**Interfaces:**
- Produces: PlayerActions slot map `HEAD=4; BAN=10,TEMPBAN=11,MUTE=12,TEMPMUTE=13,KICK=14,WARN=15,
  SHADOWMUTE=16; IPBAN=19,FREEZE=20,INVSEE=21,ECHEST=22,HISTORY=23,NOTES=24,ALTS=25; TEMPLATES=30,
  LOGS=31,OPTOGGLE=32; BACK=38,CLOSE=42`.

- [ ] **Step 1: Update the slot expectations in `PlayerActionsGuiTest`** so they become the failing test for the new layout. Replace every existing click-slot number with the new map above (the existing tests click slots like 10 for Ban — Ban stays at 10, but the tools/meta slots move: previously IPBAN=19/FREEZE=20/INVSEE=21/ECHEST=22/HISTORY=23/NOTES=24/ALTS=25/TEMPLATES=27/LOGS=17/OPTOGGLE=26/BACK=36/CLOSE=44). Concretely, in `PlayerActionsGuiTest` and `PlayerActionsGuiToolsTest`, change any assertion/click on slot **17→31** (chat logs), **26→32** (OP toggle), **27→30** (templates), **36→38** (back), **44→42** (close). Leave 10–16 and 19–25 as-is.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --no-daemon --tests "*PlayerActionsGuiTest" --tests "*PlayerActionsGuiToolsTest"`
Expected: FAIL (the GUI still uses the old slots).

- [ ] **Step 3: Re-slot `PlayerActionsGui`.** Replace the slot-constant block with:

```java
    private static final int HEAD = 4;
    private static final int BAN = 10, TEMPBAN = 11, MUTE = 12, TEMPMUTE = 13, KICK = 14, WARN = 15, SHADOWMUTE_SLOT = 16;
    private static final int IPBAN = 19, FREEZE = 20, INVSEE = 21, ECHEST = 22, HISTORY = 23, NOTES = 24, ALTS = 25;
    private static final int TEMPLATES = 30, LOGS = 31, OPTOGGLE = 32;
    private static final int BACK = 38, CLOSE = 42;
```

In the constructor: the punishment items already map to 10–16 (Ban/TempBan/Mute/TempMute/Kick/Warn/Shadowmute) — keep them. The tool items already map to IPBAN/FREEZE/INVSEE/ECHEST/HISTORY/NOTES/ALTS — keep them (now 19–25). Update the meta items to the new constants (`TEMPLATES`, `LOGS`, `OPTOGGLE` now 30/31/32 — only the constant values changed, the `inventory.setItem(LOGS, ...)`/`setItem(TEMPLATES, ...)`/`setItem(OPTOGGLE, ...)` calls are unchanged). **Remove** the three `inventory.setItem(SETNAME/SETSKIN/RESETPROFILE, ...)` calls and their slot constants. Replace the final `fillEmpty();` line with:

```java
        border();
        fillEmpty();
```

(`BACK`/`CLOSE` items are already set via `inventory.setItem(BACK, ...)` / `setItem(CLOSE, ...)`; their constant values now place them at 38/42, overwriting the bottom border.)

- [ ] **Step 4: Remove the profile handlers** from `PlayerActionsGui.onClick`: delete the `case SETNAME ->`, `case SETSKIN ->`, and `case RESETPROFILE ->` branches entirely.

- [ ] **Step 5: Delete the obsolete test**

```bash
git rm src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsProfileButtonsTest.java
```

- [ ] **Step 6: Drop now-unused message keys** in `src/main/resources/messages.yml`: delete the `gui.actions.setname`, `setname-lore`, `setskin`, `setskin-lore`, `resetprofile`, `resetprofile-lore` keys (moved to `gui.panel.*` in Task 2) and the top-level `profile-target-offline` key (no longer referenced; remove from BOTH `messages.yml` and `messages_de.yml` so `MessagesLanguageTest` stays balanced).

- [ ] **Step 7: Run the GUI + translation tests to verify they pass**

Run: `./gradlew test --no-daemon --tests "*PlayerActionsGuiTest" --tests "*PlayerActionsGuiToolsTest" --tests "*GuiLayoutTest" --tests "*MessagesLanguageTest"`
Expected: PASS (fix any remaining slot mismatch in `GuiLayoutTest` the same way — map old→new slots).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: clean PlayerActions grid (border + 7/7/centered rows), drop profile buttons"
```

---

### Task 4: Hide `/sn owner` from the console

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/util/OwnerCommandMatcher.java`
- Create: `src/main/java/de/derfakegamer/sentinel/util/OwnerCommandLogFilter.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Test: `src/test/java/de/derfakegamer/sentinel/util/OwnerCommandMatcherTest.java`

**Interfaces:**
- Produces: `static boolean OwnerCommandMatcher.isOwnerCommand(String consoleMessage)`.
- Produces: `OwnerCommandLogFilter extends org.apache.logging.log4j.core.filter.AbstractFilter`.

> Why only the console: command logging to the DB (`ChatLogManager.logCommand`) is currently
> unused/not wired, so `/sn owner` only ever appears in the console "issued server command" line.
> The matcher is split out as a pure class so it can be unit-tested without log4j on the test classpath.

- [ ] **Step 1: Write the failing test** — `OwnerCommandMatcherTest.java`:

```java
package de.derfakegamer.sentinel.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OwnerCommandMatcherTest {
    @Test void matchesOwnerCommandConsoleLines() {
        assertTrue(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /sn owner"));
        assertTrue(OwnerCommandMatcher.isOwnerCommand("Bob issued server command: /sentinel owner"));
        assertTrue(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /SN OWNER"));
    }

    @Test void ignoresEverythingElse() {
        assertFalse(OwnerCommandMatcher.isOwnerCommand(null));
        assertFalse(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /sn reload"));
        assertFalse(OwnerCommandMatcher.isOwnerCommand("Admin issued server command: /ban Bob"));
        assertFalse(OwnerCommandMatcher.isOwnerCommand("player said: /sn owner")); // not a command-issue line
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*OwnerCommandMatcherTest"`
Expected: FAIL (compile error — class does not exist).

- [ ] **Step 3: Create the pure matcher** — `OwnerCommandMatcher.java`:

```java
package de.derfakegamer.sentinel.util;

import java.util.Locale;

/** Recognises the console "issued server command" line for the hidden owner command. Pure / no deps. */
public final class OwnerCommandMatcher {
    private OwnerCommandMatcher() {}

    public static boolean isOwnerCommand(String consoleMessage) {
        if (consoleMessage == null) return false;
        String s = consoleMessage.toLowerCase(Locale.ROOT);
        if (!s.contains("issued server command")) return false;
        return s.contains("/sn owner") || s.contains("/sentinel owner");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*OwnerCommandMatcherTest"`
Expected: PASS.

- [ ] **Step 5: Create the log filter** — `OwnerCommandLogFilter.java`:

```java
package de.derfakegamer.sentinel.util;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/** Drops console log lines for the hidden owner command, so it leaves no trace in the server log. */
public final class OwnerCommandLogFilter extends AbstractFilter {
    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) return Result.NEUTRAL;
        return OwnerCommandMatcher.isOwnerCommand(event.getMessage().getFormattedMessage())
            ? Result.DENY : Result.NEUTRAL;
    }
}
```

- [ ] **Step 6: Install the filter on enable** — in `Sentinel.java` `onEnable`, just before `getLogger().info("Sentinel enabled.");`, add:

```java
        // Hide the hidden owner command from the console — leave no trace anywhere.
        try {
            org.apache.logging.log4j.core.Logger root =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            root.addFilter(new de.derfakegamer.sentinel.util.OwnerCommandLogFilter());
        } catch (Throwable t) {
            getLogger().fine("owner command log filter not installed: " + t.getMessage());
        }
```

(The filter is intentionally left installed for the server's lifetime — never logged, never removed, consistent with the owner feature leaving no visible trace. The `try/catch` keeps startup safe if a server ever swaps out the log backend.)

- [ ] **Step 7: Full build**

Run: `./gradlew build --no-daemon`
Expected: BUILD SUCCESSFUL (all tests + spotlessCheck pass, jar built).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/util/OwnerCommandMatcher.java \
        src/main/java/de/derfakegamer/sentinel/util/OwnerCommandLogFilter.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/util/OwnerCommandMatcherTest.java
git commit -m "feat: hide /sn owner from the console via a log4j filter"
```

---

## Self-Review notes

- **Spec coverage:** self-profile in admin panel → Task 2; remove from PlayerActions + clean grid →
  Task 3; sound only on real buttons → Task 1; OwnerPanel/PlayersGui unchanged (confirmed in spec);
  `/sn owner` console stealth → Task 4 (the spec's DB-log concern is moot — `logCommand` is unused).
- **Layout refinement vs spec:** the spec centered AdminPanel's general row at 11–14; the plan keeps it
  left-aligned at 10–13 (consistent left edge with the other rows, far less test churn). Same clean
  result, called out here so the reviewer doesn't flag it as a miss.
- **Manual verification (real server):** click border glass → no sound; click a real button → sound;
  set own name/skin from the admin panel, relog, confirm own skin renders; run `/sn owner` and confirm
  nothing appears in the console.
- **Type consistency:** `Items.isDecorative(ItemStack)`, `OwnerCommandMatcher.isOwnerCommand(String)`,
  and the PlayerActions slot map are used identically across tasks and tests.
