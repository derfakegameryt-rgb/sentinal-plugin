# Trim Sentinel to a Player-Moderation Tool — Design

**Date:** 2026-06-23
**Status:** Approved (design)

## Problem

Sentinel has grown beyond moderation: it shows server specs/stats, manages a maintenance
mode, and tracks playtime. The owner wants it to be a **pure player-moderation tool** — those
three feature areas removed entirely. Separately, the owner's own actions must never appear in
the audit log (not a single one).

## Goal

1. Remove **Server Info** (specs/TPS/RAM/uptime/worlds + the Optimize button).
2. Remove **Maintenance** mode (command, login gate, MOTD, config, metrics chart).
3. Remove **Playtime** (command, leaderboard GUI, session tracking).
4. **Owner actions are never written to the audit log** — zero trace, consistent with the
   already-shipped `/sn owner` console stealth.

Non-goals: removing moderation-derived stats (`ModStatsGui`, `/sn stats`, `/sn audit` stay —
they aggregate the audit log, not playtime); dropping the `players.playtime` DB column (kept to
avoid a risky SQLite table rebuild); touching `PlayerDirectory`'s last-IP/alts tracking.

## Decisions

- **Complete removal** of the three features: commands (`/maintenance`, `/playtime`), GUIs,
  AdminPanel buttons, tracking code, plugin.yml entries, config keys, message keys (both
  locales), and their tests. (User chose "Komplett raus".)
- **Owner audit hiding at write time** (User chose "Gar nicht aufzeichnen"): the single
  chokepoint `AuditManager.record(...)` drops the entry when the actor is the owner, so no owner
  row ever reaches the database. The existing `OWNER%`-action read filter in `AuditDao` is left
  as a harmless, already-tested second layer.
- **Keep the `players.playtime` column** and the `PlayerRecord.playtime` field (vestigial, read
  as whatever is in the row). Only the playtime *code paths* are removed. This keeps
  `SchemaMigratorTest.playtimeColumnExistsAfterMigration` green and avoids a SQLite migration.
- **Keep `PlayerDirectory`** (last-IP, online cache, alts) — only its session/playtime methods go.

## 1. Remove Server Info

- **Delete:** `gui/ServerInfoGui.java`, `util/ServerOptimizer.java`,
  `test/.../gui/ServerInfoGuiTest.java`.
- **`gui/AdminPanelGui`:** remove the `INFO` slot constant, its `inventory.setItem(INFO, …)` line,
  and the `case INFO -> new ServerInfoGui(plugin).open(p)` branch.
- **Messages (both `messages.yml` and `messages_de.yml`):** delete `gui-serverinfo-title`,
  `optimize-applied`, the whole `gui.serverinfo.*` block, and `gui.panel.info` / `gui.panel.info-lore`.
- The `OPTIMIZE` audit record disappears with `ServerInfoGui`.

## 2. Remove Maintenance

- **Delete:** `command/MaintenanceCommand.java`, `manager/MaintenanceManager.java`,
  `listener/ServerPingListener.java` (it does **only** the maintenance MOTD),
  `test/.../manager/MaintenanceManagerTest.java`.
- **`listener/LoginListener`:** remove the maintenance pre-login gate block (the ban lookup and
  `profile.applyOnLogin` stay).
- **`manager/MetricsManager`:** remove the maintenance-state chart line.
- **`Sentinel.java`:** remove the `maintenanceManager` field/init, the `maintenance` command +
  tab-completer registration, the `ServerPingListener` registration, and the `maintenance()`
  accessor.
- **`plugin.yml`:** remove the `maintenance` command entry.
- **`config.yml`:** remove `maintenance.enabled`, `maintenance.kick-message`, `maintenance.motd`.
- **Messages (both locales):** remove `maintenance-on`, `maintenance-off`.
- **Tests:** remove maintenance from `SubcommandTest` (tab list) and `CompletionWiringTest`
  (`maintenanceCompleterRegistered`, `maintenanceSuggestsOnOff`).

## 3. Remove Playtime

- **Delete:** `command/PlaytimeCommand.java`, `gui/StatsGui.java` (the playtime leaderboard),
  `test/.../storage/PlaytimeDaoTest.java`.
- **`gui/AdminPanelGui`:** remove the `STATS` slot constant, its `setItem`, and the
  `case STATS -> StatsGui.open(plugin, p)` branch.
- **`listener/JoinQuitListener`:** remove `players().startSession(...)` (join) and
  `players().endSession(...)` (quit). Everything else in the listener (vanish, online cache,
  staff-chat clear, chat-moderation forget, evict) stays.
