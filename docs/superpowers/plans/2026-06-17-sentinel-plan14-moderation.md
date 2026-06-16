# Sentinel — Plan 14: Moderation (Staff Permission Nodes, Extended Chat Filter, Appeals)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Give staff granular per-action permission nodes, extend the chat filter (caps / character-flood / leetspeak-aware word matching), and add a ban/mute appeal system (`/appeal` for mutes, an appeal URL on the ban screen, and a staff Appeals GUI that auto-lifts the punishment on accept).

**Architecture:** A `StaffPermissions` helper maps each `PunishmentType` to a permission node and gates both the command path and the GUI confirm path (owner bypasses). `ChatModeration` gains caps/flood checks and a normalization pass before word matching. Appeals get a new `appeals` table, `Appeal` model, `AppealDao`, `AppealManager`, an `/appeal` command, and an `AppealsGui` reachable from the Admin Panel.

**Tech Stack:** Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0.

---

## Task 1: Granular staff permission nodes

Per-action nodes (`sentinel.ban`, `sentinel.mute`, …), all `default: op` so existing operators keep full access. A non-op helper granted `sentinel.use` + specific nodes can only do those actions. The owner always bypasses.

**Files:** Create `util/StaffPermissions.java`. Modify `command/PunishmentCommands.java`, `gui/PlayerActionsGui.java`, `src/main/resources/plugin.yml`, `messages.yml`. Test: `util/StaffPermissionsTest.java`.

- [ ] **Step 1: `util/StaffPermissions.java`**

```java
package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.command.CommandSender;

/** Maps each punishment action to a permission node and gates who may perform it. */
public final class StaffPermissions {
    private final Sentinel plugin;
    public StaffPermissions(Sentinel plugin) { this.plugin = plugin; }

    /** The permission node required to issue this punishment type. */
    public static String node(PunishmentType type) {
        return switch (type) {
            case BAN -> "sentinel.ban";
            case IPBAN -> "sentinel.ipban";
            case MUTE -> "sentinel.mute";
            case KICK -> "sentinel.kick";
            case WARN -> "sentinel.warn";
            case SHADOWMUTE -> "sentinel.shadowmute";
        };
    }

    /** True if the sender may issue this punishment. The owner always may. */
    public boolean canPerform(CommandSender sender, PunishmentType type) {
        return plugin.owner().isOwner(sender) || sender.hasPermission(node(type));
    }

    /** True if the sender may use the given action node (e.g. "sentinel.unban"). The owner always may. */
    public boolean canUse(CommandSender sender, String permission) {
        return plugin.owner().isOwner(sender) || sender.hasPermission(permission);
    }
}
```

Confirm the `PunishmentType` enum constants are exactly `BAN, IPBAN, MUTE, KICK, WARN, SHADOWMUTE` (read `model/PunishmentType.java`); if a constant differs, fix the switch (it must be exhaustive — no `default`).

- [ ] **Step 2: Wire accessor into `Sentinel.java`** — add a field `private de.derfakegamer.sentinel.util.StaffPermissions staffPermissions;`, build it in `onEnable()` after `ownerManager` exists: `this.staffPermissions = new de.derfakegamer.sentinel.util.StaffPermissions(this);`, and add `public de.derfakegamer.sentinel.util.StaffPermissions staffPerms() { return staffPermissions; }`.

- [ ] **Step 3: Enforce in `PunishmentCommands.onCommand`**

After resolving the `PunishmentType type` in each punishment case (`ban/ipban/mute`, `tempban/tempmute`, `kick/warn`, `shadowmute`), and BEFORE calling `plugin.moderation().apply(...)`, gate it:
```java
if (!plugin.staffPerms().canPerform(sender, type)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
```
For the `unban`/`unmute` case, gate with the node before acting:
```java
String unNode = cmd.equals("unban") ? "sentinel.unban" : "sentinel.unmute";
if (!plugin.staffPerms().canUse(sender, unNode)) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
```
For `unshadowmute`, gate with `"sentinel.shadowmute"`. Keep the existing `sentinel.use` umbrella check at the top of the method (it still guards GUI/base access). Do NOT remove it.

- [ ] **Step 4: Hide disallowed buttons in `PlayerActionsGui`**

