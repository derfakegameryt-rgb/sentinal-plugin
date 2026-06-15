# Sentinel — Plan 9: Shadow-Mute, Chat Logging & Templates

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three moderation additions: a covert **shadow-mute** (the player sees their own messages, nobody else does), **chat & command logging** with a per-player viewer GUI and an optional Discord webhook for punishments/reports, and **punishment templates** plus a **ban-all-alts** mass action.

**Architecture:** `SHADOWMUTE` becomes a punishment type; `ChatListener` restricts a shadow-muted player's chat audience to themselves. A `chatlog` table + `ChatLogManager` records chat and commands, surfaced by `ChatLogGui`. A `DiscordWebhook` util posts to a config URL when set. Templates come from config into a `TemplatesGui`; ban-all-alts reuses `PlayerDirectory.alts`.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, `java.net.http`, JUnit 5 + MockBukkit 4.110.0). All new commands are OP-gated via the existing `sentinel.use` permission.

---

## Task 1: Shadow-mute core

**Files:**
- Modify: `model/PunishmentType.java`, `manager/PunishmentManager.java`, `manager/ModerationService.java`, `listener/ChatListener.java`, `gui/HistoryGui.java`, `messages.yml`
- Test: `manager/ShadowMuteTest.java`

- [ ] **Step 1: Add `SHADOWMUTE` to `model/PunishmentType.java`**

```java
public enum PunishmentType { BAN, MUTE, WARN, KICK, IPBAN, SHADOWMUTE }
```

- [ ] **Step 2: Update exhaustive switches that don't compile anymore**

`gui/HistoryGui.java` `iconFor`: add `case SHADOWMUTE -> Material.BOOK;`. Any other `switch (...PunishmentType...)` that the compiler now flags must get a `SHADOWMUTE` branch (see Step 4 for ModerationService).

- [ ] **Step 3: Add shadow-mute methods to `manager/PunishmentManager.java`**

```java
public Result shadowMute(java.util.UUID target, String targetName, java.util.UUID issuer,
                         String issuerName, String reason, long expiresAt) {
    return record(PunishmentType.SHADOWMUTE, target, targetName, null, issuer, issuerName, reason, expiresAt);
}

public Punishment activeShadowMute(java.util.UUID target, long now) {
    return activeOrExpire(PunishmentType.SHADOWMUTE, target, now);
}

public boolean unShadowMute(java.util.UUID target, String remover, long now) {
    Punishment p = dao.findActive(PunishmentType.SHADOWMUTE, target);
    if (p == null) return false;
    dao.deactivate(p.id(), remover, now);
    return true;
}
```

(`record` and `activeOrExpire` already exist and are private — these new methods live in the same class.)

- [ ] **Step 4: Make `ModerationService` apply shadow-mute covertly**

In `apply(...)`, add `SHADOWMUTE` to the first switch and special-case it so it does NOT broadcast publicly — only OPs are told:

```java
PunishmentManager.Result result = switch (type) {
    case BAN   -> pm.ban(targetId, targetName, issuerId, issuerName, reason, expiresAt);
    case IPBAN -> pm.ipBan(targetId, targetName, ip, issuerId, issuerName, reason, expiresAt);
    case MUTE  -> pm.mute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
    case WARN  -> pm.warn(targetId, targetName, issuerId, issuerName, reason);
    case KICK  -> pm.kick(targetId, targetName, issuerId, issuerName, reason);
    case SHADOWMUTE -> pm.shadowMute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
};
if (!result.isSuccess()) return false;

if (type == PunishmentType.SHADOWMUTE) {
    notifyStaff(plugin.messages().plain("shadowmuted", "player", targetName, "reason", reason));
    return true; // covert: no public broadcast, no kick
}
```

Add a covert staff notifier and a public un-shadowmute remover:

```java
private void notifyStaff(net.kyori.adventure.text.Component message) {
    for (org.bukkit.entity.Player op : org.bukkit.Bukkit.getOnlinePlayers())
        if (op.isOp()) op.sendMessage(message);
}

public boolean removeShadowMute(java.util.UUID issuerId, String issuerName, java.util.UUID targetId, String targetName) {
    boolean ok = plugin.punishments().unShadowMute(targetId, issuerName, System.currentTimeMillis());
    if (ok) notifyStaff(plugin.messages().plain("unshadowmuted", "player", targetName));
    return ok;
}
```