- **`manager/PlayerDirectory`:** remove `startSession`, `endSession`, `playtime(UUID)`,
  `topByPlaytime(int)`.
- **`storage/PlayerDao`:** remove `addPlaytime`, `playtime`, `topByPlaytime`. **Keep** the
  `playtime` column in the schema and the `PlayerRecord.playtime` field.
- **`Sentinel.java`:** remove the `playtime` command + tab-completer registration.
- **`plugin.yml`:** remove the `playtime` command entry.
- **Messages (both locales):** remove `playtime`, `gui-stats-title`, `gui.panel.stats`,
  `gui.panel.stats-lore`.
- **Tests:** remove `playtime` from `CompletionWiringTest` (`playtimeCompleterRegistered`).

## 4. Owner actions never enter the audit log

In `manager/AuditManager.record(String actor, String action, String target, String details)`,
before buffering the entry:

```java
String owner = plugin.owner().currentName();
if (owner != null && owner.equalsIgnoreCase(actor)) return; // owner leaves no audit trace
```

- Single chokepoint → covers every `record()` caller (moderation, vanish, staff-chat, profile,
  freeze, clearchat, reload, import, broadcast).
- `plugin.owner()` is `OwnerManager`; `currentName()` resolves the masked owner UUID to the live
  name (non-null while the owner is online and acting).
- Known limitation: comparison is by name. The owner acts online, so the name is live; only a
  mid-session rename would slip through — acceptable.
- Leave `AuditDao`'s `WHERE action NOT LIKE 'OWNER%'` read filter and its
  `ownerActionsHiddenFromAllViews` test untouched (independent second layer).

## 5. AdminPanel cleanup

Removing `INFO` (slot 10) and `STATS` (slot 13) leaves gaps in the general row (which currently
holds INFO=10, OPS=11, WHITELIST=12, STATS=13). Re-pack the surviving two contiguously:
`OPS=10, WHITELIST=11`. The moderation row (19–24), tools/self row (28–33), and CLOSE (49) are
unchanged. Update `AdminPanelGuiTest` slot expectations accordingly.

## 6. Testing

- Delete the four obsolete tests (`ServerInfoGuiTest`, `MaintenanceManagerTest`,
  `PlaytimeDaoTest`, plus the removed cases inside `SubcommandTest`/`CompletionWiringTest`).
- Update `AdminPanelGuiTest` for the re-packed general row and the removed INFO/STATS buttons.
- New `AuditManagerTest` case: a `record(...)` whose actor equals the owner's current name
  produces no row (assert `recent(...)` is empty / unchanged); a non-owner actor still records.
- `MessagesLanguageTest` must stay balanced — every key is removed from **both** `messages.yml`
  and `messages_de.yml`.
- Full `./gradlew build --no-daemon` (all tests + spotlessCheck + shaded jar) must be GREEN.
- Manual server check: `/maintenance` and `/playtime` are gone; AdminPanel has no Server-Info or
  Playtime button; an owner ban/vanish leaves no audit entry while a normal staff action does.

## 7. Files touched (summary)

- **Deleted:** `gui/ServerInfoGui`, `util/ServerOptimizer`, `command/MaintenanceCommand`,
  `manager/MaintenanceManager`, `listener/ServerPingListener`, `command/PlaytimeCommand`,
  `gui/StatsGui`; tests `ServerInfoGuiTest`, `MaintenanceManagerTest`, `PlaytimeDaoTest`.
- **Changed:** `gui/AdminPanelGui` (drop INFO/STATS, re-pack), `listener/LoginListener` (drop
  maintenance gate), `listener/JoinQuitListener` (drop session calls), `manager/MetricsManager`
  (drop maintenance chart), `manager/PlayerDirectory` (drop playtime/session methods),
  `storage/PlayerDao` (drop playtime methods), `manager/AuditManager` (owner write-skip),
  `Sentinel.java` (drop registrations/accessors), `plugin.yml`, `config.yml`, `messages.yml`,
  `messages_de.yml`; tests `AdminPanelGuiTest`, `SubcommandTest`, `CompletionWiringTest`,
  `AuditManagerTest`.
- **Unchanged (kept intentionally):** `gui/ModStatsGui`, `/sn stats` & `/sn audit` console
  subcommands, `PlayerDirectory` (last-IP/alts/cache), `players.playtime` column +
  `PlayerRecord.playtime` field, `AuditDao` `OWNER%` read filter.
