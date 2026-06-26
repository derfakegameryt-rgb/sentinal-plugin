# Bulletproof Name & Skin Overrides — Design

**Date:** 2026-06-26
**Status:** Approved (design)
**Strategy:** B — Self-healing reconciliation + fast event path + skin-fetch hardening

## Goal

Proactively harden the staff-set name/skin override system so it survives every
runtime condition without manual intervention. No concrete bug reports drove this;
the aim is to eliminate known and unknown failure modes in the floating nametag and
the Mojang skin fetch, within the existing pure-Paper architecture (no NMS, no
ProtocolLib, no new dependencies).

## Background: current architecture

- **Name override** is display-only on the live player: `playerListName()` (tab) and
  `displayName()` (chat). The above-head name is bound to the GameProfile name at login
  and cannot change mid-session, so it is rendered by `NametagManager` as a `TextDisplay`
  **mounted as a passenger** of the player. The vanilla above-head name is suppressed via
  a shared scoreboard team (`sentinel_nick`, name-tag visibility `NEVER`).
- **Skin override** swaps the `textures` property on the Paper `PlayerProfile`. It is
  injected into the login profile in `applyOnLogin` (pre-login) and applied live via a
  hide/show "resend" so other clients re-render.
- Persistence is SQLite (`profile_overrides`); writes route through the single-writer
  DB executor; entity mutations run on the entity thread; the scheduler is Folia-aware
  (`runGlobal` / `runForEntity` / `globalTimer` / `asyncTimer`).

### Known fragile points being addressed

0. **Owner sees its own floating name lag / hang in mid-air (observed bug).** The `TextDisplay`
   is a passenger of the player. The owner's client predicts its own movement locally while the
   passenger's position is server-driven, so to the owner the name lags or stays put; other
   players (player + passenger both server-positioned) see it follow correctly. Fix: hide the
   display from its owner (`Player#hideEntity`) — also vanilla-correct, since you never see your
   own above-head name.
1. **Nametag passenger detachment.** Only `PlayerRespawnEvent` and
   `PlayerChangedWorldEvent` re-apply the floating name today (`NametagListener`). A
   detached passenger leaves the player with no above-head name (and the vanilla name
   still suppressed) until a relog. Uncovered: teleport (cross-world / long distance),
   vehicle mount/dismount (passenger-stack disruption), and any plugin-driven entity
   manipulation we cannot enumerate.
2. **Skin fetch fragility.** `PlayerProfile.complete(true)` is a single network attempt
   with no retry and no caching. A transient Mojang failure or brief rate-limit makes
   `setSkin` silently return `false`. Repeated sets of the same source re-hit Mojang.

## Design

### 1. Self-healing nametag reconciliation (the catch-all)

Add one repeating task, owned by the plugin and cancelled on disable via the existing
`cancelAll()` / `disableAll()` path.

- Scheduled with `scheduler().globalTimer(task, delay=40L, period=40L)` — every **40 ticks
  (~2s)**.
- Iterates **only** online players with a cached override
  (`plugin.profile().overrideJoinName(uuid) != null`). This is typically a handful of
  players, so the per-pass cost is negligible.
- For each such player, calls `plugin.nametags().refresh(player)`. `refresh()` is already
  self-correcting:
  - no-ops when the `TextDisplay` is still a mounted passenger (just updates text),
  - clears a stale/detached/dead display and remounts a fresh one,
  - re-asserts the `sentinel_nick` no-nametag team,
  - respects vanish (never floats a name on a vanished player).
  A single `refresh()` call therefore repairs **any** drift regardless of which event
  caused the detachment — this is what makes unknown failure modes irrelevant.
- In the same pass, re-assert the tab/chat display name (`playerListName` / `displayName`)
  via the rendered override component, so a TAB/prefix plugin that overwrites those is
  corrected too. This re-assert runs on the entity thread (reuse `runForEntity`).

The reconciliation lives in `NametagManager` (e.g. a `startReconciliation()` /
`stopReconciliation()` pair holding the `TaskHandle`), started from `Sentinel` after the
managers are constructed and stopped from `disableAll()`.

### 2. Fast event-path additions

So repairs feel instant rather than up-to-2s late, extend `NametagListener` with two more
handlers, both delegating to the existing `reapplyNextTick(player)` (2-tick delay so the
entity has settled in its new state):

- `PlayerTeleportEvent` — cross-world / long-distance passenger loss.
- `VehicleEnterEvent` and `VehicleExitEvent` — passenger-stack disruption when the player
  rides a boat / horse / minecart.

These are an optimisation for the common cases; §1 remains the backstop for everything
else. Guard each so an offline/edge state cannot throw.

### 3. Skin fetch hardening

In `ProfileManager`:

- **Retry with backoff.** Introduce a small private helper that completes a profile with up
  to **3 attempts** and short async sleeps between them (0 / 250 / 750 ms). It runs entirely
  on the async thread (never blocks an entity/main thread). `setSkin`'s fetch and the
  reset-restore fetch both use it.
- **Texture cache.** A `ConcurrentHashMap<String, CachedTexture>` keyed by source name
  (lower-cased), value `{value, signature, fetchedAtMillis}`, ~**5 min TTL**. `setSkin`
  checks the cache before fetching and populates it on a successful fetch. This avoids
  re-hitting Mojang for repeated sets and lets a set succeed during a momentary outage if
  the source was fetched recently.
- **Failure behaviour unchanged.** On exhausted retries with no cache hit, `done.accept(false)`
  and the staff member is notified — no silent corruption, name override (if any) untouched.

### 4. Explicitly unchanged

- No new dependencies; pure Paper API.
- Login skin injection (`applyOnLogin`), two-phase name application, MiniMessage cosmetic
  validation, vanish integration, audit logging, and DB write-routing all stay as-is.
- Reset semantics unchanged (immediate network-free revert; best-effort skin restore).

## Testing

- **Unit:** retry helper — succeeds on 2nd attempt, gives up after 3 — using a stubbed
  completer with a call counter; texture cache — hit within TTL, miss after expiry.
- **Manual / integration:**
  - Nametag self-heals after: teleport across worlds, mounting+dismounting a boat/horse,
    and a forced passenger eject — confirm the floating name returns within ~2s (or instantly
    via the event path) and the vanilla name stays suppressed.
  - Skin set succeeds when the first `complete` attempt is made to fail (simulated), proving
    retry; repeated set of the same source does not issue a second Mojang call within TTL.

## Out of scope

- Switching the nametag to per-tick teleport tracking (Strategy C).
- Packet-level / NMS approaches.
- Changing the GameProfile login name (kept as the real account name to avoid the vanilla
  "(formerly known as …)" message).
