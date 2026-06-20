# `/sn` Hub Menu — Design

**Date:** 2026-06-20
**Status:** Approved (design)

## Problem

`/sn` (no argument) opens the player list (`PlayersGui`) directly. Self-service staff toggles
(Vanish, Staff Chat) live as buttons *inside* that player list, mixing global staff actions with
per-player management. Admins want a top-level **hub** menu as the `/sn` entry point, from which
they pick the Player Manager, Vanish, Staff Chat, and the other panels — with Vanish/Staff Chat
moved out of the player list.

## Goal

- `/sn` with no argument opens a hub menu instead of the player list.
- The hub offers: Player Manager, Vanish, Staff Chat, plus the existing admin panels.
- Vanish and Staff Chat are removed from the player list.
- No behavior change to the underlying actions (vanish/staff-chat toggles, player management).

Non-goals: the Audit Log and Stats entries (added later by the audit-log feature; their hub
slots are simply left free for now). No change to `/sn <player>`.

## Approach

Repurpose the existing `AdminPanelGui` as the hub (chosen over a new third menu layer). `/sn`
no-arg opens it; it gains Player Manager + Vanish + Staff Chat entries; the existing child GUIs
already return to it via "Back".

## Components

### `SentinelCommand` (entry point)

The no-argument branch currently calls `PlayersGui.open(plugin, 0, mod)`. Change it to
`new AdminPanelGui(plugin).open(mod)`. The `/sn <player>` and `/sn <subcommand>` branches are
unchanged.

### `AdminPanelGui` (the hub)

- Expand the inventory from 27 to **36 slots** (4 rows) to fit the new entries cleanly, keeping
  the existing `border()` + `fillEmpty()` styling.
- Add three entries (reusing the existing `button(Material, title, hint)` helper):
  - **Player Manager** → `PlayersGui.open(plugin, 0, viewer)`
  - **Vanish** → `boolean v = plugin.vanish().toggle(viewer); viewer.sendMessage(plugin.messages().prefixed(v ? "vanish-on" : "vanish-off"));`
  - **Staff Chat** → `boolean on = plugin.staffChat().toggle(viewer.getUniqueId()); viewer.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));`
- Keep the existing entries: Server Info, Operators, Active Bans, Active Mutes, Reports,
  Whitelist, Playtime, Appeals.
- Remove the `BACK` button (the hub is the root); keep `CLOSE`. (Child GUIs still open the hub
  via their own Back buttons.)
- Pick concrete, non-overlapping slot indices for all entries within the 36-slot layout; leave a
  couple of empty interior slots reserved for the future Audit/Stats entries.

### `PlayersGui` (now a hub child)

- Remove the `STAFF` (toggle staff chat) and `VANISH` (toggle vanish) buttons and their
  `onClick` handling.
- Rename/repurpose the existing `PANEL` button (which already opens `AdminPanelGui`) to read
  "Back to menu" — its action (open the hub) is unchanged, so it serves as the back-to-hub button.
- Keep `SEARCH`, `REPORTS`, `PREV`, `NEXT`, `CLOSE` and the per-player heads.

### Other child GUIs

`ActiveBansGui`, `ActiveMutesGui`, `ReportsGui`, `WhitelistGui`, `OperatorsGui`, `ServerInfoGui`,
`StatsGui`, `AppealsGui` already return to `AdminPanelGui` via Back — unchanged and correct now
that the hub is `AdminPanelGui`.

## Error handling

Pure GUI navigation; the vanish/staff-chat toggle logic is unchanged (moved, not modified). No
new failure modes.

## Testing

- `AdminPanelGuiTest`: the hub renders Player Manager, Vanish, and Staff Chat buttons; clicking
  Player Manager opens `PlayersGui`; clicking Vanish toggles the viewer's vanish state; clicking
  Staff Chat toggles staff-chat state; existing entries still present.
- `PlayersGuiButtonsTest` / `GuiLayoutTest`: the player list no longer has Vanish/Staff-Chat
  buttons; the back button opens the hub; no slot-index collisions.
- Command test (`SubcommandTest` or equivalent): `/sn` with no arg opens `AdminPanelGui`;
  `/sn <player>` still opens `PlayerActionsGui`.
- The full existing suite stays green.

## Risks

- Slot-index reshuffle in `AdminPanelGui` could collide with existing handlers — mitigated by
  defining all slot constants explicitly and a layout test.
- A test elsewhere may assume `/sn` opens the player list — update it to the new hub entry.
