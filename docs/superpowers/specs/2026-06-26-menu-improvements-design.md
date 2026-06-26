# Admin & Owner Menu Improvements — Design

**Date:** 2026-06-26
**Status:** Approved (design)

## Goal

Three related GUI improvements:

1. **Admin Panel** — regroup the buttons into clean category rows and surface the one
   genuinely-missing top-level tool, **ModStats**. (ChatLog and Templates are inherently
   per-player and already reachable from the Player-Manager → player-actions menu, so they
   are NOT added top-level.)
2. **Owner Panel** — add a "Server control" group: Restart and Backup.
3. **Hide the owner from all admin-facing views** — the configured owner must not appear
   in any admin GUI (operators, players, search, alts, mod stats, active bans/mutes,
   audit), completing the owner feature's "leave no visible trace" guarantee.

No change to the behaviour of the tools themselves; this is menu wiring of existing
managers plus owner-filtering of existing list views.

## Background

- `AdminPanelGui` (54 slots) already groups buttons into rows and uses `gui.panel.*`
  message keys (localizable via `messages.yml`). Several GUIs exist but are not linked
  from the panel: `ModStatsGui`, `ChatLogGui`, `TemplatesGui` (top-level), plus
  `AltsGui`/`NotesGui`/`InvseeGui` (per-player, opened from the Player-Manager context).
- `OwnerPanelGui` is owner-only, uses hard-coded English text, and deliberately writes
  **no audit entries**. Owner toggles persist through `OwnerProtectionManager` via a
  key-value store (`dao.set(key, value)`) loaded in `OwnerProtectionManager.load()`.
- `RestartManager.schedule(int seconds)` / `cancel()` and
  `BackupManager.backup(CommandSender, long stamp)` already exist.

## Part A — Admin Panel regroup + ModStats

Re-lay the inner area (columns 1–7) into four category rows:

| Row | Category | Buttons |
|-----|----------|---------|
| 1 | General / server | Operators, Whitelist |
| 2 | Moderation | Bans, Mutes, Reports, Appeals, Audit |
| 3 | Players & stats | Player-Manager, **ModStats** (new) |
| 4 | Self / staff tools | Vanish, StaffChat, SetName, SetSkin, Reset |

- The one new button (**ModStats**) reuses the existing pattern: `button(Material, nameKey,
  loreKey)` with new `gui.panel.modstats` / `gui.panel.modstats-lore` keys added to
  `messages.yml` (match the existing key style and every language present).
- Its click handler opens the existing GUI: `ModStatsGui.open(plugin, viewer)`.
- ChatLog and Templates are NOT added top-level — they require a target player and are
  already reachable per-player from the player-actions menu (`ChatLogGui.open(plugin,
  target, viewer)` / `new TemplatesGui(plugin, target)`). `AltsGui`, `NotesGui`,
  `InvseeGui` likewise stay per-player.
- Suggested material for ModStats: `KNOWLEDGE_BOOK` (final choice in the plan; must not
  collide confusingly with existing icons).
- `border()` + `fillEmpty()` unchanged; `CLOSE` stays at slot 49. Exact slot numbers are
  fixed in the implementation plan.

## Part B — Owner Panel: Server control

Add a "Server" group of three buttons to `OwnerPanelGui`, in the existing hard-coded
owner style (no audit logging, owner-only guarded).

### B1 — Restart
- Left-click: schedule a restart with a **60-second** default countdown
  (`RestartManager.schedule(60)`).
- Right-click: cancel a pending restart (`RestartManager.cancel()`).
- Lore reflects current state ("no restart scheduled" vs "restart pending"). Distinguish
  click type via `event.isRightClick()` / `event.isLeftClick()` in `onClick`.

### B2 — Backup
- Click: `plugin.backup().backup(p, System.currentTimeMillis())`.
- Send the owner a short confirmation message (hard-coded, owner style).