The existing `key` switch (BAN/IPBAN/MUTE/WARN/KICK) must stay exhaustive — add `case SHADOWMUTE -> "muted";` (unreachable, the early return above prevents it) so it compiles.

- [ ] **Step 5: Enforce shadow-mute in `listener/ChatListener.java`**

Insert AFTER the staff-chat block and BEFORE the normal mute block:

```java
de.derfakegamer.sentinel.model.Punishment shadow =
    plugin.punishments().activeShadowMute(id, System.currentTimeMillis());
if (shadow != null) {
    // Restrict the audience to the sender only: they see their own message normally,
    // everyone else sees nothing. Do NOT cancel — the message must still render to them.
    event.viewers().removeIf(a -> !(a instanceof org.bukkit.entity.Player p) || !p.getUniqueId().equals(id));
    return;
}
```

- [ ] **Step 6: Add message keys to `messages.yml`**

```yaml
shadowmuted: "<#3B82F6>Staff <dark_gray>»</dark_gray> <#60A5FA><player></#60A5FA> <gray>was shadow-muted. Reason: <white><reason>"
unshadowmuted: "<#3B82F6>Staff <dark_gray>»</dark_gray> <#60A5FA><player></#60A5FA> <gray>was un-shadow-muted."
```

- [ ] **Step 7: Write the failing test `ShadowMuteTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShadowMuteTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void applyShadowMuteRecordsActiveShadowMute() {
        UUID t = UUID.randomUUID();
        assertTrue(plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null,
            PunishmentType.SHADOWMUTE, 0, "test"));
        assertNotNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()));
        // a shadow-mute must NOT register as a normal mute
        assertNull(plugin.punishments().activeMute(t, System.currentTimeMillis()));
    }

    @Test void removeShadowMuteClearsIt() {
        UUID t = UUID.randomUUID();
        plugin.moderation().apply(new UUID(0,0), "Admin", t, "Sneaky", null, PunishmentType.SHADOWMUTE, 0, "x");
        assertTrue(plugin.moderation().removeShadowMute(new UUID(0,0), "Admin", t, "Sneaky"));
        assertNull(plugin.punishments().activeShadowMute(t, System.currentTimeMillis()));
    }
}
```

- [ ] **Step 8: Run tests** — `./gradlew test --tests ShadowMuteTest --tests ModerationServiceTest --tests ChatListenerTest`

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: shadow-mute punishment type"
```

---

## Task 2: Shadow-mute command + GUI button

**Files:**
- Modify: `plugin.yml`, `command/PunishmentCommands.java`, `gui/PlayerActionsGui.java`
- Test: extend `PlayerActionsGuiToolsTest.java`

- [ ] **Step 1: Declare commands in `plugin.yml`**

```yaml
  shadowmute: { description: Shadow-mute a player, permission: sentinel.use }
  unshadowmute: { description: Remove a shadow-mute, permission: sentinel.use }
