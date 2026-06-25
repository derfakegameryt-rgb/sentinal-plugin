# Owner Vanish + God-Mode + Targeting-Log + Admin-Vanish equipment hide (v3.2.0) — Design Spec

**Date:** 2026-06-25
**Status:** Approved (user: "mach vanish ... und dann noch 4" → selected God-Mode + Targeting-Log)
**Secrecy:** The owner feature must leave **no visible trace** — all owner-facing text is hard-coded English (never in messages.yml), no audit entries, no console logging. The fake join/leave broadcast uses the **vanilla translatable keys** (`multiplayer.player.left` / `multiplayer.player.joined`) so it is indistinguishable from a real disconnect.

## Goal

Add four owner-only stealth/safety capabilities, plus one improvement to the existing (non-secret) admin vanish.

## Global Constraints

- Target Minecraft 1.21 (Paper/Folia), Java 21, Gradle + shadow. `spotlessCheck` runs in `build` and FAILS on unused imports (4-space indent, no reformat of untouched code).
- **No new dependencies.** Equipment hiding uses Paper's `Player#sendEquipmentChange` (already on the API).
- All entity mutation via `plugin.scheduler()` (`runForEntity` / `runForEntityLater` / `runGlobal`). `onDisable` already cancels all tasks.
- Owner feature text is hard-coded; no `messages.yml` keys; no audit; no console trace.
- Version bumped to **3.2.0** in `build.gradle.kts:8` and `src/main/resources/plugin.yml:2` as the final step (new features → minor bump).

---

## Item 1 — Owner Vanish (new, in the Owner panel)

A second, stronger vanish for the owner — hidden from **everyone** (including ops/other admins), unlike admin vanish (hidden from non-ops only).

- **State:** in-memory in `VanishManager`. The owner's UUID goes in the existing `vanished` set (so `isVanished` / GUI filtering work uniformly) **and** a new `hideFromOps` set (the "hide from all, including ops" tier). Not persisted — vanish is a live presence state, and the owner is offline across a restart anyway.
- **Toggle ON →** hide from every online player; broadcast a fake **leave** message to **everyone, the owner included** (so a lone owner still sees it and gets their own "left the game" line), using the owner's display-name override (nick) if set, else the real name.
- **Toggle OFF →** show to everyone; broadcast a fake **join** message to everyone (same name resolution).
- **Tab list:** `hidePlayer` already removes the owner from every viewer's tab list — no separate handling.
- **Player-manager hiding:** `PlayersGui` filters out any player the viewer cannot see (`!viewer.canSee(p)`); `PlayerActionsGui.open` blocks opening an online target the viewer cannot see ("that entity does not exist"). A fully-hidden owner is therefore absent from the list and un-openable — as if offline.
- **Relog while vanished:** `JoinQuitListener` suppresses the real join/quit broadcast (`event.joinMessage(null)` / `quitMessage(null)`) when the player is owner-tier vanished, and `applyOnJoin` re-hides them from all.

## Item 2 — Admin Vanish: clean appearance for ops (no armor / hand items / potion particles)

The existing admin vanish (`AdminPanelGui` → `VanishManager.toggle`) hides the staff member from non-ops via `hidePlayer`. **Decision (user):** ops keep seeing admin-vanished staff in-world, but as a "clean" plain player. Owner vanish is fully hidden from everyone, so it needs none of this.

- **Armor + hands:** `VanishManager.sendEquip(viewer, staff, hide)` sends `sendEquipmentChange` (AIR when hiding, the real items when restoring) for all six slots. Each send runs on the viewer's region thread (`runForEntity`) for Folia safety, wrapped in try/catch (also safe under MockBukkit). Hide on vanish-ON (to ops) and on op-join; restore on vanish-OFF.
- **Potion particles:** `VanishManager.stripPotionParticles` re-applies each active effect with `particles=false` (remove-then-add so the flag actually replaces; duration/amplifier/ambient/icon preserved). This is server-side global state, so it hides the swirl for every viewer. Restored on vanish-OFF.
- `VanishCloakListener` re-applies both on the next tick after the relevant change: `PlayerArmorChangeEvent` / `PlayerItemHeldEvent` → re-blank equipment; `EntityPotionEffectEvent` (a freshly-added effect that still has particles) → re-strip. Owner-tier (fully hidden) is skipped throughout.
- **Known limit (accepted):** transient, entity-action particles (crit/sprint/sweep) and most world-interaction **sounds** cannot be suppressed through the Paper API for a still-visible player; full suppression would require ProtocolLib (rejected — no new dependency).

## Item 3 — God-Mode (owner)

Persisted toggle in `OwnerProtectionManager` (`owner_god`). `OwnerGodListener` cancels `EntityDamageEvent` for the owner while god-mode is on — covers PvP, fall, lava, fire, drowning, and combat-kick (no death). Button in the Owner panel.

## Item 4 — Targeting-Log ("who tried to hit me")

`OwnerProtectionManager` keeps an in-memory ring buffer (capacity 30, newest first) of blocked attempts: `Attempt(who, detail, at)`.
- `OwnerProtectionListener` records `(attacker, command line)` when it blocks a command targeting the owner.
- `PlayerActionsGui.open` records `(viewer, "opened player menu")` when the owner-protection guard blocks opening the owner's actions GUI.
- New `OwnerAttacksGui` lists the recent attempts (head + who + detail + relative time). Button in the Owner panel.

---

## Owner panel layout (`OwnerPanelGui`)

Existing: STATUS=4, PROTECT=20, AUTO_UNBAN=22, AUTO_WHITELIST=24, CLOSE=49. Add a third row: **VANISH=29, GOD=31, ATTACKS=33**. No existing button moves.

## Release

After review and `./gradlew clean build` green: bump to 3.2.0, generic public release notes (e.g. "vanish now hides equipment; reliability") — **no owner/vanish-from-all/god-mode/targeting mention.**

## Out of scope

- Persisting owner vanish across restarts (in-memory by design).
- The two unpicked menu ideas (Self-disguise, Panic-button) — can be added later.
- ProtocolLib or any packet library — Paper's API suffices.