## Part C — Hide the owner from all admin-facing views

The configured owner must not appear in any admin GUI. Each of these GUIs paginates
**in memory from a full list** (or is a single lookup), so the owner is filtered at
construction, before pagination — keeping page counts correct without DAO changes.

Owner identity:
- By UUID: `plugin.owner().isOwner(uuid)` (and `plugin.owner().uuid()`).
- By name (for views keyed on an actor/target *name* string): a new helper
  `plugin.owner().isOwnerName(String name)` — case-insensitive compare to the owner's
  known name (`plugin.ownerProtection().ownerName()`); returns false when the name or the
  owner name is null.

| GUI | Data | Filter |
|-----|------|--------|
| `OperatorsGui` | `Bukkit.getOperators()` | drop entries where `isOwner(uuid)` |
| `PlayersGui` | `Bukkit.getOnlinePlayers()` | drop where `isOwner(uuid)` |
| `AltsGui` | `players().alts(target)` list | drop where `isOwner(uuid)` |
| `ActiveBansGui` | bans list | drop where `isOwner(targetUuid)` |
| `ActiveMutesGui` | mutes list | drop where `isOwner(targetUuid)` |
| `ModStatsGui` | `audit().topActors(...)` | drop `ActorCount` where `isOwnerName(actor)` |
| `AuditGui` | audit entries list | drop entries where `isOwnerName(actor)` OR `isOwnerName(target)` |
| `SearchResultsGui` | single `players().byName(query)` record | if the record is the owner (`isOwner(uuid)`), render as "not found" (same as a null record) |

- Filtering happens in each GUI's constructor/opener on the already-fetched list, before
  the population loop — never mid-pagination.
- The owner's OWN panels (`OwnerPanelGui`, `OwnerOpsGui`, `OwnerAttacksGui`) are unaffected
  — they are owner-only and intentionally show owner data.
- Realistically the owner already never appears in bans/mutes (auto-unban) or audit (owner
  actions are never logged); these filters are defensive completeness so a stray entry can
  never leak.

## What stays the same

- Owner panel keeps its no-audit, hard-coded-label, owner-only-guarded character.
- Admin panel keeps `messages.yml`-driven labels, border, fill, and close slot.
- Existing tool behaviour (Restart, Backup, the GUIs being linked) is unchanged.
- No change to the login path or the ban system.

## Testing

- **Unit (MockBukkit + JUnit 5, matching existing GUI/listener tests):**
  - Admin panel: clicking the new ModStats slot opens `ModStatsGui` (or is wired to the
    correct handler without error) — mirror existing `AdminPanelGui`-style coverage if
    present; otherwise a construction/wiring smoke test.
  - Owner filtering: `OwnerManager.isOwnerName(...)` — true for the owner's name
    (case-insensitive), false for others / null. Plus a representative GUI test that the
    owner UUID is excluded from a constructed list view (e.g. `OperatorsGui` /
    `PlayersGui`) given a list containing the owner.
- **Manual:** owner panel Restart (left-click schedule + right-click cancel) and Backup
  (file produced); admin panel ModStats button opens the stats screen; owner does not
  appear in operators/players/search/alts/mod-stats/bans/mutes/audit for a non-owner staff.

## Out of scope

- The other owner-function bundles offered earlier (player control, self-buffs, stealth/
  surveillance) — only "Server control" was chosen.
- **Maintenance / lockdown mode** — explicitly removed from scope; no login-path or
  `OwnerProtectionManager` changes.
- Adding Alts/Notes/Invsee to the admin top level.
- Any redesign of the per-player GUIs themselves.
- **LuckPerms compatibility** (replacing the `isOp()`-as-staff checks with a `sentinel.staff`
  permission node across ChatListener, StaffChatManager, ReportManager, VanishManager,
  ModerationService, ClearChatCommand, etc.) — deferred to its own spec + plan as a separate
  follow-up unit, to be done after this menu work.
