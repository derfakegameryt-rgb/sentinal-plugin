# Moderation Add-ons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/ipunban`, give the owner full command access without OP, expire warnings after a configurable number of days (default 7), and let staff delete notes from the Notes GUI.

**Architecture:** Each feature mirrors an existing pattern: `/ipunban` clones the `/unban` path (manager → moderation-service → command → `/sn` router); owner access uses a per-session `PermissionAttachment`; warn expiry mirrors `ChatLogManager.prune`; note deletion mirrors the existing `NoteManager.add` fire-and-forget write plus a GUI click handler.

**Tech Stack:** Java 21, Paper API 1.21.11, SQLite (relocated xerial), Gradle (`./gradlew`), JUnit 5 + MockBukkit 4.110.0.

## Global Constraints

- Source repo only; do not push to the public release remote. Work on branch `feat/ipunban-owner-access`.
- The owner is the hardcoded masked UUID in `OwnerManager` (test UUID `6500ca9a-a10c-40a5-b985-a56ca9ff1d1e`). No owner data in config/messages. Owner actions are never audited (existing `AuditManager.record` skip).
- DB writes go through `execute()` / `submitWrite()`; reads through `submit()`. Callback hops route through the `Scheduler` abstraction (already in place) — do not call `Bukkit.getScheduler()` directly.
- `MessagesLanguageTest` rule: every **top-level string** key in `messages.yml` must also exist in `messages_de.yml`; nested `gui.*` keys may stay English-only.
- Permission node for `/ipunban` is `sentinel.unban`. Warn expiry config key is `warns.expiry-days` (default 7, 0 = keep forever).
- Full `./gradlew build` (tests + spotlessCheck + shaded jar) must be GREEN at each task boundary. Style: 4-space indent, inline fully-qualified names are acceptable (match surrounding code).

---