Read `gui/PlayerActionsGui.java`. It places action buttons (ban, mute, kick, warn, …) and routes clicks. For each punishment-type button, only place it if `plugin.staffPerms().canPerform(viewer, type)` — you'll need the viewing player. If the GUI doesn't currently keep the viewer, gate at click time instead: in `onClick`, before performing/opening the confirm for an action, check `canPerform` and if false send `no-permission` and return. Pick whichever fits the existing structure with the least churn; click-time gating is mandatory (hiding is a bonus). Mirror the exact button/case names already in the file — do not invent new actions.

- [ ] **Step 5: plugin.yml permissions**

Add under `permissions:` (after `sentinel.use`):
```yaml
  sentinel.ban:
    description: Ban and tempban players
    default: op
  sentinel.ipban:
    description: IP-ban players
    default: op
  sentinel.mute:
    description: Mute and tempmute players
    default: op
  sentinel.kick:
    description: Kick players
    default: op
  sentinel.warn:
    description: Warn players
    default: op
  sentinel.shadowmute:
    description: Shadow-mute players
    default: op
  sentinel.unban:
    description: Remove bans
    default: op
  sentinel.unmute:
    description: Remove mutes
    default: op
```

- [ ] **Step 6: Test `util/StaffPermissionsTest.java`** (MockBukkit)

