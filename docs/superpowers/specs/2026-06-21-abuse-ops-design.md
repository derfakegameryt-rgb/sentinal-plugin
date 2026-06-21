# Abuse Protection & Operations — Cooldowns + Debug Flag + Permission Audit — Design

**Date:** 2026-06-21
**Status:** Approved (design)

## Problem

Player-facing commands `/report` and `/appeal` have no rate limit, so a single player can spam staff
with reports/appeals. There is no operator-facing debug switch to diagnose issues in production
(only fixed SEVERE/INFO logs). And the permission model is applied unevenly — some commands and GUI
actions may not gate on a permission node — with no single documented model.

## Goal

Add a reusable cooldown for the abuse-prone player commands, a curated `debug` logging switch for
troubleshooting, and a permission audit that closes gaps and documents the model — all without
changing existing healthy behaviour.

Non-goals: cooldowns on every command (only report/appeal); verbose debug logging everywhere (a
curated set only); new finer-grained permission nodes (keep the current `sentinel.use` + per-type
model).

## 1. Cooldowns (abuse protection)

`util/CooldownManager` — in-memory, reusable:

- State: `ConcurrentHashMap<String, Long>` keyed by `uuid + ":" + key`, value = last-use epoch ms.
- `boolean tryUse(UUID id, String key, long cooldownMillis, long now)` — if `cooldownMillis <= 0`
  or the last use is older than `cooldownMillis`, record `now` and return `true`; otherwise return
  `false` (no state change).
- `long remainingMillis(UUID id, String key, long cooldownMillis, long now)` — ms left (>= 0), for
  the rejection message.
- Clock passed in as `now` (callers use `System.currentTimeMillis()`; tests pass a fixed value).

Wiring:
- `ReportCommand` and `AppealCommand` check the cooldown BEFORE doing work. Players with `sentinel.use`
  (staff) bypass it (`plugin.staffPerms().canUse(sender, "sentinel.use")`). On rejection, send the new
  `cooldown` message with the remaining whole seconds and return.
- Config keys (with defaults): `report.cooldown-seconds: 30`, `appeal.cooldown-seconds: 60`; `0`
  disables. Read live per use (so `/sentinel reload` takes effect).
- New message key `cooldown` (e.g. `"<red>Please wait <white><seconds></white>s before doing that again."`).
- `ConfigValidator` checks both keys are non-negative integers.

## 2. Debug flag (operations)

- Config `debug: false`.
- `Sentinel.debug()` returns the current flag (read in `onEnable` and refreshed on reload).
- `Sentinel.debug(String msg)` — logs `"[DEBUG] " + msg` at `INFO` only when the flag is on; a no-op
  otherwise (cheap guard, no string building forced by callers beyond the argument).
- Curated call sites (bounded): applied schema migration version; DB operation failure detail
  (alongside the existing SEVERE — debug adds context/timing); `BatchWriter` flush (count flushed);
  cooldown rejections (who/what/remaining); Discord bot lifecycle (connect/ready/shutdown);
  `ConfigValidator` summary (counts checked/warnings). No debug logging in hot per-tick/per-message
  paths.

## 3. Permission audit (operations)

- Audit every command's `onCommand` and every GUI action handler: confirm an appropriate check is
  present — operator commands require `sentinel.use` (or the existing specific node), punishment
  actions require `canPerform(type)`, and GUI actions mirror the command-side checks. Fix any gap by
  adding the missing check (using the existing `StaffPermissions` API and `no-permission` message).
- `plugin.yml`: ensure every command declares its `permission`, and every referenced permission node
  is declared under `permissions:` with a sensible `default` (operator nodes `default: op`; player
  commands like `/report`, `/appeal`, `/rules` have no permission / default true).
- README: document the permission model (the `sentinel.use` staff gate, the per-type
  `sentinel.<ban|mute|kick|warn|...>` nodes, and which commands are player-facing).
- No new node granularity is introduced; this is gap-closing + documentation only.

## Error handling

- `CooldownManager` never throws; a disabled cooldown (`<= 0`) always allows. Cooldown state is
  in-memory and server-local (acceptable — it is anti-spam, not security).
- `debug(msg)` never throws and does nothing when disabled.
- Permission checks reuse the existing `no-permission` message and `StaffPermissions`; no behaviour
  change for already-correctly-gated commands.

## Testing

- `CooldownManagerTest`: first use allowed; a second use within the window blocked; allowed again
  after expiry (fixed clock); `remainingMillis` correct; `<= 0` cooldown always allows; distinct keys
  are independent.
- Cooldown wiring (MockBukkit): a non-staff player's second `/report` within the window is rejected
  with the `cooldown` message and files nothing; a `sentinel.use` holder is never rate-limited.
- `debug(msg)`: logs only when the flag is on (assert via a captured log record or a boolean seam),
  no-op when off.
- Permission: a non-permitted sender is rejected (`no-permission`) for representative commands found
  to be missing a check during the audit; existing permission tests stay green.
- Full existing suite stays green.

## Risks

- **Cooldown false-positives across reload/restart** — state is in-memory, so a restart clears
  cooldowns. Acceptable for anti-spam. Documented.
- **Debug-log noise / scope creep** — mitigated by the curated, bounded call-site list; no hot-path
  logging.
- **Permission audit surfacing many gaps** — if the audit finds widespread missing checks, each fix
  is small and uses the existing API; reviewer confirms no over-broad or wrong-node checks were added.
