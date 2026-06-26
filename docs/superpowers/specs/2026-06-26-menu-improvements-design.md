# Admin & Owner Menu Improvements — Design

**Date:** 2026-06-26
**Status:** Approved (design)

## Goal

Two related GUI improvements:

1. **Admin Panel** — regroup the buttons into clean category rows and surface three
   existing tools that are currently not reachable from the panel (ModStats, ChatLog,
   Templates).
2. **Owner Panel** — add a "Server control" group: Restart and Backup.

No change to existing behaviour of the tools themselves; this is purely menu wiring of
existing managers.

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

## Part A — Admin Panel regroup + new tools

Re-lay the inner area (columns 1–7) into four category rows:

| Row | Category | Buttons |
|-----|----------|---------|
| 1 | General / server | Operators, Whitelist, **Templates** (new) |
| 2 | Moderation | Bans, Mutes, Reports, Appeals, Audit, **ChatLog** (new) |
| 3 | Players & stats | Player-Manager, **ModStats** (new) |
| 4 | Self / staff tools | Vanish, StaffChat, SetName, SetSkin, Reset |

- New buttons reuse the existing pattern: `button(Material, nameKey, loreKey)` with new
  `gui.panel.*` keys added to `messages.yml` (default English + German if the file is
  bilingual; match the existing key style).
- Click handlers open the existing GUIs: `TemplatesGui`, `ChatLogGui`, `ModStatsGui`
  (using each one's existing open/constructor convention).
- `AltsGui`, `NotesGui`, `InvseeGui` stay per-player (reached from Player-Manager), not
  added to the top level.
- Suggested materials: Templates = `WRITABLE_BOOK` (or `BOOKSHELF`), ChatLog = `BOOK`/
  `PAPER`, ModStats = `KNOWLEDGE_BOOK`/`CLOCK` — final choice in the plan, must not collide
  confusingly with existing icons.
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

## What stays the same

- Owner panel keeps its no-audit, hard-coded-label, owner-only-guarded character.
- Admin panel keeps `messages.yml`-driven labels, border, fill, and close slot.
- Existing tool behaviour (Restart, Backup, the GUIs being linked) is unchanged.
- No change to the login path or the ban system.

## Testing

- **Unit (MockBukkit + JUnit 5, matching existing GUI/listener tests):**
  - Admin panel: clicking the new ModStats/ChatLog/Templates slots opens the right GUI
    (or at least does not error and is wired to the correct handler) — mirror existing
    `AdminPanelGui`-style coverage if present; otherwise a construction/wiring smoke test.
- **Manual:** owner panel Restart (left-click schedule + right-click cancel) and Backup
  (file produced); admin panel new buttons open the correct screens.

## Out of scope

- The other owner-function bundles offered earlier (player control, self-buffs, stealth/
  surveillance) — only "Server control" was chosen.
- **Maintenance / lockdown mode** — explicitly removed from scope; no login-path or
  `OwnerProtectionManager` changes.
- Adding Alts/Notes/Invsee to the admin top level.
- Any redesign of the per-player GUIs themselves.
