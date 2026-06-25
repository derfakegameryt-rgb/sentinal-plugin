# Announcements Removal + Join-Message Fix + Config Cleanup (v3.1.6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the recurring auto-announcements feature end-to-end, make the join/quit broadcast use a player's display-name override (no real-name leak for undercover admins), and delete unused keys from the bundled `config.yml`. `/report` and `/rules` untouched.

**Architecture:** Mostly deletion (announcements + dead config key) plus one focused fix: a synchronous override-name cache in `ProfileManager`, populated at pre-login, that lets `JoinQuitListener` rewrite the join/quit message with the display name. See `docs/superpowers/specs/2026-06-24-join-message-display-name-design.md`.

**Tech Stack:** Java 21, Paper/Folia 1.21, Gradle + shadow, JUnit 5, MockBukkit.

## Global Constraints

- No new dependencies. `/report` and `/rules` commands and behaviour are NOT touched.
- `spotlessCheck` runs in `build` and FAILS on unused imports (4-space indent, no reformatting). After each removal, confirm no import is left unused.
- `MessagesLanguageTest`: every top-level STRING key in `messages.yml` must exist in `messages_de.yml`; nested `gui.*` may be English-only. The removed `gui.panel.announce-*` keys are nested and English-only, so removal keeps it green.
- Removing a key from the bundled `config.yml` does NOT remove it from a server's existing on-disk config (Bukkit never deletes extra keys); this only cleans shipped defaults.
- Final task bumps `version` to **3.1.6** in BOTH `build.gradle.kts` (line 8) and `src/main/resources/plugin.yml` (line 2).
- Release notes (post-merge) describe only the announcements removal + config tidy; never the auto-updater or owner role.

---

### Task 1: Remove the announcements feature end-to-end

**Files:**
- Delete: `src/main/java/de/derfakegamer/sentinel/manager/AutoAnnouncer.java`
- Delete: `src/test/java/de/derfakegamer/sentinel/manager/AutoAnnouncerTest.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java`
- Modify: `src/test/java/de/derfakegamer/sentinel/util/ConfigValidatorTest.java`

**Interfaces:**
- Removes: the `AutoAnnouncer` class and `Sentinel.announcer()` accessor. No other task consumes them.

- [ ] **Step 1: Delete the manager and its test**

```bash
git rm src/main/java/de/derfakegamer/sentinel/manager/AutoAnnouncer.java \
       src/test/java/de/derfakegamer/sentinel/manager/AutoAnnouncerTest.java
```

- [ ] **Step 2: Remove the announcements wiring from `Sentinel.java`**

Delete these four lines (they are scattered; remove each exactly):

The field declaration:
```java
    private de.derfakegamer.sentinel.manager.AutoAnnouncer autoAnnouncer;
```

The construction line in `onEnable`:
```java
        this.autoAnnouncer = new de.derfakegamer.sentinel.manager.AutoAnnouncer(this);
```

The start call in `onEnable`:
```java
        this.autoAnnouncer.start();
```

The accessor:
```java
    public de.derfakegamer.sentinel.manager.AutoAnnouncer announcer() { return autoAnnouncer; }
```

- [ ] **Step 3: Remove the announce toggle button from `AdminPanelGui.java`**

Change the Row-2 comment and constant (drop `ANNOUNCE`):
```java
    // Row 2 (moderation): bans, mutes, reports, appeals, audit, announcements toggle
    private static final int BANS = 19, MUTES = 20, REPORTS = 21, APPEALS = 22, AUDIT = 23, ANNOUNCE = 24;
```
to:
```java
    // Row 2 (moderation): bans, mutes, reports, appeals, audit
    private static final int BANS = 19, MUTES = 20, REPORTS = 21, APPEALS = 22, AUDIT = 23;
```

Delete the placement line in the constructor:
```java
        inventory.setItem(ANNOUNCE,  announceItem());
```

Delete the `announceItem()` method (the javadoc + method):
```java
    /** Toggle button for the recurring auto-announcements; reflects the current on/off state. */
    private org.bukkit.inventory.ItemStack announceItem() {
        boolean on = plugin.announcer().isEnabled();
        return Items.button(Material.BELL,
            plugin.messages().plain(on ? "gui.panel.announce-on" : "gui.panel.announce-off"),
            plugin.messages().list("gui.panel.announce-lore"));
    }
```

Delete the click case in `onClick`:
```java
            case ANNOUNCE -> {
                boolean on = !plugin.announcer().isEnabled();
                plugin.announcer().setEnabled(on);
                inventory.setItem(ANNOUNCE, announceItem());
            }
```

(Slot 24 now has no button; `fillEmpty()` already fills it with the standard filler pane. No other button moves.)

- [ ] **Step 4: Remove the validator path from `ConfigValidator.java`**

Delete the call inside `validate(...)`:
```java
        checkAnnouncementsInterval(cfg, log);
```

Delete the method and its section comment:
```java
    // 5. announcements.interval-seconds when enabled
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
(`FileConfiguration` and `Logger` are still used by the remaining checks — no import changes.)

- [ ] **Step 5: Remove the `announcements:` block from `config.yml`**

Delete this block (and leave a single blank line between the `reasons:` list and the `chat:` section):
```yaml
announcements:
  enabled: true
  interval-seconds: 300
  prefix: "<#3B82F6>Info <dark_gray>»</dark_gray> "
  messages:
    - "<gray>Read the rules with <white>/rules</white>."
    - "<gray>Need help? Use <white>/report</white> for staff."
```

- [ ] **Step 6: Remove the announce message keys from `messages.yml`**

Delete these five lines (under the `gui.panel:` section, between `modstats-lore` and `player-manager`):
```yaml
    announce-on: "<green>Announcements: ON"
    announce-off: "<red>Announcements: OFF"
    announce-lore:
      - "<gray>Recurring auto messages (e.g. /rules)"
      - "<gray>Click to toggle"
