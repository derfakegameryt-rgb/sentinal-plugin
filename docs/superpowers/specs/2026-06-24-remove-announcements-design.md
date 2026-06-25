# Remove Announcements + Config Cleanup (v3.1.6) — Design Spec

**Date:** 2026-06-24
**Status:** Approved
**Goal:** Remove the recurring auto-announcements feature end-to-end and delete unused keys from the bundled `config.yml`, without touching the `/report` and `/rules` commands themselves.

## Background

The plugin broadcasts a rotating set of MiniMessage announcements every N seconds (default 300) — the lines that say things like "Read the rules with /rules" and "Need help? Use /report". The user wants this recurring-broadcast feature gone entirely. The `/report` and `/rules` commands stay; only the auto-broadcast is removed.

A full audit of `config.yml` keys against actual code reads found the config otherwise tight: the only dead key (besides the announcements block) is `logging.ignore-commands` — it is read nowhere (commands are never logged; only chat is), so it does nothing.

## Decisions (confirmed)

- Remove `logging.ignore-commands` (dead key). Under `logging:` only `retention-days` remains.
- Version bump: **v3.1.6** (patch — treated as pure cleanup).

## Global Constraints

- Target Minecraft 1.21 (Paper/Folia), Java 21, Gradle + shadow. `spotlessCheck` runs in `build` and FAILS on unused imports (4-space indent, no reformatting of untouched code).
- No new dependencies. The `/report` and `/rules` commands and their behaviour are NOT touched.
- All scheduling via `plugin.scheduler()`. `onDisable` already cancels all tasks via `scheduler.cancelAll()`.
- `MessagesLanguageTest` rule: every top-level STRING key in `messages.yml` must exist in `messages_de.yml`; nested `gui.*` may be English-only. The removed keys (`gui.panel.announce-*`) are nested `gui.*` and exist only in `messages.yml`, so removing them keeps that test green.
- Version bumped to **3.1.6** in BOTH `build.gradle.kts` (line 8) and `src/main/resources/plugin.yml` (line 2) as the final step.
- Note: removing a key from the bundled `config.yml` does NOT remove it from a server's existing on-disk `config.yml` (Bukkit never deletes extra keys); the removed keys simply become harmless leftovers there. This change only cleans the shipped defaults.

---

## Item 1 — Remove the announcements feature end-to-end

**Delete:**
- `src/main/java/de/derfakegamer/sentinel/manager/AutoAnnouncer.java`
- `src/test/java/de/derfakegamer/sentinel/manager/AutoAnnouncerTest.java`

**`src/main/java/de/derfakegamer/sentinel/Sentinel.java`:** remove the `autoAnnouncer` field, its construction in `onEnable`, the `this.autoAnnouncer.start();` call, and the `announcer()` accessor.

**`src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`:** remove the announcements toggle button — the `ANNOUNCE` slot constant, the `announceItem()` method, the `inventory.setItem(ANNOUNCE, ...)` placement, and the `case ANNOUNCE` click handler. The freed slot (24) becomes a normal filler pane; the other admin-panel buttons keep their existing slots (no re-layout).

**`src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java`:** remove the `checkAnnouncementsInterval(cfg, log)` call from `validate(...)` and delete the `checkAnnouncementsInterval` method. (The non-negative-int checks do not reference any announcements key, so they are unchanged.)

**`src/main/resources/config.yml`:** delete the entire `announcements:` block.

**`src/main/resources/messages.yml`:** delete the `gui.panel.announce-on`, `gui.panel.announce-off`, and `gui.panel.announce-lore` keys. (`messages_de.yml` has no announce keys — no change there.)

**Tests:**
- `AdminPanelGuiTest`: remove assertions about the announcements toggle button / its slot.
- `ConfigValidatorTest`: remove the three announcements tests (`announcementsEnabledZeroIntervalWarns`, `announcementsDisabledZeroIntervalNoWarn`, `enabledZeroIntervalIsClampedToDefault`) and remove the `announcements:` lines from the `validConfigProducesNoWarnings` YAML fixture.

**Result:** No recurring broadcasts, no announcements config, no toggle button, no announce message keys, no validator path. `/report` and `/rules` are untouched.

---

## Item 2 — Remove dead config key

**`src/main/resources/config.yml`:** delete the `ignore-commands:` list (and its sub-entries) under `logging:`. Keep `logging.retention-days`. This key is read nowhere in the codebase.

No code or test changes accompany this (nothing reads the key).

---

## Release

After all changes pass review and `./gradlew clean build` is green:

- Bump `version` to `3.1.6` in `build.gradle.kts:8` and `src/main/resources/plugin.yml:2`.
- Release notes: generic — "removed the recurring auto-announcements feature and tidied the default config." No auto-updater or owner mention.

## Out of Scope (explicitly)

- The `/report` and `/rules` commands and their handlers — untouched.
- Re-laying-out the admin panel — the freed slot just becomes filler.
- Removing leftover keys from servers' existing on-disk configs — not possible/!desired; only the bundled default is cleaned.
- Any other config key — the audit found everything else is read and in use.
