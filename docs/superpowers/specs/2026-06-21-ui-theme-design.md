# Chest-UI Modernisation — Dark + Blue Accent Theme — Design

**Date:** 2026-06-21
**Status:** Approved (design)

## Problem

The chest GUIs are structurally tidy (unified control row, i18n text) but visually plain: empty slots
and borders are gray glass, which reads as dated. The user wants a clean, modern look.

## Goal

Apply a "dark + blue accent" theme centrally so every GUI is restyled with no per-GUI edits and no
loss of content capacity: black glass for empty/content filler, the plugin's blue accent for the
bottom control bar and menu borders.

Non-goals: per-GUI layout changes; header/context items; reworking button materials (those were the
heavier options and are out of scope); changing slot counts or losing list capacity.

## 1. Palette (`util/Items`)

- `Items.filler()` returns `BLACK_STAINED_GLASS_PANE` (was `GRAY_STAINED_GLASS_PANE`) — the default
  empty-slot filler used everywhere via `fillEmpty()`.
- New `Items.accent()` returns `LIGHT_BLUE_STAINED_GLASS_PANE` — the theme accent, closest stock glass
  to the plugin blue `#3B82F6`. Both use a single blank display name (space), like the current filler.

## 2. Central restyle (`gui/Gui`)

- `border()` draws the outer frame with `Items.accent()` (blue) instead of gray filler. This affects
  the bordered menus: `AdminPanelGui` (hub), `ServerInfoGui`, `ConfirmGui`, `ReasonGui`.
- `navBar()` fills the bottom control row (slots 45–53) with `Items.accent()` (a blue action bar)
  before placing Previous(45)/Back(48)/Close(50)/Next(53); per-GUI action buttons (46/47/51/52) still
  overwrite the accent at their slots. The final `fillEmpty()` then fills only the remaining empty
  CONTENT slots (0–44 on partial pages) with black `Items.filler()`.
- The nav icons (ARROW for prev/next, OAK_DOOR for back, BARRIER for close) are already unified from
  the prior control-row refactor and are unchanged.
- Net effect: every list GUI gets a blue control bar + black content filler; every bordered menu gets
  a blue frame + black interior filler. No per-GUI code changes are required.

## Error handling

Pure cosmetic item construction; no new failure modes. `Items.accent()` mirrors `Items.filler()`
(a plain named pane), cannot throw.

## Testing

- `Items` returns the expected materials: `filler()` → BLACK_STAINED_GLASS_PANE, `accent()` →
  LIGHT_BLUE_STAINED_GLASS_PANE.
- `GuiLayoutTest`: the existing border-corner assertion is updated from GRAY to the accent material
  (LIGHT_BLUE) and renamed accordingly.
- A control-row test (a list GUI): an EMPTY control-row slot (e.g. slot 49, between Back and Close) is
  the accent (LIGHT_BLUE) pane, while an empty CONTENT slot (a high slot on a partial page) is the
  black filler — proving content filler and control-bar accent are distinct.
- All existing GUI tests stay green (they assert button presence/behaviour and display names, not
  filler colour, except the one border test updated above).

## Risks

- **Material name correctness** — `BLACK_STAINED_GLASS_PANE` / `LIGHT_BLUE_STAINED_GLASS_PANE` exist
  in the Paper 1.21 `Material` enum; verified at implementation.
- **A test asserting the old gray filler** — only `GuiLayoutTest.menuGuiHasGrayBorder` is known to;
  it is updated. The implementer greps for any other gray-glass assertion before finishing.