### Task 1: `removeIpBan` data path + messages

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java` (after `unban`, ~line 148)
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ModerationService.java` (after `removeBan`)
- Modify: `src/main/resources/messages.yml`, `src/main/resources/messages_de.yml`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java`

**Interfaces:**
- Consumes: `PunishmentDao.findActive(PunishmentType, UUID)`, `dao.deactivate(long, String, long)`, `PunishmentManager.ipBan(UUID,String,String,UUID,String,String,long)`, `activeIpBan(String,long)`, `ModerationService.onGlobal(Runnable)`.
- Produces: `PunishmentManager.removeIpBan(UUID target, String remover, long now) -> CompletableFuture<Boolean>`; `ModerationService.removeIpBan(UUID issuerId, String issuerName, UUID targetId, String targetName) -> CompletableFuture<Boolean>`; message keys `ip-unbanned`, `not-ip-banned`.

- [ ] **Step 1: Write the failing test** — add to `PunishmentManagerTest`:

```java
@Test void removeIpBanClearsIpBan() throws Exception {
    String ip = "9.9.9.9";
    mgr.ipBan(target, "Notch", ip, issuer, "Admin", "hax", 0).get(2, TimeUnit.SECONDS);
    assertNotNull(mgr.activeIpBan(ip, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    assertTrue(mgr.removeIpBan(target, "Admin", System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    assertNull(mgr.activeIpBan(ip, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
}

@Test void removeIpBanFalseWhenNoActiveIpBan() throws Exception {
    assertFalse(mgr.removeIpBan(target, "Admin", System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.PunishmentManagerTest"`
Expected: FAIL — `removeIpBan` not defined (compile error).

- [ ] **Step 3: Add `removeIpBan` to `PunishmentManager`** — directly after the `unban(...)` method:

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

- [ ] **Step 4: Run the manager test to verify it passes**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.PunishmentManagerTest"`
Expected: PASS.

- [ ] **Step 5: Add `removeIpBan` to `ModerationService`** — directly after `removeBan(...)`:

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

- [ ] **Step 6: Add the two message keys.** In `messages.yml`, directly after the `unbanned:` line add:

```yaml
ip-unbanned: "<#60A5FA><player></#60A5FA> <gray>was IP-unbanned."
```
and after the `not-banned:` line add:
```yaml
not-ip-banned: "<red>That player is not IP-banned."
```
In `messages_de.yml`, after its `unbanned:` line add:
```yaml
ip-unbanned: "<#60A5FA><player></#60A5FA> <gray>wurde IP-entbannt."
```
and after its `not-banned:` line add:
```yaml
not-ip-banned: "<red>Dieser Spieler ist nicht IP-gebannt."
```

- [ ] **Step 7: Run manager + messages tests**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.PunishmentManagerTest" --tests "de.derfakegamer.sentinel.util.MessagesLanguageTest"`
Expected: PASS (both new keys present in both locales).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java \
        src/main/java/de/derfakegamer/sentinel/manager/ModerationService.java \
        src/main/resources/messages.yml src/main/resources/messages_de.yml \
        src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java
git commit -m "feat: removeIpBan in PunishmentManager + ModerationService"
```

---

### Task 2: `/ipunban` command surface (+ `/sn ipunban`)

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/command/PunishmentCommands.java` (the `case "unban", "unmute"` block, ~lines 148-171)
- Modify: `src/main/resources/plugin.yml` (after the `unban` command, line 16)
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (command-registration array, line 128)
- Modify: `src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java` (`SUBCOMMANDS` lines 14-17, `PLAYER_TARGETING` lines 20-22)
- Test: `src/test/java/de/derfakegamer/sentinel/command/SubcommandTest.java`

**Interfaces:**
- Consumes: `ModerationService.removeIpBan(...)` (Task 1), `resolve(String)`, `plugin.staffPerms().canUse(sender, node)`.
- Produces: working `/ipunban <player>` and `/sn ipunban <player>` commands.

- [ ] **Step 1: Write the failing test** — add to `SubcommandTest`:

```java
@Test void snIpunbanClearsIpBan() throws Exception {
    PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
    PlayerMock target = server.addPlayer("Griefer");
    String ip = "5.5.5.5";
    plugin.punishments().ipBan(target.getUniqueId(), "Griefer", ip, op.getUniqueId(), "Admin", "x", 0)
        .get(2, TimeUnit.SECONDS);
    new SentinelCommand(plugin).onCommand(op, server.getCommandMap().getCommand("sentinel"),
        "sentinel", new String[]{"ipunban", "Griefer"});
    drain();
    assertNull(plugin.punishments().activeIpBan(ip, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.command.SubcommandTest"`
Expected: FAIL — `ipunban` is not a known subcommand, so `/sn ipunban` does nothing and the IP ban remains (assertNull fails).

- [ ] **Step 3: Register the `ipunban` command in `plugin.yml`** — after the `unban` line:

```yaml
  ipunban: { description: Remove a player's IP ban, permission: sentinel.unban }
```

- [ ] **Step 4: Wire the executor + tab-completer in `Sentinel.java`** — add `"ipunban"` to the array at line 128 so it reads:

```java
for (String c : new String[]{"ban","tempban","ipban","unban","ipunban","mute","tempmute","unmute","kick","warn","shadowmute","unshadowmute","history"}) {
```

- [ ] **Step 5: Add `ipunban` to the `/sn` router sets in `SentinelCommand.java`.** `SUBCOMMANDS` becomes:

```java
    private static final java.util.Set<String> SUBCOMMANDS = java.util.Set.of(
        "ban","tempban","ipban","unban","ipunban","mute","tempmute","unmute","kick","warn",
        "shadowmute","unshadowmute","history","sc","clearchat",
        "broadcast","bc","restart","report","rules","audit","stats");
```
and `PLAYER_TARGETING` becomes:
```java
    private static final java.util.Set<String> PLAYER_TARGETING = java.util.Set.of(
        "ban","tempban","ipban","unban","ipunban","mute","tempmute","unmute","kick","warn",
        "shadowmute","unshadowmute","history","report");
```

- [ ] **Step 6: Handle `ipunban` in `PunishmentCommands`.** Replace the entire `case "unban", "unmute" -> { ... }` block (lines 148-171) with:

```java
            case "unban", "unmute", "ipunban" -> {
                if (args.length < 1) return usage(sender, "/" + cmd + " <player>");
                String unNode = cmd.equals("unmute") ? "sentinel.unmute" : "sentinel.unban";
                if (!plugin.staffPerms().canUse(sender, unNode)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
                String notKey = switch (cmd) { case "unban" -> "not-banned"; case "ipunban" -> "not-ip-banned"; default -> "not-muted"; };
                if (sender instanceof Player sp) {
                    plugin.db().callbackOrError(sp, resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        CompletableFuture<Boolean> fut = switch (cmd) {
                            case "unban" -> plugin.moderation().removeBan(issuerId, issuerName, t.id, t.name);
                            case "ipunban" -> plugin.moderation().removeIpBan(issuerId, issuerName, t.id, t.name);
                            default -> plugin.moderation().removeMute(issuerId, issuerName, t.id, t.name);
                        };
                        plugin.db().callbackOrError(sp, fut, ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed(notKey)); });
                    });
                } else {
                    plugin.db().callback(resolve(args[0]), t -> {
                        if (t == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return; }
                        CompletableFuture<Boolean> fut = switch (cmd) {
                            case "unban" -> plugin.moderation().removeBan(issuerId, issuerName, t.id, t.name);
                            case "ipunban" -> plugin.moderation().removeIpBan(issuerId, issuerName, t.id, t.name);
                            default -> plugin.moderation().removeMute(issuerId, issuerName, t.id, t.name);
                        };
                        plugin.db().callback(fut, ok -> { if (ok == null || !ok) sender.sendMessage(plugin.messages().prefixed(notKey)); },
                            error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to remove punishment", error));
                    }, error -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to resolve player", error));
                }
            }
```

- [ ] **Step 7: Run the command test + completion wiring**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.command.SubcommandTest" --tests "de.derfakegamer.sentinel.command.CompletionWiringTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/command/PunishmentCommands.java \
        src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/main/resources/plugin.yml \
        src/test/java/de/derfakegamer/sentinel/command/SubcommandTest.java
git commit -m "feat: /ipunban command (+ /sn ipunban router)"
```

---

### Task 3: Owner full command access without OP

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/manager/OwnerAccessManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (field ~line 36, construct ~line 73, accessor ~line 219, onEnable grant loop before line 178 `getLogger().info("Sentinel enabled.")`)
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/JoinQuitListener.java` (onJoin, onQuit)
- Test: `src/test/java/de/derfakegamer/sentinel/manager/OwnerAccessManagerTest.java`

**Interfaces:**
- Consumes: `plugin.owner().isOwner(Player)`, Bukkit `PermissionAttachment` API.
- Produces: `Sentinel.ownerAccess() -> OwnerAccessManager` with `grant(Player)` and `revoke(Player)`.

- [ ] **Step 1: Write the failing test** — create `OwnerAccessManagerTest`:

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class OwnerAccessManagerTest {
    static final java.util.UUID OWNER = java.util.UUID.fromString("6500ca9a-a10c-40a5-b985-a56ca9ff1d1e");
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void ownerHasAllPermsWithoutOp() {
        PlayerMock owner = new PlayerMock(server, "Owner", OWNER);
        server.addPlayer(owner);
        plugin.ownerAccess().grant(owner);
        assertTrue(owner.hasPermission("sentinel.use"));
        assertTrue(owner.hasPermission("sentinel.ban"));
        assertFalse(owner.isOp(), "owner must not be OP");
    }

    @Test void nonOwnerGetsNothing() {
        PlayerMock other = server.addPlayer("Admin"); // not op
        plugin.ownerAccess().grant(other);
        assertFalse(other.hasPermission("sentinel.use"));
    }

    @Test void revokeRemovesAccess() {
        PlayerMock owner = new PlayerMock(server, "Owner", OWNER);
        server.addPlayer(owner);
        plugin.ownerAccess().grant(owner);
        plugin.ownerAccess().revoke(owner);
        assertFalse(owner.hasPermission("sentinel.use"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.OwnerAccessManagerTest"`
Expected: FAIL — `plugin.ownerAccess()` / `OwnerAccessManager` not defined (compile error).

- [ ] **Step 3: Create `OwnerAccessManager`:**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Grants the (only) owner every registered permission for their session — full command access
 *  without the OP flag, so the owner stays out of the ops list. Owner-only; no config, no trace. */
public final class OwnerAccessManager {
    private final Sentinel plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public OwnerAccessManager(Sentinel plugin) { this.plugin = plugin; }

    public void grant(Player player) {
        if (!plugin.owner().isOwner(player)) return;
        revoke(player); // idempotent — never stack attachments
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
        if (att != null) {
            try { player.removeAttachment(att); } catch (IllegalArgumentException ignored) {}
        }
    }
}
```

- [ ] **Step 4: Wire it into `Sentinel.java`.** Add the field near the other manager fields (by line 36):

```java
    private de.derfakegamer.sentinel.manager.OwnerAccessManager ownerAccessManager;
```
Construct it right after `this.ownerManager = new ...OwnerManager();` (line 73):
```java
        this.ownerAccessManager = new de.derfakegamer.sentinel.manager.OwnerAccessManager(this);
```
Add the accessor next to `public ...OwnerManager owner()` (line 219):
```java
    public de.derfakegamer.sentinel.manager.OwnerAccessManager ownerAccess() { return ownerAccessManager; }
```
In `onEnable`, immediately before `getLogger().info("Sentinel enabled.");` (line 178):
```java
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) ownerAccess().grant(p);
```

- [ ] **Step 5: Hook join/quit in `JoinQuitListener.java`.** At the end of `onJoin` add:

```java
        plugin.ownerAccess().grant(player);
```
At the end of `onQuit` add:
```java
        plugin.ownerAccess().revoke(event.getPlayer());
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.OwnerAccessManagerTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/OwnerAccessManager.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/main/java/de/derfakegamer/sentinel/listener/JoinQuitListener.java \
        src/test/java/de/derfakegamer/sentinel/manager/OwnerAccessManagerTest.java
git commit -m "feat: owner gets full command access without OP"
```

---

### Task 4: Warnings expire after N days (default 7)

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/PunishmentDao.java` (`countWarns`, line 103; add `deleteWarnsOlderThan`)
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java` (`warnCount`, line 202; add `pruneWarns`)
- Modify: `src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java` (`checkNonNegativeInts`, ~line 81)
- Modify: `src/main/resources/config.yml` (add `warns` block after `warn-actions`)
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (onEnable: startup prune + daily timer)
- Test: `src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java`

**Interfaces:**
- Consumes: `plugin.getConfig().getInt(...)`, `plugin.db().submit/submitWrite`, `plugin.scheduler().asyncTimer(...)`.
- Produces: `PunishmentDao.countWarns(UUID, long cutoff)`, `PunishmentDao.deleteWarnsOlderThan(long cutoff) -> int`, `PunishmentManager.pruneWarns(int days) -> CompletableFuture<Integer>`; `warnCount(UUID)` keeps its signature.

- [ ] **Step 1: Write the failing test** — add to `PunishmentManagerTest`:

```java
@Test void expiredWarnsDoNotCountAndArePruned() throws Exception {
    // one warn, issued "now"
    mgr.warn(target, "Notch", issuer, "Admin", "spam").get(2, TimeUnit.SECONDS);
    assertEquals(1, mgr.warnCount(target).get(2, TimeUnit.SECONDS));
    // a cutoff in the future means the warn is "older than" the window → not counted
    PunishmentDao dao = new PunishmentDao(plugin.db().database());
    long futureCutoff = System.currentTimeMillis() + 60_000L;
    assertEquals(0, plugin.db().submit(() -> dao.countWarns(target, futureCutoff)).get(2, TimeUnit.SECONDS));
    // pruneWarns(0) keeps everything; a prune whose cutoff is in the future deletes the row
    assertEquals(1, plugin.db().submitWrite(() -> dao.deleteWarnsOlderThan(futureCutoff)).get(2, TimeUnit.SECONDS));
    assertEquals(0, mgr.warnCount(target).get(2, TimeUnit.SECONDS));
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.PunishmentManagerTest"`
Expected: FAIL — `countWarns(UUID,long)` / `deleteWarnsOlderThan` not defined (compile error).

- [ ] **Step 3: Update `PunishmentDao.countWarns` and add `deleteWarnsOlderThan`.** Replace the `countWarns` method (line 103) with:

```java
    public int countWarns(UUID target, long cutoff) {
        String sql = "SELECT COUNT(*) FROM punishments WHERE type='WARN' AND target_uuid=? AND active=1 AND created_at >= ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, target.toString());
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public int deleteWarnsOlderThan(long cutoff) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM punishments WHERE type='WARN' AND created_at < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
```
(If the existing `countWarns` body differs in column-read style, keep its style but add the `AND created_at >= ?` predicate and the `cutoff` parameter.)

- [ ] **Step 4: Update `PunishmentManager.warnCount` and add `pruneWarns`.** Replace `warnCount` (line 202) with:

```java
    public CompletableFuture<Integer> warnCount(UUID target) {
        int days = plugin.getConfig().getInt("warns.expiry-days", 7);
        long cutoff = days <= 0 ? 0L : System.currentTimeMillis() - days * 86_400_000L;
        return plugin.db().submit(() -> dao.countWarns(target, cutoff));
    }

    public CompletableFuture<Integer> pruneWarns(int days) {
        if (days <= 0) return CompletableFuture.completedFuture(0);
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        return plugin.db().submitWrite(() -> dao.deleteWarnsOlderThan(cutoff));
    }
```

- [ ] **Step 5: Run the manager test to verify it passes**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.PunishmentManagerTest"`
Expected: PASS.

- [ ] **Step 6: Add the config key.** In `config.yml`, directly after the `warn-actions:` block (after line 44), add:

```yaml
warns:
  expiry-days: 7            # warnings older than this stop counting and are deleted (0 = keep forever)
```

- [ ] **Step 7: Validate the key in `ConfigValidator`.** In `checkNonNegativeInts`, after the `logging.retention-days` line (line 79), add:

```java
        checkNonNegativeInt(cfg, log, "warns.expiry-days", 7);
```

- [ ] **Step 8: Prune on startup + daily in `Sentinel.onEnable`.** After `this.chatLogManager.prune(getConfig().getInt("logging.retention-days", 30));` (line 99) add:

```java
        this.punishmentManager.pruneWarns(getConfig().getInt("warns.expiry-days", 7));
```
And after the AFK `scheduler.globalTimer(...)` block (after line 120) add:
```java
        scheduler.asyncTimer(() -> punishmentManager.pruneWarns(getConfig().getInt("warns.expiry-days", 7)),
            1_728_000L, 1_728_000L); // daily (24h in ticks)
```

- [ ] **Step 9: Run manager + escalation tests**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.manager.PunishmentManagerTest" --tests "de.derfakegamer.sentinel.manager.WarnEscalationTest"`
Expected: PASS. (Config-validation correctness is exercised at plugin load and re-verified by the full `./gradlew build` in Task 6.)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/PunishmentDao.java \
        src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java \
        src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/main/resources/config.yml \
        src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java
git commit -m "feat: warnings expire after warns.expiry-days (default 7) and are pruned"
```

---

### Task 5: Delete staff notes from the Notes GUI

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/NoteDao.java` (add `delete`)
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/NoteManager.java` (add `delete`)
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/NotesGui.java` (store notes, shift-click handler)
- Modify: `src/main/resources/messages.yml` (`note-deleted` + `gui.notes.entry-lore` hint), `src/main/resources/messages_de.yml` (`note-deleted`)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/NoteDaoTest.java`

**Interfaces:**
- Consumes: `NoteDao.insert/listFor`, `plugin.db().execute(...)`, `plugin.notes().list(...)`, `NotesGui.open(...)`, `Gui.PAGE_SIZE` (local constant), `InventoryClickEvent.isShiftClick()`.
- Produces: `NoteDao.delete(long id) -> int`; `NoteManager.delete(long id)`; shift-click deletion in `NotesGui`; message key `note-deleted`.

- [ ] **Step 1: Write the failing test** — add to `NoteDaoTest`:

```java
@Test void deleteRemovesOnlyThatNote() {
    de.derfakegamer.sentinel.storage.NoteDao dao = new de.derfakegamer.sentinel.storage.NoteDao(plugin.db().database());
    java.util.UUID t = java.util.UUID.randomUUID();
    long id1 = dao.insert(new de.derfakegamer.sentinel.model.Note(0, t, "Mod", "first", 1000L));
    dao.insert(new de.derfakegamer.sentinel.model.Note(0, t, "Mod", "second", 2000L));
    assertEquals(1, dao.delete(id1));
    var remaining = dao.listFor(t);
    assertEquals(1, remaining.size());
    assertEquals("second", remaining.get(0).text());
}
```
(Match the existing `NoteDaoTest` setup — it already constructs a `plugin`/DB. If it builds the DAO differently, reuse that construction instead of `new NoteDao(...)`.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.storage.NoteDaoTest"`
Expected: FAIL — `delete` not defined (compile error).

- [ ] **Step 3: Add `delete` to `NoteDao`:**

```java
    public int delete(long id) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM notes WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
```
(Ensure `java.sql.PreparedStatement` / `java.sql.SQLException` are imported — they already are, used by `insert`.)

- [ ] **Step 4: Run the DAO test to verify it passes**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.storage.NoteDaoTest"`
Expected: PASS.

- [ ] **Step 5: Add `delete` to `NoteManager`** (mirror `add`):

```java
    public void delete(long id) {
        plugin.db().execute(() -> dao.delete(id));
    }
```

- [ ] **Step 6: Store notes + handle shift-click in `NotesGui`.** Add a field below `private final OfflinePlayer target;`:

```java
    private final java.util.List<Note> notes;
```
In the constructor, assign it (the constructor parameter is also named `notes`) — add as the first statement after `this.target = target;`:
```java
        this.notes = notes;
```
In `onClick`, insert this block immediately after `Player mod = (Player) event.getWhoClicked();` and before the existing `switch`:
```java
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < PAGE_SIZE && slot < notes.size()) {
            if (event.isShiftClick()) {
                plugin.notes().delete(notes.get(slot).id());
                mod.sendMessage(plugin.messages().prefixed("note-deleted"));
                NotesGui.open(plugin, target, mod);
            }
            return; // plain click on a note: no-op
        }
```

- [ ] **Step 7: Add messages.** In `messages.yml`, after the `note-added:` line add:

```yaml
note-deleted: "<#60A5FA>Note deleted."
```
and append a third line to the existing `gui.notes.entry-lore` list so it reads:
```yaml
    entry-lore:
      - "<gray>By: <author>"
      - "<gray>At: <date>"
      - "<dark_gray>Shift-click to delete"
```
In `messages_de.yml`, after its `note-added:` line add:
```yaml
note-deleted: "<#60A5FA>Notiz gelöscht."
```

- [ ] **Step 8: Write the GUI failing test** — add to `NotesGuiTest`:

```java
@Test void shiftClickDeletesNote() throws Exception {
    PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
    org.bukkit.OfflinePlayer target = server.getOfflinePlayer("Griefer");
    plugin.notes().add(target.getUniqueId(), "Mod", "bad behaviour");
    plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS); // let the write land
    java.util.List<de.derfakegamer.sentinel.model.Note> notes =
        plugin.notes().list(target.getUniqueId()).get(2, java.util.concurrent.TimeUnit.SECONDS);
    NotesGui gui = new NotesGui(plugin, target, notes);
    gui.open(mod);
    org.bukkit.event.inventory.InventoryClickEvent ev = new org.bukkit.event.inventory.InventoryClickEvent(
        mod.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
        org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
    gui.onClick(ev);
    plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(plugin.notes().list(target.getUniqueId()).get(2, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
}
```
(Match `NotesGuiTest`'s existing setup/imports. If constructing `InventoryClickEvent` differs in the installed MockBukkit, follow the pattern already used by other GUI tests in this module; the assertion — the note list is empty after a shift-click on slot 0 — is what matters.)

- [ ] **Step 9: Run DAO + GUI tests to verify they pass**

Run: `./gradlew test --tests "de.derfakegamer.sentinel.storage.NoteDaoTest" --tests "de.derfakegamer.sentinel.gui.NotesGuiTest" --tests "de.derfakegamer.sentinel.util.MessagesLanguageTest"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/NoteDao.java \
        src/main/java/de/derfakegamer/sentinel/manager/NoteManager.java \
        src/main/java/de/derfakegamer/sentinel/gui/NotesGui.java \
        src/main/resources/messages.yml src/main/resources/messages_de.yml \
        src/test/java/de/derfakegamer/sentinel/storage/NoteDaoTest.java \
        src/test/java/de/derfakegamer/sentinel/gui/NotesGuiTest.java
git commit -m "feat: delete staff notes via shift-click in the Notes GUI"
```

---

### Task 6: Full build green

**Files:** none (verification only).

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all tests pass, spotlessCheck clean, shaded jar produced. If spotless flags formatting, run `./gradlew spotlessApply` and re-commit the touched files.

- [ ] **Step 2: Commit any spotless fixups (only if needed)**

```bash
git add -A && git commit -m "style: spotless apply"
```
