# One-Click Server Optimize — Design

**Date:** 2026-06-22
**Status:** Approved (design)

## Problem

Server admins must hand-tune `view-distance` and `simulation-distance` for their hardware. Sensible
values depend on allocated RAM, and the simulation distance (the TPS-heavy setting) should be lower
than the view distance. A one-click button that detects RAM and applies a researched preset removes
the guesswork.

## Goal

Add an "Optimize server" button to `ServerInfoGui` that shows the current view/simulation distance (in
red) and the RAM-based recommended values (in green), and on click applies them to every world at
runtime AND persists them to `server.properties`.

Non-goals: tuning anything beyond view/simulation distance; auto-applying without a click; scaling by
player count or CPU cores (RAM-tiered only for v1).

## 1. Recommendation logic (`util/ServerOptimizer`, pure + testable)

A RAM-tiered preset. The max heap (`Runtime.getRuntime().maxMemory()`) is rounded to the nearest GB
(so `-Xmx4G`, which reports ~3.9 GB, counts as 4); the chosen tier is the highest threshold ≤ that
rounded GB; below 1 GB uses the 1 GB tier; above 32 GB uses the 32 GB tier.

| RAM (GB) | view | sim |
|---|---|---|
| 1 | 4 | 3 |
| 2 | 6 | 4 |
| 4 | 7 | 5 |
| 6 | 8 | 5 |
| 8 | 9 | 6 |
| 12 | 10 | 6 |
| 16 | 11 | 7 |
| 24 | 12 | 7 |
| 32 | 12 | 8 |

Rationale: simulation distance is the dominant TPS cost (entity/redstone/crop ticking), so it stays
lower than view and is capped at 8; view distance is more RAM/bandwidth-bound and scales higher, capped
at 12. Simulation is always strictly less than view in every tier.

API:
- `record Preset(int view, int sim)`.
- `static Preset recommend(long maxMemoryBytes)` — pure tier lookup (the table above), the unit-tested
  core.
- `static long roundedGb(long maxMemoryBytes)` — `Math.round(bytes / 1073741824.0)` (helper, testable).

## 2. Apply

`ServerOptimizer` (instance, holds `Sentinel`) applies a `Preset`:

- **Runtime:** for every `World` in `Bukkit.getWorlds()`, call `setViewDistance(view)` and
  `setSimulationDistance(sim)` — takes effect immediately.
- **Persist:** rewrite `server.properties` line-based — replace the value after `view-distance=` and
  `simulation-distance=`, leaving every other line and all comments untouched. A pure helper
  `String replaceProperty(String fileContent, String key, int value)` does the text edit (unit-tested);
  the file read/write wraps it. **Best-effort:** if `server.properties` is absent or unreadable (e.g.
  under MockBukkit), persistence is skipped silently — the runtime apply still happened. File I/O runs
  off the main thread is unnecessary here (a one-shot tiny file on a button click is fine on main, but
  wrap in try/catch so it never throws into the click handler).
- Current values are read from the primary world: `Bukkit.getWorlds().get(0).getViewDistance()` /
  `.getSimulationDistance()` (guard empty world list).

## 3. Button in `ServerInfoGui`

- A new button at a free slot (e.g. 22), material `Material.REDSTONE_COMPARATOR` or `DIAMOND_PICKAXE`
  (a "tuning" icon; pick one consistent with the theme).
- Lore (MiniMessage via message keys, with placeholders):
  - current: `<red>View: <view>  ·  Sim: <sim>` (the live values, in red)
  - recommended: `<green>Recommended (<ram> GB): View <recView>  ·  Sim <recSim>` (in green)
  - hint: `<gray>Click to apply`
- `onClick` on that slot: re-check `plugin.staffPerms().canUse(p, "sentinel.use")` (defense in depth,
  send `no-permission` + return if not); apply the recommended preset; record an audit entry
  (`actor`, `"OPTIMIZE"`, target `"server"`, details `"view=<v> sim=<s>"`); send the player the
  `optimize-applied` message; rebuild/refresh the GUI so the lore now shows the new current values
  (in red) matching the recommendation.
- The button is shown to the existing `ServerInfoGui.update()`/static layout consistently; the
  `update()` timer already refreshes dynamic items — the optimize lore is rebuilt on apply (and on open).

## 4. Config / i18n / messages

- No new config keys (RAM is detected; presets are fixed in code).
- New message keys (English defaults, auto-merged): `gui.serverinfo.optimize` (button name),
  `gui.serverinfo.optimize-current`, `gui.serverinfo.optimize-recommended`, `gui.serverinfo.optimize-hint`
  (lore lines with the placeholders above), and `optimize-applied` (chat feedback, e.g.
  `"<#60A5FA>Server optimised — view-distance <white><view></white>, simulation-distance <white><sim></white>."`).
- README: a short note on the Optimize button and the preset table.

## Error handling

- `recommend`/`roundedGb`/`replaceProperty` are pure and never throw (clamp to the table bounds).
- Apply wraps world-setting and file I/O in try/catch; a failure to persist (or set a world) never
  throws into the click handler and never spams the console (a single `plugin.debug(...)` on failure).
- Empty world list → no runtime change; the button still computes the recommendation from RAM.

## Testing

- `ServerOptimizerTest` (pure): `recommend` returns the exact table value for each tier boundary
  (1/2/4/6/8/12/16/24/32 GB) and for in-between values (e.g. 5 GB → 4 GB tier, 10 GB → 8 GB tier);
  below 1 GB → 1 GB tier; above 32 GB → 32 GB tier; `sim < view` for every tier; `roundedGb` rounds
  `-Xmx4G`-like values (≈3.9 GB) to 4.
- `replaceProperty`: replaces an existing `key=...` line's value, preserves other lines + comments; if
  the key is absent, appends it (or leaves content unchanged — pick one and test it).
- GUI (MockBukkit): the optimize button exists with current values in red and recommended in green
  (assert the rendered lore text + that current vs recommended are distinct); clicking it sets every
  world's view/simulation distance to the preset and writes an audit entry; a non-op clicking is
  rejected with `no-permission` and nothing changes.
- Existing `ServerInfoGui` tests stay green.

## Risks

- **`server.properties` write** — line-based edit preserves the file; best-effort + try/catch so a
  read-only or absent file never breaks the button (runtime apply still works). Persisted values only
  take full effect after a restart; runtime apply covers the live session.
- **MockBukkit world API** — if `setViewDistance`/`setSimulationDistance` is unsupported in the test
  harness, the GUI test asserts the call path via the primary world or falls back to asserting the
  audit entry + recommendation; the pure `ServerOptimizerTest` is the authoritative coverage.
- **Distance bounds** — values are within Minecraft's accepted range (2–32); the preset max (12) and
  min (3 sim / 4 view) are safe.