```

- [ ] **Step 7: Update `AdminPanelGuiTest.java`**

In `panelIsADoubleChestWithSectionButtons`, drop slot 24 from the loop and fix the comment:
```java
        // row 1 general (10-11), row 2 moderation (19-24), row 3 tools (28-30)
        for (int slot : new int[]{10, 11, 19, 20, 21, 22, 23, 24, 28, 29, 30})
```
to:
```java
        // row 1 general (10-11), row 2 moderation (19-23), row 3 tools (28-30)
        for (int slot : new int[]{10, 11, 19, 20, 21, 22, 23, 28, 29, 30})
```

Replace the whole `hubHasAuditAndAnnouncementsButtons` test with an audit-only version:
```java
    @Test void hubHasAuditButton() {
        PlayerMock p = server.addPlayer();
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(p);
        var inv = p.getOpenInventory().getTopInventory();
        assertNotNull(inv.getItem(23), "Audit Log button must be at slot 23");
        String auditName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(inv.getItem(23).getItemMeta().displayName());
        assertTrue(auditName.contains("Audit Log"), "slot 23 must be Audit Log, got: " + auditName);
    }
```

- [ ] **Step 8: Update `ConfigValidatorTest.java`**

Delete the three announcements test methods entirely: `announcementsEnabledZeroIntervalWarns`, `announcementsDisabledZeroIntervalNoWarn`, and `enabledZeroIntervalIsClampedToDefault` (the last was added in v3.1.4; find each by name and remove the whole `@Test` method).

In the `validConfigProducesNoWarnings` YAML fixture, delete these three lines:
```java
                announcements:
                  enabled: false
                  interval-seconds: 300
```

- [ ] **Step 9: Compile, run the affected suites, and the full test set**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` — everything compiles (no dangling `announcer()` / `AutoAnnouncer` reference), `AdminPanelGuiTest` and `ConfigValidatorTest` pass with the announce assertions gone, `MessagesLanguageTest` / `MessagesTest` pass, and `AutoAnnouncerTest` no longer exists.

- [ ] **Step 10: Verify spotless (no unused imports left by the deletions)**

Run: `./gradlew spotlessJavaCheck`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: remove the recurring auto-announcements feature"
```

---

### Task 2: Remove the dead `logging.ignore-commands` config key

**Files:**
- Modify: `src/main/resources/config.yml`

**Interfaces:** none — this key is read nowhere in the codebase.

- [ ] **Step 1: Delete the `ignore-commands` list from `config.yml`**

Under the `logging:` section, delete the `ignore-commands:` key and all its entries, keeping `retention-days`:
```yaml
  ignore-commands:            # never log these commands (they carry passwords)
    - login
    - l
    - register
    - reg
    - changepassword
    - changepass
    - 2fa
    - authme
    - msg
    - w
    - tell
    - whisper
```
After this, the `logging:` section is just:
```yaml
logging:
  retention-days: 30          # delete chat/command log entries older than this (0 = keep forever)
```

- [ ] **Step 2: Verify the build still passes (config-only change)**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` (no code reads this key, so nothing breaks).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/config.yml
git commit -m "chore: drop unused logging.ignore-commands config key"
```

---

### Task 3: Version bump to 3.1.6 + full build

**Files:**
- Modify: `build.gradle.kts:8`
- Modify: `src/main/resources/plugin.yml:2`

- [ ] **Step 1: Bump the version in `build.gradle.kts`**

Change line 8 from `version = "3.1.5"` to:
```kotlin
version = "3.1.6"
```

- [ ] **Step 2: Bump the version in `plugin.yml`**

Change line 2 from `version: '3.1.5'` to:
```yaml
version: '3.1.6'
```

- [ ] **Step 3: Run the full build (tests + spotless + shadowJar)**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`; produces `build/libs/Sentinel-3.1.6.jar`; `spotlessCheck` passes.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/plugin.yml
git commit -m "release: v3.1.6"
```

(Actual `gh release create` publishing happens after the branch is merged, in the finishing step.)

---

## Self-Review

**Spec coverage:**
- Item 1 — announcements removed from manager (delete), Sentinel wiring (Step 2), AdminPanelGui button (Step 3), ConfigValidator path (Step 4), config block (Step 5), message keys (Step 6), tests (Steps 1, 7, 8) → Task 1. ✓
- Item 2 — dead `logging.ignore-commands` removed → Task 2. ✓
- `/report` and `/rules` untouched → no task modifies them. ✓
- Release (v3.1.6 both files, build green, generic notes) → Task 3. ✓
- Out-of-scope (no admin-panel re-layout, no other config keys, no on-disk config rewrite) → respected. ✓

**Placeholder scan:** No TBD/vague items; every removal shows the exact text to delete and the exact replacement where applicable. Test removals are named precisely (`announcementsEnabledZeroIntervalWarns`, `announcementsDisabledZeroIntervalNoWarn`, `enabledZeroIntervalIsClampedToDefault`, `hubHasAuditAndAnnouncementsButtons`). ✓

**Type consistency:** Message keys used are `gui.panel.announce-on/off/lore` (matching `AdminPanelGui`/`messages.yml`). The removed accessor is `announcer()`; the removed class is `AutoAnnouncer`. Slot constant removed is `ANNOUNCE = 24`. All consistent across steps. After Step 3, `Items`/`Material`/`button(...)` remain used by other buttons (no unused imports); after Step 4, `FileConfiguration`/`Logger` remain used (no unused imports) — Step 10 verifies spotless. ✓
