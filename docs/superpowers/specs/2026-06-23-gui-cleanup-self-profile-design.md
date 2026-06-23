# Self-Profile in Admin Panel + Hub-Menu Cleanup — Design

**Date:** 2026-06-23
**Status:** Approved (design)

## Problem

The profile override (display name + skin) currently lives in the per-player **PlayerActions** menu,
mixed in with ban/mute/kick. The owner wants it instead as a **self-service tool in the admin panel**:
the staff member who clicks it changes **their own** name and skin. Separately, the hub menus have grown
uneven (left-aligned groups, an asymmetric PlayerActions grid) and should be tidied into a clean,
consistent layout.

## Goal

1. **Move profile editing to self-service in the admin panel.** Add *Set name*, *Set skin*, *Reset
   profile* buttons to `AdminPanelGui`; each acts on the clicking staff member. Remove the three
   profile buttons (and their handlers) from `PlayerActionsGui`.
2. **Clean up the hub menus** into a consistent, centered, bordered layout: `AdminPanelGui` and
   `PlayerActionsGui` get re-slotted; `OwnerPanelGui` and `PlayersGui` are confirmed already on the
   consistent pattern and left as-is (YAGNI — no churn where it's already clean).

Non-goals: changing *other* players' name/skin (that capability is dropped); touching list/detail GUIs
beyond the four hubs; changing the `ProfileManager` backend or persistence.

## Decisions

- **Self-target.** The admin-panel buttons pass the clicking player as the target to the existing
  `ProfileManager.setName(Player, String, String)` / `setSkin(Player, String, String, Consumer<Boolean>)`
  / `reset(Player, String)`. No backend change. Because overrides persist by UUID and re-apply via the
  login profile, the admin's own override survives relog — and after a relog they see their **own** new
  skin/above-head name correctly (the live-change self-view lag noted in the prior profile spec still
  applies only to the immediate moment before relog).
- **Permission unchanged.** Each handler checks `staffPerms().canUse(p, "sentinel.profile")` (default
  `op`). In practice only operators can open the admin panel anyway; the check is defense in depth.
- **`ProfileManager` is reused as-is.** No new manager, DAO, or schema.

## 1. Layout system (consistent across hubs)

Reuse the existing `Gui` helpers: `border()` (accent ring), `fillEmpty()` (black glass). Principle for
every hub: a bordered ring, related buttons grouped contiguously per interior row and centered, the head
display (where present) in the top row, and the bottom row carrying a centered Back/Close cluster (Back
left of centre, Close right of centre; Close-only for root panels).

Interior slots:
- **54-slot menu** (border): rows `10–16`, `19–25`, `28–34`, `37–43`; bottom row `45–53` (centre 49).
- **45-slot menu** (border): rows `10–16`, `19–25`, `28–34`; top row `0–8` (centre 4); bottom row
  `36–44` (centre 40).

## 2. `AdminPanelGui` (54, bordered) — new layout

| Row (group) | Slots | Buttons |
|---|---|---|
| General | 11,12,13,14 | Info, Operators, Whitelist, Playtime |
| Moderation | 19–24 | Bans, Mutes, Reports, Appeals, Audit, Announce(toggle) |
| Self & tools | 28–33 | Player Manager, Vanish, Staff Chat, **Set name**, **Set skin**, **Reset profile** |
| Control | 49 | Close |

Slot constants become: `INFO=11, OPS=12, WHITELIST=13, STATS=14; BANS=19, MUTES=20, REPORTS=21,
APPEALS=22, AUDIT=23, ANNOUNCE=24; PLAYERS=28, VANISH=29, STAFFCHAT=30, SETNAME=31, SETSKIN=32,
RESETPROFILE=33; CLOSE=49`.

New button items (icons consistent with the old PlayerActions ones): Set name = `NAME_TAG`, Set skin =
`PLAYER_HEAD`, Reset profile = `WATER_BUCKET`.

New handlers in `onClick` (each gates on `sentinel.profile`, then acts on the clicking player `p`):
- **SETNAME** → `p.closeInventory()`; send `profile-enter-name`; `chatInput().await(p.getUniqueId(), input
  -> { if (!ProfileManager.isValidName(input)) send profile-bad-name; else { profile().setName(p, input,
  p.getName()); send profile-name-set with player=p.getName(), name=input; } })`.
- **SETSKIN** → `p.closeInventory()`; send `profile-enter-skin`; `chatInput().await(..., input ->
  profile().setSkin(p, input, p.getName(), ok -> send ok ? profile-skin-set : profile-skin-not-found,
  player=p.getName(), name=input))`.
- **RESETPROFILE** → `profile().reset(p, p.getName())`; send `profile-reset` with player=p.getName();
  `p.closeInventory()`.

## 3. `PlayerActionsGui` (45, bordered) — cleaned grid

Remove `SETNAME/SETSKIN/RESETPROFILE` slot constants, their three `inventory.setItem(...)` calls, and
their three `case` handlers. Re-slot the remaining buttons into a clean grid and add `border()`:

| Row (group) | Slots | Buttons |
|---|---|---|
| Head | 4 | target player head (status lore) |
| Punishments | 10–16 | Ban/Unban, TempBan, Mute/Unmute, TempMute, Kick, Warn, Shadowmute/Un- |
| Tools | 19–25 | IP-Ban*, Freeze/Unfreeze*, Invsee*, Ender chest*, History, Notes, Alts |
| Meta | 30,31,32 | Templates, Chat logs, OP/De-OP toggle |
| Control | 38, 42 | Back (38), Close (42) |

`*` = conditional (IP-Ban only when a last IP is known; Freeze/Invsee/Echest only when the target is
online), exactly as today — conditional buttons simply leave their slot to the border/filler.

Slot constants become: `HEAD=4; BAN=10, TEMPBAN=11, MUTE=12, TEMPMUTE=13, KICK=14, WARN=15,
SHADOWMUTE=16; IPBAN=19, FREEZE=20, INVSEE=21, ECHEST=22, HISTORY=23, NOTES=24, ALTS=25; TEMPLATES=30,
LOGS=31, OPTOGGLE=32; BACK=38, CLOSE=42`. Call `border()` then `fillEmpty()` in the constructor (Back/
Close overwrite the bottom border glass at 38/42). Add `Material.OAK_DOOR` Back + `Material.BARRIER`
Close items (Close already exists; Back already exists — only their slots change).

## 4. `OwnerPanelGui` and `PlayersGui` — confirmed consistent, unchanged

- `OwnerPanelGui` already uses `border()` + a centered status head (slot 4) + centered toggles (20, 22,
  24) + centered Close (49). It matches the system; **no change**.
- `PlayersGui` is a paginated list using the shared `navBar()` (Prev 45 / Back 48 / Close 50 / Next 53,
  action buttons 46–47); content heads fill 0–44. It matches the system; **no change**.

Stating this explicitly avoids needless re-slotting (and test churn) where the layout is already clean.

## 5. Messages

- Reuse the flat `profile-*` keys unchanged (`profile-enter-name`, `profile-enter-skin`,
  `profile-name-set`, `profile-skin-set`, `profile-skin-not-found`, `profile-bad-name`, `profile-reset`,
  `profile-target-offline` — the last is now unused and removed).
- Move the GUI labels from `gui.actions.{setname,setname-lore,setskin,setskin-lore,resetprofile,
  resetprofile-lore}` to `gui.panel.{...}` (same English text; German continues to fall back to English,
  as the other `gui.panel.*` labels already do). Delete the old `gui.actions.*` profile keys.
- `profile-name-set` / `profile-skin-set` wording stays player-scoped (`<player>` = the admin's own
  name), which reads fine for self-service ("<You> are now shown as …" works with the name substituted).

## 6. Only real buttons click & make sound

Today `GuiListener.onClick` calls `playClick(...)` on **every** click inside a Sentinel GUI (before
`gui.onClick`), so clicking the border/filler glass plays the `UI_BUTTON_CLICK` sound even though those
slots do nothing — which is the reported annoyance.

Fix: play the sound only when the clicked slot holds an **actionable** item.

- Add `Items.isDecorative(ItemStack)` → true for the two non-interactive panes
  (`BLACK_STAINED_GLASS_PANE` = filler, `LIGHT_BLUE_STAINED_GLASS_PANE` = accent/border).
- In `GuiListener.onClick`, gate the sound:
  ```java
  if (event.getInventory().getHolder() instanceof Gui gui) {
      if (isActionable(event)) playClick(event.getWhoClicked());
      gui.onClick(event);
  }
  ```
  where `isActionable` is true only when the raw slot is inside the GUI's top inventory
  (`0 <= rawSlot < inventory.getSize()`) and `event.getCurrentItem()` is non-null and not decorative.

`gui.onClick` is still always called (every Gui already cancels the event there, so glass and
own-inventory clicks remain inert) — only the **sound** is suppressed for non-buttons. No behaviour
change for real buttons.

## 7. Testing

- **AdminPanelGuiTest** — update expected slots for the regrouped general/moderation/tools rows; add
  assertions that slots 31/32/33 are `NAME_TAG`/`PLAYER_HEAD`/`WATER_BUCKET`.
- **New AdminPanel self-profile test** — MockBukkit: a player clicks SETNAME's slot, supplies chat input,
  and the override is stored for *that* player (assert via `ProfileOverrideDao.find(player uuid)` after
  the async write) — mirrors the existing profile tests, but self-targeted. (Skin rendering stays manual.)
- **PlayerActionsGuiTest / PlayerActionsGuiToolsTest** — update expected slots to the new grid.
- **Remove PlayerActionsProfileButtonsTest** (its subject moved); its intent is re-covered by the new
  AdminPanel test.
- **GuiLayoutTest** — re-run; update any hard-coded slot expectations for the two changed hubs.
- **ItemsTest** — add a pure unit test for `Items.isDecorative`: true for `BLACK_STAINED_GLASS_PANE`
  and `LIGHT_BLUE_STAINED_GLASS_PANE`, false for a real button material (e.g. `BARRIER`) and null.
- Live skin/above-head rendering remains a **manual** check on a real server.

## 8. Files touched

- Changed: `gui/AdminPanelGui` (regroup + 3 self buttons + handlers), `gui/PlayerActionsGui` (remove 3
  buttons/handlers, re-slot + border), `gui/GuiListener` (gate the click sound on actionable slots),
  `util/Items` (add `isDecorative`), `resources/messages.yml` (move `gui.actions.*` profile labels to
  `gui.panel.*`, drop `profile-target-offline`), `README.md` (note profile editing is self-service in the
  admin panel).
- Tests: `gui/AdminPanelGuiTest`, `gui/PlayerActionsGuiTest`, `gui/PlayerActionsGuiToolsTest`,
  `gui/GuiLayoutTest`, `util/ItemsTest` updated; `gui/PlayerActionsProfileButtonsTest` removed; new
  `gui/AdminPanelProfileTest`.
- Unchanged: `ProfileManager`, DAO, schema, `LoginListener`, `OwnerPanelGui`, `PlayersGui`.