```java
package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class StaffPermissionsTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void nodeMappingIsStable() {
        assertEquals("sentinel.ban", StaffPermissions.node(PunishmentType.BAN));
        assertEquals("sentinel.mute", StaffPermissions.node(PunishmentType.MUTE));
    }
    @Test void helperWithOnlyMuteCannotBan() {
        PlayerMock helper = server.addPlayer("Helper"); // not op
        helper.addAttachment(plugin, "sentinel.mute", true);
        assertTrue(plugin.staffPerms().canPerform(helper, PunishmentType.MUTE));
        assertFalse(plugin.staffPerms().canPerform(helper, PunishmentType.BAN));
    }
    @Test void operatorCanDoEverything() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        for (PunishmentType t : PunishmentType.values())
            assertTrue(plugin.staffPerms().canPerform(op, t));
    }
}
```
(If `addAttachment(plugin, perm, true)` isn't how MockBukkit grants perms, use the API that the existing tests use — check another test that grants a permission. `op.setOp(true)` granting all `default: op` nodes is the key assertion.)

- [ ] **Step 7: Build + commit** — `./gradlew build` green, then `git commit -m "feat: granular per-action staff permission nodes"`.

---

## Task 2: Extended chat filter (caps, character-flood, leetspeak normalization)

Add to the existing `ChatModeration`. New checks: caps ratio, repeated-character flood, and a normalization pass so obfuscated bad words (`f.u.c.k`, `fuuuck`, `f0ck`) still match the word filter.

**Files:** Modify `manager/ChatModeration.java`, `src/main/resources/config.yml`, `messages.yml`. Test: extend `manager/ChatModerationTest.java`.

- [ ] **Step 1: Read `manager/ChatModeration.java` fully** to learn the `Outcome` type, the `evaluate(UUID, String, long)` method, the order checks run in, and the `Action` enum (`ALLOW/BLOCK/CENSOR`). New message keys you'll reference: `chat-blocked-caps`, `chat-blocked-flood`.

- [ ] **Step 2: Config defaults** (add to `src/main/resources/config.yml` under the existing `chat:` block — match indentation):
```yaml
  caps-filter:
    enabled: true
    min-length: 8        # ignore short messages
    max-uppercase-ratio: 0.7   # block/censor if more than this fraction of letters are uppercase
    mode: censor         # "block" or "censor" (censor = lowercase the message)
  flood-filter:
    enabled: true
    max-run: 5           # block if the same character repeats more than this many times in a row
```

- [ ] **Step 3: Implement the checks.**

In the constructor, read the new settings into fields:
```java
this.capsFilter = c.getBoolean("chat.caps-filter.enabled", true);
this.capsMinLength = c.getInt("chat.caps-filter.min-length", 8);
this.capsMaxRatio = c.getDouble("chat.caps-filter.max-uppercase-ratio", 0.7);
this.capsCensor = c.getString("chat.caps-filter.mode", "censor").equalsIgnoreCase("censor");
this.floodFilter = c.getBoolean("chat.flood-filter.enabled", true);
this.floodMaxRun = Math.max(2, c.getInt("chat.flood-filter.max-run", 5));
```

Add helper methods:
```java
/** Fraction of letters that are uppercase, 0 if there are no letters. */
static double uppercaseRatio(String s) {
    int letters = 0, upper = 0;
    for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (Character.isLetter(ch)) { letters++; if (Character.isUpperCase(ch)) upper++; }
    }
    return letters == 0 ? 0 : (double) upper / letters;
}

/** Longest run of the same (case-insensitive) character. */
static int longestRun(String s) {
    int best = 0, run = 0; char prev = 0;
    for (int i = 0; i < s.length(); i++) {
        char ch = Character.toLowerCase(s.charAt(i));
        if (ch == prev) run++; else { run = 1; prev = ch; }
        if (run > best) best = run;
    }
    return best;
}

/** Normalizes leetspeak/obfuscation so the word filter still matches: lowercases, maps common
 *  substitutions, and strips non-alphanumerics + collapses repeated letters. */
static String normalize(String s) {
    StringBuilder b = new StringBuilder();
    char last = 0;
    for (int i = 0; i < s.length(); i++) {
        char ch = Character.toLowerCase(s.charAt(i));
        char mapped = switch (ch) {
            case '0' -> 'o'; case '1','!','|' -> 'i'; case '3' -> 'e';
            case '4','@' -> 'a'; case '5','$' -> 's'; case '7' -> 't';
            default -> ch;
        };
        if (!Character.isLetterOrDigit(mapped)) continue; // drop separators like . _ - spaces
        if (mapped == last) continue;                      // collapse repeats: fuuuck -> fuck
        b.append(mapped);
        last = mapped;
    }
    return b.toString();
}
```

In `evaluate(...)`, after the existing anti-spam/slowmode/advert checks and around the word-filter check:
- **Word filter normalization:** when the word filter is enabled, ALSO test `normalize(message)` against each censor word's normalized form. Precompute normalized words once in the constructor (e.g. a `Set<String> normalizedWords`). If `normalize(message)` contains a normalized bad word, treat it as a word-filter hit (same Action as the existing word filter: block → `chat-blocked-word`, censor → censor the original message). Keep the existing literal/regex censor behavior too; the normalized check is an ADDITIONAL catch for obfuscation. (For censor mode, censoring the normalized form back onto the original is hard — for a normalized-only hit in censor mode, fall back to BLOCK with `chat-blocked-word`. Document this in a comment.)
- **Caps filter:** if `capsFilter` and `message.length() >= capsMinLength` and `uppercaseRatio(message) > capsMaxRatio`: if `capsCensor` return `Outcome.censor(message.toLowerCase())` else `Outcome.block("chat-blocked-caps")`.
- **Flood filter:** if `floodFilter` and `longestRun(message) > floodMaxRun`: return `Outcome.block("chat-blocked-flood")`.

Decide a sensible order: run flood + caps before slowmode/spam is fine, but to avoid surprising interactions, put caps/flood AFTER the advert/word checks and BEFORE returning allow. Keep operators bypassing (the caller already bypasses for ops — verify in `ChatListener`). Ensure `evaluate` stays deterministic for testing.

- [ ] **Step 4: Messages** — add to `messages.yml`:
```yaml
chat-blocked-caps: "<red>Please don't shout (too many capital letters)."
chat-blocked-flood: "<red>Please don't spam repeated characters."
```

- [ ] **Step 5: Tests** — extend `manager/ChatModerationTest.java`:
```java
@Test void allCapsIsBlockedOrCensored() {
    ChatModeration cm = fresh();
    ChatModeration.Outcome o = cm.evaluate(java.util.UUID.randomUUID(), "STOP DOING THAT RIGHT NOW", 1000);
    assertNotEquals(ChatModeration.Action.ALLOW, o.action());
}
@Test void shortShoutIsAllowed() {
    assertEquals(ChatModeration.Action.ALLOW, fresh().evaluate(java.util.UUID.randomUUID(), "OK!", 1000).action());
}
@Test void characterFloodIsBlocked() {
    assertEquals(ChatModeration.Action.BLOCK,
        fresh().evaluate(java.util.UUID.randomUUID(), "heyyyyyyyyyy", 1000).action());
}
@Test void obfuscatedBadWordIsCaught() {
    plugin.getConfig().set("chat.word-filter.enabled", true);
    plugin.getConfig().set("chat.word-filter.mode", "block");
    plugin.getConfig().set("chat.word-filter.words", java.util.List.of("badword"));
    ChatModeration cm = fresh();
    assertEquals(ChatModeration.Action.BLOCK,
        cm.evaluate(java.util.UUID.randomUUID(), "b.a.d.w.o.r.d", 1000).action());
}
@Test void normalizeHelperCollapsesObfuscation() {
    assertEquals("badword", ChatModeration.normalize("B.A.D.W.0.R.D"));
    assertEquals("fuck", ChatModeration.normalize("fuuuuck"));
}
```
Note `fresh()` already exists in the test (constructs a `new ChatModeration(plugin)`); the config set in `obfuscatedBadWordIsCaught` must happen BEFORE `fresh()` — adjust if `fresh()` caches. Confirm `uppercaseRatio`/`longestRun`/`normalize` are package-private `static` so the test can call them.

- [ ] **Step 6: Build + commit** — `./gradlew build` green, then `git commit -m "feat: extended chat filter (caps, flood, leetspeak normalization)"`.

---

## Task 3: Ban / mute appeals

`/appeal <text>` lets an online player appeal their active mute. Bans show an appeal URL on the ban screen (banned players can't run commands — Minecraft limitation). Staff review all appeals in an Appeals GUI; accepting auto-lifts the linked punishment.

**Files:** Modify `storage/Database.java`. Create `model/Appeal.java`, `storage/AppealDao.java`, `manager/AppealManager.java`, `command/AppealCommand.java`, `gui/AppealsGui.java`. Modify `Sentinel.java`, `gui/AdminPanelGui.java`, `command/SentinelCommand.java` (optional `/sn appeals`), `manager/ModerationService.java` + `listener/LoginListener.java` (ban-screen appeal line), `plugin.yml`, `config.yml`, `messages.yml`. Tests: `storage/AppealDaoTest.java`, `manager/AppealManagerTest.java`.

- [ ] **Step 1: Schema** — in `storage/Database.java`, alongside the other `CREATE TABLE IF NOT EXISTS` blocks, add:
```java
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS appeals (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      punishment_id INTEGER,
      target_uuid TEXT NOT NULL,
      target_name TEXT NOT NULL,
      type TEXT NOT NULL,
      text TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'OPEN',
      created_at INTEGER NOT NULL,
      handled_by TEXT,
      handled_at INTEGER NOT NULL DEFAULT 0
    )""");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appeal_open ON appeals(status)");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appeal_target ON appeals(target_uuid)");
```

- [ ] **Step 2: `model/Appeal.java`**
```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record Appeal(long id, long punishmentId, UUID targetUuid, String targetName,
                     PunishmentType type, String text, String status,
                     long createdAt, String handledBy, long handledAt) {
    public boolean isOpen() { return "OPEN".equals(status); }
}
```
(`punishmentId` is 0 when not linked. `type` reuses `PunishmentType` — appeals are for BAN or MUTE.)

- [ ] **Step 3: `storage/AppealDao.java`** — mirror `ReportDao` exactly (constructor takes `Database`, every method `synchronized (db)`, `db.connection()`):
  - `long insert(Appeal a)` — INSERT (status 'OPEN', handled_at 0), return generated key.
  - `List<Appeal> findOpen()` — `WHERE status='OPEN' ORDER BY created_at ASC`.
  - `Appeal byId(long id)` — single row or null.
  - `boolean hasOpenForTarget(UUID uuid)` — `SELECT 1 FROM appeals WHERE target_uuid=? AND status='OPEN' LIMIT 1`.
  - `void setStatus(long id, String status, String handledBy, long handledAt)` — UPDATE.
  - private `Appeal map(ResultSet rs)` — read columns; `PunishmentType.valueOf(rs.getString("type"))`.

- [ ] **Step 4: `manager/AppealManager.java`**
```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Appeal;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.storage.AppealDao;

import java.util.List;
import java.util.UUID;

/** Stores and resolves ban/mute appeals. Accepting an appeal lifts the linked punishment. */
public final class AppealManager {
    private final Sentinel plugin;
    private final AppealDao dao;
    public AppealManager(Sentinel plugin, AppealDao dao) { this.plugin = plugin; this.dao = dao; }

    public boolean hasOpen(UUID uuid) { return dao.hasOpenForTarget(uuid); }
    public List<Appeal> open() { return dao.findOpen(); }

    /** Files an appeal. Returns false if the player already has an open appeal. */
    public boolean submit(UUID uuid, String name, long punishmentId, PunishmentType type, String text, long now) {
        if (dao.hasOpenForTarget(uuid)) return false;
        dao.insert(new Appeal(0, punishmentId, uuid, name, type, text, "OPEN", now, null, 0));
        return true;
    }

    /** Accepts an appeal: lifts the linked punishment (unban/unmute) and marks it accepted. */
    public void accept(Appeal a, String staff, long now) {
        if (a.type() == PunishmentType.MUTE) plugin.punishments().unmute(a.targetUuid(), staff, now);
        else plugin.punishments().unban(a.targetUuid(), staff, now);
        dao.setStatus(a.id(), "ACCEPTED", staff, now);
    }

    public void deny(Appeal a, String staff, long now) { dao.setStatus(a.id(), "DENIED", staff, now); }
}
```
Verify `PunishmentManager.unmute(UUID, String, long)` and `unban(UUID, String, long)` signatures match (they do per the existing API). If accepting should also broadcast, reuse `ModerationService.removeBan/removeMute` instead — but those need issuer UUID; keep the direct `unban/unmute` for simplicity and add a staff message in the GUI.

- [ ] **Step 5: Wire into `Sentinel.java`** — field `private de.derfakegamer.sentinel.manager.AppealManager appealManager;`, build after the DB exists: `this.appealManager = new de.derfakegamer.sentinel.manager.AppealManager(this, new de.derfakegamer.sentinel.storage.AppealDao(database));`, accessor `public de.derfakegamer.sentinel.manager.AppealManager appeals() { return appealManager; }`, and register the command `getCommand("appeal").setExecutor(new de.derfakegamer.sentinel.command.AppealCommand(this));`.

- [ ] **Step 6: `command/AppealCommand.java`** — player-only; appeals their active mute.
```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class AppealCommand implements CommandExecutor {
    private final Sentinel plugin;
    public AppealCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.messages().prefixed("players-only")); return true; }
        if (args.length == 0) { sender.sendMessage(plugin.messages().prefixed("appeal-usage")); return true; }
        long now = System.currentTimeMillis();
        Punishment mute = plugin.punishments().activeMute(p.getUniqueId(), now);
        if (mute == null) { p.sendMessage(plugin.messages().prefixed("appeal-nothing")); return true; }
        String text = String.join(" ", args);
        if (!plugin.appeals().submit(p.getUniqueId(), p.getName(), mute.id(), PunishmentType.MUTE, text, now)) {
            p.sendMessage(plugin.messages().prefixed("appeal-exists")); return true;
        }
        p.sendMessage(plugin.messages().prefixed("appeal-submitted"));
        // notify online staff
        plugin.getServer().getOnlinePlayers().stream()
            .filter(s -> s.hasPermission("sentinel.use"))
            .forEach(s -> s.sendMessage(plugin.messages().prefixed("appeal-alert", "player", p.getName())));
        return true;
    }
}
```
Confirm `Punishment` has an `id()` accessor and `activeMute` returns it populated (read `model/Punishment.java`). If `players-only` isn't an existing message key, add it.

- [ ] **Step 7: `gui/AppealsGui.java`** — model it on `gui/ReportsGui.java` (read that file first). A 54-slot paginated list of `plugin.appeals().open()`. Each appeal → a head (`Items.head` of the target `Bukkit.getOfflinePlayer(uuid)`) with lore showing type, the appeal text (split if long), and "Left-click: Accept · Right-click: Deny". Bottom nav PREV/BACK(→AdminPanelGui)/NEXT like ReportsGui. In `onClick`: cancel first; gate with `plugin.staffPerms().canUse(p, "sentinel.use")` (viewing) — accept requires `sentinel.unban`/`sentinel.unmute`, so check `plugin.staffPerms().canUse(p, appeal.type()==MUTE?"sentinel.unmute":"sentinel.unban")` before accepting; on accept call `plugin.appeals().accept(appeal, p.getName(), now)` + message `appeal-accepted`; on right-click deny call `plugin.appeals().deny(...)` + `appeal-denied`; refresh the GUI. Keep the list as a field for index lookup (like OperatorsGui/ReportsGui).

- [ ] **Step 8: Admin Panel button** — in `gui/AdminPanelGui.java` add `APPEALS = 22` to the constants, place `inventory.setItem(APPEALS, button(Material.WRITABLE_BOOK, "Appeals", "Review ban/mute appeals"));`, and in `onClick` add `case APPEALS -> new AppealsGui(plugin, 0).open(p);`.

- [ ] **Step 9: Ban-screen appeal line** — add config `appeals.url: ""` to config.yml. Add to `messages.yml` an `appeal-line` template e.g. `appeal-line: "\n<gray>Appeal at: <white><url>"`. In BOTH `ModerationService` (the `BAN, IPBAN -> online.kick(... "ban-screen" ...)` call) and `LoginListener` (the `ban-screen` render), compute an appeal string: if `getConfig().getString("appeals.url","")` is non-blank, render `messages().plain("appeal-line", "url", url)` to a String via plain-serialized? Simpler: add an `<appeal>` placeholder to the `ban-screen` message and pass `"appeal", appealText` where `appealText` is either "" or `"\nAppeal at: <url>"`. Update the `ban-screen` value in `messages.yml` to end with `<appeal>`. Provide a tiny helper (e.g. a static method on a util or inline) so both render sites build the same `appeal` placeholder string from config. Keep it MiniMessage-safe (the url is plain text).

  Concretely set in `messages.yml`:
  ```yaml
  ban-screen: "<#3B82F6>Sentinel\n<gray>You are banned.\n<white><reason>\n\n<gray>Duration: <white><duration><appeal>"
  ```
  and in both render sites pass an extra placeholder pair `"appeal", appealSuffix` where:
  ```java
  String url = plugin.getConfig().getString("appeals.url", "");
  String appealSuffix = url.isBlank() ? "" : "\n\nAppeal at: " + url;
  ```

- [ ] **Step 10: plugin.yml** — add command:
```yaml
  appeal:
    description: Appeal an active mute.
    usage: /appeal <reason>
```
(No permission line — any player may appeal their own mute.)

- [ ] **Step 11: messages** — add: `appeal-usage`, `appeal-nothing`, `appeal-exists`, `appeal-submitted`, `appeal-alert` (`<player>`), `appeal-accepted`, `appeal-denied`, `gui-appeals-title`, and `players-only` if missing.

- [ ] **Step 12: Tests**
  - `storage/AppealDaoTest.java` (mirror an existing DAO test; use a temp-file `Database`): insert → `findOpen` returns it; `hasOpenForTarget` true; `setStatus(id,"ACCEPTED",...)` → `findOpen` empty, `byId` shows ACCEPTED.
  - `manager/AppealManagerTest.java` (MockBukkit): `submit` once true, twice false (open dedup); `accept` a MUTE appeal lifts the mute — set up an active mute via `plugin.punishments().mute(...)`, submit, accept, assert `plugin.punishments().activeMute(uuid, now)` is null and the appeal is no longer in `open()`.

- [ ] **Step 13: Build + commit** — `./gradlew build` green, then `git commit -m "feat: ban/mute appeals (/appeal, Appeals GUI, ban-screen URL)"`.

---

## Task 4: Full suite + smoke test
- [ ] `./gradlew build` BUILD SUCCESSFUL, all tests green, shaded jar built.
- [ ] Confirm shipped `plugin.yml`/`messages.yml` in the jar still contain NO `orbital`/`owner` traces (`unzip -p build/libs/Sentinel-*.jar plugin.yml | grep -iE 'orbital|owner'` → empty).
- [ ] Manual smoke: a non-op helper with only `sentinel.mute` can mute but not ban (command + GUI); ALL-CAPS / `heyyyyyy` / `b.a.d.w.o.r.d` get filtered; `/appeal text` while muted files an appeal and notifies staff; Admin Panel → Appeals → accept unmutes; a banned player sees the appeal URL on the ban screen.

## Self-Review Notes
- **Permissions:** every new node is `default: op` → existing operators unaffected; owner bypasses via `StaffPermissions`. Enforced in BOTH command and GUI paths.
- **Chat filter:** caps/flood/normalization added without breaking existing spam/advert/word tests; helpers are static + package-private for unit testing.
- **Appeals:** mute appeals fully in-game; ban appeals via URL + staff GUI (Minecraft blocks banned-player commands — documented). Accept auto-lifts. Dedup prevents appeal spam.
- **Types:** `StaffPermissions.node` switch is exhaustive over `PunishmentType`; `Appeal.type` reuses `PunishmentType`.
- **onDisable** already closes the DB and cancels tasks — no new teardown needed.
