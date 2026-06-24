# Join/Quit Message Display-Name Fix — Design Spec

**Date:** 2026-06-24
**Status:** Approved
**Goal:** Make the vanilla join and quit broadcast ("X joined/left the game") use a player's staff-set display-name override instead of their real account name, so an undercover admin's real name is never leaked on join/leave. Ships in v3.1.6 alongside the announcements removal.

## Background / Root Cause

When a player has a profile override (display name set via `/sn` → Set Name), their tab/chat name and skin are correct after rejoin, but the **join message still shows the real account name**. Root cause:

- The server computes and broadcasts the join message with the real account name around `PlayerJoinEvent`.
- `JoinQuitListener.onJoin` calls `ProfileManager.applyNameOnJoin`, which sets the display name only **after an asynchronous DB lookup** (a callback). By then the join message is already out.

The quit message has the identical leak ("RealName left the game").

To rewrite the join/quit message we need the override display name **synchronously** inside the join/quit handler — but a DB read there would block the main thread. The fix pre-fetches the override at pre-login (where a blocking read already happens) into a small thread-safe cache.

## Global Constraints

- Target Minecraft 1.21 (Paper/Folia), Java 21. `spotlessCheck` runs in `build` and FAILS on unused imports.
- No new dependencies. No config/message-schema changes.
- The fix must not block the main thread (no DB read in the join/quit handler).
- Players WITHOUT a display-name override keep the exact default join/quit message (no behaviour change for them).
- Owner secrecy: this is itself a secrecy hardening (stops the real-name leak); no owner data is logged.

## Design

### Component: a synchronous override-name cache in `ProfileManager`

- Add `private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, String> joinNames = new ConcurrentHashMap<>();` — maps an online (or logging-in) player's UUID to their effective display-name override.
- `public String overrideJoinName(java.util.UUID id)` — returns the cached display name, or `null` if the player has no override. Read by the join/quit handler on the main thread.
- `public void evictJoinName(java.util.UUID id)` — removes an entry (called on quit).
- The cache is populated/maintained wherever the override changes for a player:
  - **`applyOnLogin`** (pre-login, blocking read already present): fetch the override row once; if it has a non-null display name, `joinNames.put(id, name)`, else `joinNames.remove(id)`. This must run **regardless of whether a skin is set** — today `applyOnLogin` returns early when `skinValue == null`; restructure so the name is cached before that skin early-return. (This is the core fix path for join/rejoin.)
  - **`setName`** (its existing online callback): after `applyLive`, `joinNames.put(id, name)` so a mid-session rename keeps the quit message correct.
  - **`reset`** (its existing immediate online block): `joinNames.remove(id)` so a quit after reset uses the real name again.

### Component: join/quit message rewrite in `JoinQuitListener`

- Add a pure helper: `static net.kyori.adventure.text.Component nameMessage(String key, String name)` returning `Component.translatable(key, net.kyori.adventure.text.format.NamedTextColor.YELLOW, Component.text(name))`. This reproduces the vanilla yellow "… joined/left the game" line with the chosen name.
- `onJoin`: `String dn = plugin.profile().overrideJoinName(player.getUniqueId()); if (dn != null) event.joinMessage(nameMessage("multiplayer.player.joined", dn));` — placed alongside the existing `applyNameOnJoin` call (which still handles tab/chat).
- `onQuit`: `String dn = plugin.profile().overrideJoinName(id); if (dn != null) event.quitMessage(nameMessage("multiplayer.player.left", dn));` then `plugin.profile().evictJoinName(id);` (always evict, even when `dn == null`).

### Data flow

pre-login (async, blocking read) → `applyOnLogin` caches override name → join (main thread) reads cache synchronously → rewrites `joinMessage` with the display name. No main-thread DB read; no async delay on the message.

### Error handling

- If the pre-login DB read fails, `applyOnLogin` already returns without caching → the player simply gets the default (real-name) message, exactly as today. No regression, fail-open.
- Cache entries are evicted on quit, so the map only holds currently-relevant UUIDs.

## Testing

- **`nameMessage` (pure, new `JoinQuitListenerTest`):** `nameMessage("multiplayer.player.joined", "Nicked")` is a `TranslatableComponent` with key `multiplayer.player.joined`, colour yellow, and a single text argument `"Nicked"`.
- **Cache via login (extend `ProfileLoginListenerTest` or new test):** after inserting an override row with display name `"Nicked"` and running `LoginListener.onPreLogin`, `plugin.profile().overrideJoinName(id)` returns `"Nicked"`; for a UUID with no override it returns `null`.
- **Join-message rewrite (integration):** with the cache populated for a player's UUID, running `JoinQuitListener.onJoin` on a `PlayerJoinEvent` for that player sets `event.joinMessage()` to a translatable whose argument is `"Nicked"` (not the real name). For a player with no override, `event.joinMessage()` is left unchanged.
- **Quit eviction:** after `onQuit`, `overrideJoinName(id)` returns `null`.

## Out of Scope

- Changing tab/chat name application (`applyNameOnJoin` stays as-is for tab/chat).
- The above-head name (still the real account name, by the v3.1.2 design — display name is tab/chat only).
- Any non-override player's join/quit message — unchanged.
