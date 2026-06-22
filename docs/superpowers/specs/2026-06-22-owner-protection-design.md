# Owner Protection — Design

**Date:** 2026-06-22
**Status:** Approved (design)

## Problem

The plugin already knows a single hard-coded owner (UUID `6500ca9a-a10c-40a5-b985-a56ca9ff1d1e`,
the player DerFakeGamer) via `OwnerManager`. The owner wants a hidden, toggleable protection so that
**no other player** can run any command — vanilla (`/effect`, `/give`, `/kill`, `/deop`, …) or plugin
(`/ban`, `/mute`, …) — that targets the owner. Such attempts must fail as if the owner did not exist
("that entity does not exist"). Only the owner himself and the server console may manipulate the owner.

## Goal

Re-add a hidden owner-only `/sn owner` that opens an **Owner Panel** (double chest) with three
owner-self-protection toggles:

1. **Owner Protection** — a command listener cancels any non-owner player command that references the
   owner (by current name or via a target selector) and replies with a vanilla-style
   "that entity does not exist". The console is never intercepted. The owner is never blocked. The plugin
   GUI vector (opening the owner's action menu) is closed the same way.
2. **Auto Unban** — the owner can never stay banned: a ban on the owner is lifted automatically at login
   (and immediately when the toggle is switched on), and the owner is allowed in.
3. **Auto Whitelist** — the owner is kept on the server whitelist automatically (added when the toggle is
   switched on and on plugin enable), so the whitelist can never lock the owner out.

All three toggles persist across restart and default to off.

Non-goals: protecting other players; blocking the console; rate-limiting; intercepting command blocks;
configurable owner (stays hard-coded). No new permission nodes.

## Hidden by design

Like the prior owner system, the feature leaves no visible trace in `config.yml`, `messages.yml`, or
`plugin.yml`: the `/sn owner` subcommand is not advertised, the panel title/labels and the block message
are **hard-coded Components** (not message keys), and the toggle state is stored in the DB `settings`
table (key `owner_protect`), not in a config file. Non-owners who type `/sn owner` get the vanilla
`Unknown command. Type "/help" for help.` reply and never see it in tab-completion.

## 1. Detection logic (pure, testable)

`OwnerProtectionManager.affectsOwner(String commandLine, String ownerName)` — static, pure, never throws:

- Strip a leading `/`, trim, split on whitespace.
- Scan the **arguments** (skip index 0, the command label). A command "affects the owner" if any
  argument token:
  - equals `ownerName` case-insensitively (exact token; `DerFakeGamer123` does NOT match), or
  - starts with a targeting selector `@a`, `@e`, `@p`, or `@r` (case-insensitive; covers `@a[...]`).
- `@s` (self) is intentionally allowed (the sender targeting themselves). A `null`/blank `ownerName`
  disables name matching (selector matching still applies). `null` commandLine → false.

This catches `/effect DerFakeGamer …`, `/give DerFakeGamer …`, `/kill DerFakeGamer`,
`/deop DerFakeGamer`, `/ban DerFakeGamer …`, and selector forms like `/kill @a`, `/effect @e …`.

## 2. State + persistence (`OwnerProtectionManager`)

Instance holding `Sentinel plugin`, three `volatile boolean` flags (`protect`, `autoUnban`,
`autoWhitelist`), and a `volatile String ownerName` cache (avoids a Bukkit lookup on the command hot
path). Each flag is backed by a `settings` key: `owner_protect`, `owner_auto_unban`,
`owner_auto_whitelist`.

- `void load()` — read all three `settings` keys (default `"false"`) via `plugin.db().submit(...)` and
  set the flags; best-effort (`try/catch` → `plugin.debug`). Called once in `onEnable` after the DB is
  ready.
- `boolean isEnabled()` / `boolean isAutoUnban()` / `boolean isAutoWhitelist()`.
- `void setEnabled(boolean)` / `void setAutoUnban(boolean)` / `void setAutoWhitelist(boolean)` — set the
  flag in memory immediately, then persist its `settings` key via `plugin.db().submitWrite(...)` (writes
  use the writer thread, per the project's write-routing rule). Best-effort (`try/catch` → `plugin.debug`).
- `String ownerName()` — return the cached name; if null, resolve once from the online owner
  (`Bukkit.getPlayer(uuid)`) or `Bukkit.getOfflinePlayer(uuid).getName()`, cache the first non-null
  result, and return it.

`OwnerManager` gains `UUID uuid()` (exposes the hard-coded UUID for the listener and tests) and
`String currentName()` (best-effort current name).

`Sentinel` gains a field, an `ownerProtection()` accessor, constructs the manager after the DB is open,
and calls `load()` in `onEnable`.

## 3. Command listener

`listener/OwnerProtectionListener` on `PlayerCommandPreprocessEvent`
(`priority = LOWEST`, `ignoreCancelled = true`):

- If `!plugin.ownerProtection().isEnabled()` → return.
- If `plugin.owner().isOwner(player)` → return (the owner may target himself).
- If `affectsOwner(event.getMessage(), plugin.ownerProtection().ownerName())` →
  `event.setCancelled(true)`, send the player `Component.text("that entity does not exist", RED)`, and
  `plugin.debug(...)` the blocked attempt (no console spam).

The console is **not** intercepted (no `ServerCommandEvent` handler) → console always succeeds.
Registered in `onEnable` alongside the other listeners.

## 3b. Login behaviors (auto-unban + auto-whitelist)

Both extend the existing `LoginListener.onPreLogin` (`AsyncPlayerPreLoginEvent`) for the owner only:

- **Auto Unban:** if a ban is found for the logging-in player AND `plugin.owner().isOwner(uuid)` AND
  `plugin.ownerProtection().isAutoUnban()` → do **not** disallow (allow the owner in) and best-effort
  `plugin.punishments().unban(uuid, "AUTO", now)` (fire-and-forget; the prelogin thread is fine for the
  DB write). The maintenance and ban-record steps above are unchanged.
- **Auto Whitelist:** the reliable form keeps the owner on the native whitelist so Bukkit's own check
  passes — adding them at the moment the toggle is switched on and on plugin enable, not racing the login
  event. So: when `setAutoWhitelist(true)` is called (panel click, main thread) →
  `Bukkit.getOfflinePlayer(uuid).setWhitelisted(true)`; and in `onEnable`, if `isAutoWhitelist()` →
  ensure the owner is whitelisted (main thread). Switching it off does not remove the owner. As a
  belt-and-suspenders, the owner's `onPreLogin` also sets whitelisted when the toggle is on (scheduled on
  the main thread via `Bukkit.getScheduler().runTask`, since whitelist mutation should not run async).

`setEnabled`/`setAutoUnban` perform their own immediate side effect when switched on: `setAutoUnban(true)`
best-effort unbans the owner now; `setAutoWhitelist(true)` whitelists the owner now. These live in the
manager (so both the panel and a future caller get them) and never throw.

## 4. Owner Panel GUI

`gui/OwnerPanelGui extends Gui`, 54 slots (double chest), dark + blue theme (`border()` + `fillEmpty()`),
all text hard-coded Components:

- Title: `Component.text("Owner", DARK_AQUA)`.
- Status item (slot 4, top row): owner head (`Items.head(Bukkit.getOfflinePlayer(uuid), …)`) showing the
  owner name.
- Protection toggle (slot 20, `Material.SHIELD`): name `Owner Protection: ON` (green) / `OFF` (red),
  lore `Blocks others from targeting you` / `Click to toggle`.
- Auto-Unban toggle (slot 22, `Material.IRON_BARS`): name `Auto Unban: ON` (green) / `OFF` (red),
  lore `Lifts any ban on you automatically` / `Click to toggle`.
- Auto-Whitelist toggle (slot 24, `Material.NAME_TAG`): name `Auto Whitelist: ON` (green) / `OFF` (red),
  lore `Keeps you on the whitelist` / `Click to toggle`.
- Close (slot 49, `Material.BARRIER`).
- `onClick`: cancel the event; if the clicker is not the owner, close and return (defense in depth);
  on a toggle slot flip the matching flag via its setter, record an audit entry
  (`record(name, action, "self", on?"on":"off")` with actions `OWNER_PROTECT` / `OWNER_AUTO_UNBAN` /
  `OWNER_AUTO_WHITELIST`), and rebuild the panel so the buttons reflect the new state; on close, close.

Clicks route through the existing `GuiListener` automatically (any `Gui` holder). A rebuild helper sets
the items; `border()`/`fillEmpty()` only fill empty slots, so re-calling is safe.

## 5. `/sn owner` routing

In `SentinelCommand.onCommand`, after the existing `update` branch, add an `owner` branch: if
`plugin.owner().isOwner(sender)` and the sender is a player → `new OwnerPanelGui(plugin).open(p)`; else
→ send `Component.text("Unknown command. Type \"/help\" for help.", RED)`. In `onTabComplete`, add
`"owner"` to the first-arg options only when `plugin.owner().isOwner(sender)`.

(The existing `sentinel.use` gate at the top still runs first; non-staff get `no-permission`, staff who
are not the owner get the `Unknown command` reply — the subcommand stays hidden either way.)

## 6. Plugin GUI vector

`PlayerActionsGui.open(plugin, target, viewer)` gains a guard at the very top: if
`plugin.ownerProtection().isEnabled() && plugin.owner().isOwner(target) && !plugin.owner().isOwner(viewer)`
→ send the viewer `Component.text("that entity does not exist", RED)` and return without opening. This
closes the players-list → owner head → actions path. (The `/sn <owner>` command path is already blocked
by the listener since it names the owner.)

## Error handling

- `affectsOwner` / `ownerName` / `setEnabled` / `load` never throw into callers (persistence and
  resolution are best-effort, failures go to `plugin.debug`).
- The listener never throws into the event pipeline.

## Testing

- **`affectsOwner` (pure):** matches owner name as an exact arg (case-insensitive), does not match a
  superstring; matches `@a/@e/@p/@r` (incl. `@a[...]`); does not match `@s`; ignores the command label;
  `null`/blank owner name → only selectors match; `null` line → false.
- **Persistence round-trip:** for each of the three flags, the setter then a fresh `load()` (after
  draining the DB executor) reports the value; defaults are all `false`.
- **Listener (MockBukkit):** protection off → never cancels; protection on + non-owner runs a command
  naming the owner → cancelled + the player got "that entity does not exist"; non-owner runs an unrelated
  command → not cancelled; a selector command → cancelled; the owner (a `PlayerMock` created with
  `plugin.owner().uuid()`) → not cancelled.
- **Auto-unban login (MockBukkit):** with a ban on the owner and `autoUnban` on, the owner's prelogin is
  allowed (not disallowed) and an unban is issued; with `autoUnban` off, the owner is disallowed (banned).
- **Auto-whitelist:** `setAutoWhitelist(true)` marks the owner `isWhitelisted()`; on enable with the flag
  on the owner ends up whitelisted.
- **Panel + `/sn owner`:** owner opens `OwnerPanelGui`; a non-owner staff member gets the `Unknown command`
  reply and no panel; clicking each toggle flips the matching flag, persists, and records the matching
  audit entry (`OWNER_PROTECT` / `OWNER_AUTO_UNBAN` / `OWNER_AUTO_WHITELIST`).
- **GUI guard:** under protection a non-owner calling `PlayerActionsGui.open` for the owner gets the
  block message and no inventory opens; the owner can open it; protection off → opens normally.
- Existing tests stay green.

## Risks

- **Command hot path** — the listener runs on every player command; it short-circuits when protection is
  off or the sender is the owner, and uses the cached `ownerName` (no per-command Bukkit lookup once
  resolved). `affectsOwner` is a small token scan.
- **Name resolution before the owner is known** — if the owner has never been seen, `ownerName()` may be
  null; selector matching still protects against `@a/@e/@p/@r`, and name matching resumes once resolved
  (e.g. when the owner joins). Acceptable.
- **False positives** — exact-token name match avoids substrings; `@s` is allowed so players can still
  affect themselves. Blocking `/msg DerFakeGamer` and `/tp x DerFakeGamer` is intended (full protection).