```

- [ ] **Step 2: Handle them in `command/PunishmentCommands.java`**

Register `shadowmute`/`unshadowmute` to the `PunishmentCommands` executor in `Sentinel.java` (add to the existing command array). In the `onCommand` switch, add:

```java
case "shadowmute" -> {
    if (args.length < 2) return usage(sender, "/shadowmute <player> <reason>");
    Target t = resolve(sender, args[0]); if (t == null) return true;
    boolean ok = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip,
        PunishmentType.SHADOWMUTE, 0, join(args, 1));
    if (!ok) sender.sendMessage(plugin.messages().prefixed("exempt"));
}
case "unshadowmute" -> {
    if (args.length < 1) return usage(sender, "/unshadowmute <player>");
    Target t = resolve(sender, args[0]); if (t == null) return true;
    if (!plugin.moderation().removeShadowMute(issuerId, issuerName, t.id, t.name))
        sender.sendMessage(plugin.messages().prefixed("not-muted"));
}
```

In `Sentinel.java`, add `"shadowmute","unshadowmute"` to the `for (String c : new String[]{...})` array that wires the `PunishmentCommands` executor AND the tab-completer.

- [ ] **Step 3: Add a Shadow-Mute button to `gui/PlayerActionsGui.java`**

Add `SHADOWMUTE_SLOT = 16` to the slot constants. In the constructor (after WARN), render a context-sensitive button:

```java
boolean shadowMuted = plugin.punishments().activeShadowMute(target.getUniqueId(), now) != null;
inventory.setItem(SHADOWMUTE_SLOT, Items.button(Material.INK_SAC,
    net.kyori.adventure.text.Component.text(shadowMuted ? "Un-shadowmute" : "Shadow-mute",
        shadowMuted ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                    : net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE),
    List.of(net.kyori.adventure.text.Component.text("Covert mute — only they see their chat",
        net.kyori.adventure.text.format.NamedTextColor.GRAY)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
```

Store `shadowMuted` as a field (like `banned`/`muted`). Add the click case:

```java
case SHADOWMUTE_SLOT -> {
    if (shadowMuted) { plugin.moderation().removeShadowMute(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()); mod.closeInventory(); }
    else new ReasonGui(plugin, target, null, PunishmentType.SHADOWMUTE, 0).open(mod);
}
```

- [ ] **Step 4: Extend `PlayerActionsGuiToolsTest.java`**

```java
    @Test void shadowMuteButtonIsShown() {
        org.mockbukkit.mockbukkit.entity.PlayerMock mod = server.addPlayer("Mod");
        org.mockbukkit.mockbukkit.entity.PlayerMock target = server.addPlayer("Sneaky");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        assertNotNull(gui.getInventory().getItem(16));
        assertEquals(org.bukkit.Material.INK_SAC, gui.getInventory().getItem(16).getType());
    }
```

- [ ] **Step 5: Run tests + commit**

```bash
./gradlew test --tests PlayerActionsGuiToolsTest --tests PunishmentCommandsTest
git add -A && git commit -m "feat: shadow-mute command and GUI button"
```

---

## Task 3: Chat & command logging + viewer GUI

**Files:**
- Modify: `storage/Database.java`
- Create: `storage/ChatLogDao.java`, `manager/ChatLogManager.java`, `model/ChatLogEntry.java`, `listener/CommandLogListener.java`, `gui/ChatLogGui.java`
- Modify: `listener/ChatListener.java` (log chat), `gui/PlayerActionsGui.java` (Logs button), `Sentinel.java`, `messages.yml`
- Test: `storage/ChatLogDaoTest.java`, `gui/ChatLogGuiTest.java`

- [ ] **Step 1: Add the `chatlog` table to `Database.createSchema()`**

```java
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS chatlog (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      uuid TEXT NOT NULL,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,      -- CHAT or COMMAND
      text TEXT NOT NULL,
      created_at INTEGER NOT NULL
    )""");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chatlog_uuid ON chatlog(uuid)");
```

- [ ] **Step 2: Write `model/ChatLogEntry.java`**

```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record ChatLogEntry(long id, UUID uuid, String name, String kind, String text, long createdAt) {}
```

- [ ] **Step 3: Write the failing test `ChatLogDaoTest.java`**

```java
package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatLogDaoTest {
    Database db; ChatLogDao dao; File tmp;
    UUID who = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new ChatLogDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void logsAndReadsRecentNewestFirst() {
        dao.log(who, "Bob", "CHAT", "hello", 100);
        dao.log(who, "Bob", "COMMAND", "/help", 200);
        var recent = dao.recent(who, 10);
        assertEquals(2, recent.size());
        assertEquals("/help", recent.get(0).text()); // newest first
    }

    @Test void recentIsLimited() {
        for (int i = 0; i < 20; i++) dao.log(who, "Bob", "CHAT", "m" + i, i);
        assertEquals(5, dao.recent(who, 5).size());
    }
}
```

- [ ] **Step 4: Write `storage/ChatLogDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.ChatLogEntry;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ChatLogDao {
    private final Database db;

    public ChatLogDao(Database db) { this.db = db; }

    public void log(UUID uuid, String name, String kind, String text, long now) {
        synchronized (db) {
            String sql = "INSERT INTO chatlog (uuid,name,kind,text,created_at) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, kind);
                ps.setString(4, text);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<ChatLogEntry> recent(UUID uuid, int limit) {
        synchronized (db) {
            List<ChatLogEntry> out = new ArrayList<>();
            String sql = "SELECT * FROM chatlog WHERE uuid=? ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ChatLogEntry(rs.getLong("id"),
                        UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                        rs.getString("kind"), rs.getString("text"), rs.getLong("created_at")));
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }
}
```

- [ ] **Step 5: Write `manager/ChatLogManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.ChatLogEntry;
import de.derfakegamer.sentinel.storage.ChatLogDao;

import java.util.List;
import java.util.UUID;

public final class ChatLogManager {
    private final ChatLogDao dao;

    public ChatLogManager(ChatLogDao dao) { this.dao = dao; }

    public void logChat(UUID uuid, String name, String text) { dao.log(uuid, name, "CHAT", text, System.currentTimeMillis()); }
    public void logCommand(UUID uuid, String name, String cmd) { dao.log(uuid, name, "COMMAND", cmd, System.currentTimeMillis()); }
    public List<ChatLogEntry> recent(UUID uuid, int limit) { return dao.recent(uuid, limit); }
}
```

- [ ] **Step 6: Log chat + commands**

In `listener/ChatListener.java` `onChat`, at the very TOP (before any return), log the message:

```java
plugin.chatLog().logChat(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()));
```

Create `listener/CommandLogListener.java`:

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class CommandLogListener implements Listener {
    private final Sentinel plugin;
    public CommandLogListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        plugin.chatLog().logCommand(event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getMessage());
    }
}
```

- [ ] **Step 7: Write `gui/ChatLogGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.ChatLogEntry;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ChatLogGui extends Gui {
    private static final int BACK = 45, CLOSE = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;

    public ChatLogGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-chatlog-title", "player", name()));
        List<ChatLogEntry> entries = plugin.chatLog().recent(target.getUniqueId(), 45);
        for (int i = 0; i < entries.size() && i < 45; i++) {
            ChatLogEntry e = entries.get(i);
            boolean cmd = e.kind().equals("COMMAND");
            inventory.setItem(i, Items.button(cmd ? Material.COMMAND_BLOCK : Material.PAPER,
                Component.text(e.text(), cmd ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                List.of(grey(e.kind() + " · " + DATE.format(Instant.ofEpochMilli(e.createdAt()))))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }
    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new PlayerActionsGui(plugin, target).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
    }
}
```

- [ ] **Step 8: Add a Logs button to `PlayerActionsGui` (slot 17) + message + wiring**

`PlayerActionsGui` constant `LOGS = 17`; render `Items.button(Material.WRITTEN_BOOK, Component.text("Chat logs", NamedTextColor.AQUA), List.of(grey hint))`; click case `case LOGS -> new ChatLogGui(plugin, target).open(mod);`.

`messages.yml`: `gui-chatlog-title: "<#3B82F6>Sentinel · Chat Log · <player>"`.

`Sentinel.java`: build `chatLogManager` (new ChatLogManager(new ChatLogDao(database))), add `chatLog()` getter, register `CommandLogListener`.

- [ ] **Step 9: Write `gui/ChatLogGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class ChatLogGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersLoggedMessages() {
        PlayerMock t = server.addPlayer("Bob");
        plugin.chatLog().logChat(t.getUniqueId(), "Bob", "hello world");
        plugin.chatLog().logCommand(t.getUniqueId(), "Bob", "/help");
        ChatLogGui gui = new ChatLogGui(plugin, t);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && (it.getType() == Material.PAPER || it.getType() == Material.COMMAND_BLOCK)) items++;
        }
        assertEquals(2, items);
    }
}
```

- [ ] **Step 10: Run tests + commit**

```bash
./gradlew test --tests ChatLogDaoTest --tests ChatLogGuiTest
git add -A && git commit -m "feat: chat and command logging with viewer GUI"
```

---

## Task 4: Optional Discord webhook

**Files:**
- Modify: `config.yml`, `manager/ModerationService.java`, `manager/ReportManager.java`, `Sentinel.java`
- Create: `util/DiscordWebhook.java`
- Test: `util/DiscordWebhookTest.java`

- [ ] **Step 1: Add config**

```yaml
discord:
  webhook-url: ''   # paste a Discord webhook URL to mirror punishments & reports; empty = off
```

- [ ] **Step 2: Write `util/DiscordWebhook.java`**

```java
package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class DiscordWebhook {
    private final Sentinel plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DiscordWebhook(Sentinel plugin) { this.plugin = plugin; }

    /** Posts a plain-text line to the configured webhook, async; no-op if unset. */
    public void post(String content) {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank()) return;
        String body = "{\"content\":\"" + escape(content) + "\"}";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                plugin.getLogger().fine("Discord webhook failed: " + e.getMessage());
            }
        });
    }

    /** Escapes a string for embedding in a JSON string literal. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
```

- [ ] **Step 3: Write `util/DiscordWebhookTest.java`**

```java
package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookTest {
    @Test void escapesQuotesAndNewlines() {
        assertEquals("a\\\"b", DiscordWebhook.escape("a\"b"));
        assertEquals("line1\\nline2", DiscordWebhook.escape("line1\nline2"));
        assertEquals("c:\\\\path", DiscordWebhook.escape("c:\\path"));
    }
}
```

- [ ] **Step 4: Hook it in**

In `Sentinel.java`: build `discordWebhook = new DiscordWebhook(this)` and add a `discord()` getter.

In `ModerationService.apply(...)`, after a successful non-shadow punishment broadcast, also post to Discord:

```java
plugin.discord().post("**" + targetName + "** was " + key + " by " + issuerName
    + (reason == null || reason.isBlank() ? "" : ": " + reason));
```

In `ReportManager.file(...)`, after inserting, post:

```java
plugin.discord().post(":triangular_flag_on_post: **" + reporter.getName() + "** reported **" + targetName + "**: " + reason);
```

- [ ] **Step 5: Run tests + commit**

```bash
./gradlew test --tests DiscordWebhookTest
git add -A && git commit -m "feat: optional Discord webhook for punishments and reports"
```

---

## Task 5: Punishment templates + ban-all-alts

**Files:**
- Modify: `config.yml`, `gui/PlayerActionsGui.java`, `gui/AltsGui.java`, `messages.yml`
- Create: `gui/TemplatesGui.java`
- Test: `gui/TemplatesGuiTest.java`

- [ ] **Step 1: Add templates to `config.yml`**

```yaml
templates:        # quick punishments: "<type> [duration] <reason>"
  - "ban Cheating / hacked client"
  - "tempban 7d Severe griefing"
  - "mute 1d Spam"
  - "kick Please read the rules"
  - "warn Inappropriate language"
```

- [ ] **Step 2: Write `gui/TemplatesGui.java`**

Parses each template line like `WarnEscalation` does (type + optional duration + reason) and applies it to the target on click.

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class TemplatesGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;

    private final OfflinePlayer target;
    private final List<String> templates;

    public TemplatesGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.templates = plugin.getConfig().getStringList("templates");
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-templates-title"));
        for (int i = 0; i < PAGE_SIZE && i < templates.size(); i++) {
            inventory.setItem(i, Items.button(Material.WRITABLE_BOOK,
                Component.text(templates.get(i), NamedTextColor.AQUA),
                List.of(Component.text("Click to apply", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new PlayerActionsGui(plugin, target).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot < 0 || slot >= PAGE_SIZE || slot >= templates.size()) return;
        apply(mod, templates.get(slot));
        mod.closeInventory();
    }

    private void apply(Player mod, String spec) {
        String[] parts = spec.trim().split("\\s+");
        String word = parts[0].toLowerCase();
        PunishmentType type; long expiresAt = 0; int reasonFrom = 1;
        switch (word) {
            case "ban" -> type = PunishmentType.BAN;
            case "mute" -> type = PunishmentType.MUTE;
            case "kick" -> type = PunishmentType.KICK;
            case "warn" -> type = PunishmentType.WARN;
            case "tempban", "tempmute" -> {
                type = word.equals("tempban") ? PunishmentType.BAN : PunishmentType.MUTE;
                if (parts.length < 2) return;
                try { expiresAt = System.currentTimeMillis() + DurationParser.parse(parts[1]); }
                catch (IllegalArgumentException e) { return; }
                reasonFrom = 2;
            }
            default -> { return; }
        }
        String reason = reasonFrom >= parts.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(parts, reasonFrom, parts.length));
        plugin.moderation().apply(mod.getUniqueId(), mod.getName(), target.getUniqueId(),
            target.getName() == null ? "?" : target.getName(),
            target.getPlayer() != null && target.getPlayer().getAddress() != null
                ? target.getPlayer().getAddress().getAddress().getHostAddress() : null,
            type, expiresAt, reason);
    }
}
```

- [ ] **Step 3: Add a Templates button to `PlayerActionsGui` (slot 27)**

`TEMPLATES = 27`; render `Items.button(Material.WRITABLE_BOOK, Component.text("Templates", NamedTextColor.AQUA), List.of(grey("Quick preset punishments")))`; click `case TEMPLATES -> new TemplatesGui(plugin, target).open(mod);`.
`messages.yml`: `gui-templates-title: "<#3B82F6>Sentinel · Templates"`.

- [ ] **Step 4: Add a "Ban all alts" button to `gui/AltsGui.java` (slot 49)**

In the `AltsGui` constructor, add a button at slot 49:

```java
inventory.setItem(49, Items.button(Material.TNT,
    Component.text("Ban all alts", NamedTextColor.RED),
    List.of(grey(alts.size() + " accounts + the target"))));
```

In `AltsGui.onClick`, handle slot 49: open a `ConfirmGui` whose action bans the target and every alt:

```java
if (slot == 49) {
    new ConfirmGui(plugin, Component.text("Ban " + (alts.size() + 1) + " accounts?", NamedTextColor.RED), () -> {
        long now = System.currentTimeMillis();
        plugin.moderation().apply(p.getUniqueId(), p.getName(), target.getUniqueId(),
            target.getName() == null ? "?" : target.getName(), null, de.derfakegamer.sentinel.model.PunishmentType.BAN, 0, "Alt of a banned account");
        for (var r : alts)
            plugin.moderation().apply(p.getUniqueId(), p.getName(), r.uuid(), r.name(), null,
                de.derfakegamer.sentinel.model.PunishmentType.BAN, 0, "Alt of a banned account");
    }, null).open(p);
    return;
}
```

(Place the slot-49 check before the entry-click handling. Slot 49 was the Close in earlier GUIs but in AltsGui the layout uses BACK=45/CLOSE=53, so 49 is free — verify and use a free slot; if 49 is taken, use 48.)

- [ ] **Step 5: Write `gui/TemplatesGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class TemplatesGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void clickingTemplateAppliesPunishment() {
        plugin.getConfig().set("templates", java.util.List.of("ban template ban reason"));
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        PlayerMock target = server.addPlayer("BadGuy");
        TemplatesGui gui = new TemplatesGui(plugin, target);
        gui.open(mod);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 0);
        gui.onClick(ev);
        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()));
    }
}
```

- [ ] **Step 6: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green. Shaded jar produced.

- [ ] **Step 7: Manual smoke test**

1. Shadow-mute a player → they see their own chat, you (and others) don't; OPs get a covert "shadow-muted" notice; un-shadowmute restores it.
2. Open a player → Chat logs → see their recent chat + commands.
3. Set a Discord webhook URL → ban someone → message appears in Discord. Empty URL → nothing happens.
4. Open a player → Templates → click one → punishment applied.
5. Open a player → Alts → "Ban all alts" → confirm → the player + all shared-IP accounts are banned.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: punishment templates and ban-all-alts"
```

---

## Self-Review Notes (plan vs. requirements)

- **Shadow-mute** ✓ (Tasks 1–2): covert type; audience restricted to the sender; staff-only notification; command + GUI button.
- **Chat & command logging** ✓ (Task 3): `chatlog` table, chat + command listeners, viewer GUI button.
- **Discord webhook** ✓ (Task 4): optional config URL, async POST, escaping unit-tested, hooked into punishments + reports.
- **Templates & mass-action** ✓ (Task 5): config templates GUI, ban-all-alts with a confirmation.
- **Type consistency:** `PunishmentType.SHADOWMUTE` added — all exhaustive switches (HistoryGui.iconFor, ModerationService.apply) updated. New `Sentinel` accessors `chatLog()`, `discord()`. New `PlayerActionsGui` slots `SHADOWMUTE_SLOT = 16`, `LOGS = 17`, `TEMPLATES = 27` (all previously filler — no existing test clicks them).
- **OP gating:** new commands declared with `permission: sentinel.use`.
- **Testing caveats:** Discord POST and the chat-audience restriction are server-side; tests cover the DAO, escaping, manager, and GUI rendering/navigation.
```
