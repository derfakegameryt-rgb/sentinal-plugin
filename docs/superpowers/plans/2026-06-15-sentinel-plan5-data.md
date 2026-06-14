# Sentinel — Plan 5: Player Data, Offline Support, Notes & Alts

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Track every player's name and last IP, so the plugin can act on offline players (offline IP-ban, lookup), surface alt accounts that share an IP, store staff notes per player, and offer command tab-completion.

**Architecture:** Two new SQLite tables (`players`, `notes`) with DAOs, fronted by thin managers (`PlayerDirectory`, `NoteManager`). The existing `LoginListener` records every login (name + IP) into `players`. New GUIs (`NotesGui`, `AltsGui`) reachable from `PlayerActionsGui`. Offline IP-ban falls back to the stored last IP. Command classes gain `TabCompleter`s.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0).

---

## File Structure

```
src/main/java/de/derfakegamer/sentinel/
  model/PlayerRecord.java          NEW  record (uuid, name, lastIp, firstSeen, lastSeen)
  model/Note.java                  NEW  record (id, targetUuid, author, text, createdAt)
  storage/Database.java            MOD  add `players` and `notes` tables
  storage/PlayerDao.java           NEW  upsert / byUuid / byName / byIp
  storage/NoteDao.java             NEW  insert / listFor
  manager/PlayerDirectory.java     NEW  record(), byUuid(), byName(), alts()
  manager/NoteManager.java         NEW  add(), list()
  listener/LoginListener.java      MOD  record login (name + ip) on pre-login
  gui/PlayerActionsGui.java        MOD  Notes + Alts buttons; offline IP-ban via stored IP
  gui/NotesGui.java                NEW  list + add staff notes
  gui/AltsGui.java                 NEW  accounts sharing the target's last IP
  command/SentinelCommand.java     MOD  implement TabCompleter
  command/PunishmentCommands.java  MOD  implement TabCompleter; offline-IP fallback in resolve()
  Sentinel.java                    MOD  build managers, register tab-completers, getters
  resources/messages.yml           MOD  new keys
src/test/java/de/derfakegamer/sentinel/
  storage/PlayerDaoTest.java           NEW
  storage/NoteDaoTest.java             NEW
  manager/PlayerDirectoryTest.java     NEW
  gui/NotesGuiTest.java                NEW
  gui/AltsGuiTest.java                 NEW
  command/TabCompleteTest.java         NEW
```

---

## Task 1: Storage — players & notes tables, DAOs, models

**Files:**
- Modify: `storage/Database.java`
- Create: `model/PlayerRecord.java`, `model/Note.java`, `storage/PlayerDao.java`, `storage/NoteDao.java`
- Test: `storage/PlayerDaoTest.java`, `storage/NoteDaoTest.java`

- [ ] **Step 1: Add tables to `Database.createSchema()`**

After the reports table/index statements, add:

```java
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS players (
      uuid TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      last_ip TEXT,
      first_seen INTEGER NOT NULL,
      last_seen INTEGER NOT NULL
    )""");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_ip ON players(last_ip)");
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS notes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      target_uuid TEXT NOT NULL,
      author TEXT NOT NULL,
      text TEXT NOT NULL,
      created_at INTEGER NOT NULL
    )""");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_notes_target ON notes(target_uuid)");
```

- [ ] **Step 2: Write `model/PlayerRecord.java` and `model/Note.java`**

```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record PlayerRecord(UUID uuid, String name, String lastIp, long firstSeen, long lastSeen) {}
```

```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record Note(long id, UUID targetUuid, String author, String text, long createdAt) {}
```

- [ ] **Step 3: Write the failing tests**

`storage/PlayerDaoTest.java`:

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.PlayerRecord;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDaoTest {
    Database db; PlayerDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new PlayerDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void upsertInsertsThenUpdates() {
        UUID id = UUID.randomUUID();
        dao.upsert(id, "Steve", "1.2.3.4", 100);
        dao.upsert(id, "SteveRenamed", "5.6.7.8", 200);
        PlayerRecord r = dao.byUuid(id);
        assertNotNull(r);
        assertEquals("SteveRenamed", r.name());
        assertEquals("5.6.7.8", r.lastIp());
        assertEquals(100, r.firstSeen());   // first_seen preserved
        assertEquals(200, r.lastSeen());
    }

    @Test void byNameIsCaseInsensitive() {
        UUID id = UUID.randomUUID();
        dao.upsert(id, "Alex", "1.1.1.1", 100);
        assertEquals(id, dao.byName("alex").uuid());
    }

    @Test void byIpReturnsAllSharingThatIp() {
        dao.upsert(UUID.randomUUID(), "A", "9.9.9.9", 100);
        dao.upsert(UUID.randomUUID(), "B", "9.9.9.9", 100);
        dao.upsert(UUID.randomUUID(), "C", "8.8.8.8", 100);
        assertEquals(2, dao.byIp("9.9.9.9").size());
    }
}
```

`storage/NoteDaoTest.java`:

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Note;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NoteDaoTest {
    Database db; NoteDao dao; File tmp;
    UUID target = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new NoteDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void insertThenList() {
        dao.insert(new Note(0, target, "Admin", "watch this guy", 100));
        dao.insert(new Note(0, target, "Mod", "spammed once", 200));
        assertEquals(2, dao.listFor(target).size());
    }

    @Test void listForUnknownIsEmpty() {
        assertTrue(dao.listFor(UUID.randomUUID()).isEmpty());
    }
}
```

- [ ] **Step 4: Run them to verify failure**

Run: `./gradlew test --tests PlayerDaoTest --tests NoteDaoTest`
Expected: FAIL — DAOs do not exist.

- [ ] **Step 5: Write `storage/PlayerDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.PlayerRecord;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerDao {
    private final Database db;

    public PlayerDao(Database db) { this.db = db; }

    public void upsert(UUID uuid, String name, String ip, long now) {
        synchronized (db) {
            String sql = """
                INSERT INTO players (uuid,name,last_ip,first_seen,last_seen)
                VALUES (?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_ip=excluded.last_ip,
                    last_seen=excluded.last_seen""";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public PlayerRecord byUuid(UUID uuid) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT * FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public PlayerRecord byName(String name) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM players WHERE name=? COLLATE NOCASE LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<PlayerRecord> byIp(String ip) {
        synchronized (db) {
            List<PlayerRecord> out = new ArrayList<>();
            if (ip == null) return out;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM players WHERE last_ip=? ORDER BY last_seen DESC")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    private PlayerRecord map(ResultSet rs) throws SQLException {
        return new PlayerRecord(UUID.fromString(rs.getString("uuid")), rs.getString("name"),
            rs.getString("last_ip"), rs.getLong("first_seen"), rs.getLong("last_seen"));
    }
}
```

- [ ] **Step 6: Write `storage/NoteDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Note;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NoteDao {
    private final Database db;

    public NoteDao(Database db) { this.db = db; }

    public long insert(Note n) {
        synchronized (db) {
            String sql = "INSERT INTO notes (target_uuid,author,text,created_at) VALUES (?,?,?,?)";
            try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, n.targetUuid().toString());
                ps.setString(2, n.author());
                ps.setString(3, n.text());
                ps.setLong(4, n.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<Note> listFor(UUID target) {
        synchronized (db) {
            List<Note> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM notes WHERE target_uuid=? ORDER BY created_at DESC")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Note(rs.getLong("id"),
                        UUID.fromString(rs.getString("target_uuid")), rs.getString("author"),
                        rs.getString("text"), rs.getLong("created_at")));
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }
}
```

- [ ] **Step 7: Run tests → green; confirm existing storage tests still pass**

Run: `./gradlew test --tests PlayerDaoTest --tests NoteDaoTest --tests PunishmentDaoTest --tests ReportDaoTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: players and notes tables with DAOs"
```

---

## Task 2: Managers, login tracking, wiring

**Files:**
- Create: `manager/PlayerDirectory.java`, `manager/NoteManager.java`
- Modify: `listener/LoginListener.java`, `Sentinel.java`
- Test: `manager/PlayerDirectoryTest.java`

- [ ] **Step 1: Write the failing test `PlayerDirectoryTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDirectoryTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void recordThenLookupByName() {
        UUID id = UUID.randomUUID();
        plugin.players().record(id, "Notch", "1.2.3.4");
        assertEquals(id, plugin.players().byName("notch").uuid());
        assertEquals("1.2.3.4", plugin.players().byUuid(id).lastIp());
    }

    @Test void altsShareIpExcludingSelf() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        plugin.players().record(a, "Main", "5.5.5.5");
        plugin.players().record(b, "Alt", "5.5.5.5");
        var alts = plugin.players().alts(a);
        assertEquals(1, alts.size());
        assertEquals(b, alts.get(0).uuid());
    }
}
```

- [ ] **Step 2: Run it → fails (no `players()`).**

Run: `./gradlew test --tests PlayerDirectoryTest`

- [ ] **Step 3: Write `manager/PlayerDirectory.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.storage.PlayerDao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerDirectory {
    private final PlayerDao dao;

    public PlayerDirectory(PlayerDao dao) { this.dao = dao; }

    public void record(UUID uuid, String name, String ip) {
        dao.upsert(uuid, name, ip, System.currentTimeMillis());
    }

    public PlayerRecord byUuid(UUID uuid) { return dao.byUuid(uuid); }

    public PlayerRecord byName(String name) { return dao.byName(name); }

    /** Other accounts that share this player's last IP. */
    public List<PlayerRecord> alts(UUID uuid) {
        PlayerRecord self = dao.byUuid(uuid);
        if (self == null || self.lastIp() == null) return List.of();
        List<PlayerRecord> out = new ArrayList<>();
        for (PlayerRecord r : dao.byIp(self.lastIp()))
            if (!r.uuid().equals(uuid)) out.add(r);
        return out;
    }
}
```

- [ ] **Step 4: Write `manager/NoteManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.Note;
import de.derfakegamer.sentinel.storage.NoteDao;

import java.util.List;
import java.util.UUID;

public final class NoteManager {
    private final NoteDao dao;

    public NoteManager(NoteDao dao) { this.dao = dao; }

    public void add(UUID target, String author, String text) {
        dao.insert(new Note(0, target, author, text, System.currentTimeMillis()));
    }

    public List<Note> list(UUID target) { return dao.listFor(target); }
}
```

- [ ] **Step 5: Record logins in `listener/LoginListener.java`**

At the END of `onPreLogin` (after the ban/ip-ban handling, regardless of result), record the player. Add before the method returns:

```java
String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : null;
plugin.players().record(event.getUniqueId(), event.getName(), ip);
```

Place this so it runs even when the login is allowed. (It is safe to record even for banned players — useful for alt detection.)

- [ ] **Step 6: Wire into `Sentinel.java`**

```java
// fields
private de.derfakegamer.sentinel.manager.PlayerDirectory playerDirectory;
private de.derfakegamer.sentinel.manager.NoteManager noteManager;

// in onEnable(), after database is open:
this.playerDirectory = new de.derfakegamer.sentinel.manager.PlayerDirectory(
    new de.derfakegamer.sentinel.storage.PlayerDao(database));
this.noteManager = new de.derfakegamer.sentinel.manager.NoteManager(
    new de.derfakegamer.sentinel.storage.NoteDao(database));

// getters
public de.derfakegamer.sentinel.manager.PlayerDirectory players() { return playerDirectory; }
public de.derfakegamer.sentinel.manager.NoteManager notes() { return noteManager; }
```

- [ ] **Step 7: Run tests**

Run: `./gradlew test --tests PlayerDirectoryTest --tests LoginListenerTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: player directory + note manager, record logins"
```

---

## Task 3: Offline IP-ban via stored IP

**Files:**
- Modify: `gui/PlayerActionsGui.java`, `command/PunishmentCommands.java`
- Test: extend `gui/PlayerActionsGuiToolsTest.java` (or a new small test)

- [ ] **Step 1: Update `PlayerActionsGui` to offer IP-ban for offline players with a stored IP**

Replace the `ip()` helper so it falls back to the stored last IP:

```java
private String ip() {
    Player online = target.getPlayer();
    if (online != null && online.getAddress() != null)
        return online.getAddress().getAddress().getHostAddress();
    var rec = plugin.players().byUuid(target.getUniqueId());
    return rec != null ? rec.lastIp() : null;
}
```

In the constructor, render the IP-Ban button whenever an IP is known (online OR stored), instead of only when online. Change the IP-Ban line so it is set when `ip() != null` (move it out of the `if (target.isOnline())` block; keep Freeze/Invsee/EChest inside the online block):

```java
if (ip() != null)
    inventory.setItem(IPBAN, Items.button(Material.IRON_BARS,
        net.kyori.adventure.text.Component.text("IP-Ban", net.kyori.adventure.text.format.NamedTextColor.DARK_RED),
        List.of(net.kyori.adventure.text.Component.text("Ban the last known IP", net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
```

The IPBAN click case already calls `ip()`; it now works for offline targets.

- [ ] **Step 2: Update `PunishmentCommands.resolve()` to fall back to the stored IP**

```java
private Target resolve(CommandSender sender, String name) {
    OfflinePlayer op = Bukkit.getOfflinePlayer(name);
    if (op.getUniqueId() == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return null; }
    String ip = (op.getPlayer() != null && op.getPlayer().getAddress() != null)
        ? op.getPlayer().getAddress().getAddress().getHostAddress() : null;
    if (ip == null) {
        var rec = plugin.players().byUuid(op.getUniqueId());
        if (rec != null) ip = rec.lastIp();
    }
    return new Target(op.getUniqueId(), name, ip);
}
```

The `ipban-requires-online` guard in the `ban,ipban,mute` case stays — but now `t.ip` is non-null for offline players we've seen before, so it only triggers for players with no recorded IP at all.

- [ ] **Step 3: Write/extend a test (in `PlayerActionsGuiToolsTest.java`)**

```java
    @Test void offlineIpBanUsesStoredIp() {
        org.mockbukkit.mockbukkit.entity.PlayerMock mod = server.addPlayer("Mod");
        java.util.UUID offline = java.util.UUID.randomUUID();
        plugin.players().record(offline, "GoneGuy", "7.7.7.7");
        org.bukkit.OfflinePlayer target = server.getOfflinePlayer(offline);

        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        // IP-Ban button is present at slot 19 even though the target is offline
        assertNotNull(gui.getInventory().getItem(19));
        assertEquals(org.bukkit.Material.IRON_BARS, gui.getInventory().getItem(19).getType());
    }
```

> **Note:** `server.getOfflinePlayer(uuid)` returns an `OfflinePlayer` whose `getName()` may be null in MockBukkit; `PlayerActionsGui.name()` already handles null. If `getOfflinePlayer(UUID)` is unavailable, use `server.getOfflinePlayer("GoneGuy")` and record that name. Keep the assertion that slot 19 holds the IP-Ban item.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests PlayerActionsGuiToolsTest --tests PunishmentCommandsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: offline IP-ban via last known IP"
```

---

## Task 4: NotesGui + button

**Files:**
- Create: `gui/NotesGui.java`
- Modify: `gui/PlayerActionsGui.java` (add Notes button at slot 24), `messages.yml`
- Test: `gui/NotesGuiTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
gui-notes-title: "<#3B82F6>Sentinel · Notes · <player>"
enter-note: "<#60A5FA>Type the note in chat, or type <white>cancel<#60A5FA>."
note-added: "<#60A5FA>Note added."
notes-empty: "<gray>No notes yet. Click the book to add one."
```

- [ ] **Step 2: Write the failing test `NotesGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.OfflinePlayer;
import static org.junit.jupiter.api.Assertions.*;

class NotesGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerNote() {
        OfflinePlayer target = server.addPlayer("Suspect");
        plugin.notes().add(target.getUniqueId(), "Admin", "warned for spam");
        plugin.notes().add(target.getUniqueId(), "Mod", "rude in chat");
        NotesGui gui = new NotesGui(plugin, target);
        int notes = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PAPER) notes++;
        }
        assertEquals(2, notes);
    }

    @Test void addButtonAwaitsChatInput() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Suspect");
        NotesGui gui = new NotesGui(plugin, target);
        gui.open(mod);
        // the add-note button is at slot 49
        org.bukkit.event.inventory.InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 49);
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertTrue(plugin.chatInput().has(mod.getUniqueId()), "clicking add awaits a chat note");
    }
}
```

- [ ] **Step 3: Write `gui/NotesGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Note;
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

public final class NotesGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int ADD = 49, BACK = 45, CLOSE = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;

    public NotesGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-notes-title", "player", name()));
        List<Note> notes = plugin.notes().list(target.getUniqueId());
        for (int i = 0; i < PAGE_SIZE && i < notes.size(); i++) {
            Note n = notes.get(i);
            inventory.setItem(i, Items.button(Material.PAPER,
                Component.text(n.text(), NamedTextColor.WHITE),
                List.of(grey("By: " + n.author()),
                        grey("At: " + DATE.format(Instant.ofEpochMilli(n.createdAt()))))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(ADD, Items.button(Material.WRITABLE_BOOK,
            Component.text("Add note", NamedTextColor.AQUA), List.of(grey("Type the note in chat"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Component grey(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case ADD -> {
                mod.closeInventory();
                mod.sendMessage(plugin.messages().prefixed("enter-note"));
                plugin.chatInput().await(mod.getUniqueId(), text -> {
                    plugin.notes().add(target.getUniqueId(), mod.getName(), text);
                    mod.sendMessage(plugin.messages().prefixed("note-added"));
                    new NotesGui(plugin, target).open(mod);
                });
            }
            case BACK -> new PlayerActionsGui(plugin, target).open(mod);
            case CLOSE -> mod.closeInventory();
        }
    }
}
```

- [ ] **Step 4: Add a Notes button to `PlayerActionsGui`**

Add a constant `NOTES = 24` to the slot constants line, set the button in the constructor (always shown), and add a click case:

```java
// constructor (after HISTORY):
inventory.setItem(NOTES, Items.button(Material.BOOK,
    net.kyori.adventure.text.Component.text("Notes", net.kyori.adventure.text.format.NamedTextColor.AQUA),
    List.of(net.kyori.adventure.text.Component.text("Staff notes about this player", net.kyori.adventure.text.format.NamedTextColor.GRAY)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));

// onClick:
case NOTES -> new NotesGui(plugin, target).open(mod);
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests NotesGuiTest --tests PlayerActionsGuiTest --tests PlayerActionsGuiToolsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: staff notes GUI"
```

---

## Task 5: AltsGui + button

**Files:**
- Create: `gui/AltsGui.java`
- Modify: `gui/PlayerActionsGui.java` (Alts button at slot 25), `messages.yml`
- Test: `gui/AltsGuiTest.java`

- [ ] **Step 1: Add the title key to `messages.yml`**

```yaml
gui-alts-title: "<#3B82F6>Sentinel · Alts · <player>"
alts-empty: "<gray>No known alt accounts (no shared IP)."
```

- [ ] **Step 2: Write the failing test `AltsGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AltsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void showsAccountsSharingIp() {
        PlayerMock main = server.addPlayer("Main");
        UUID altId = UUID.randomUUID();
        plugin.players().record(main.getUniqueId(), "Main", "4.4.4.4");
        plugin.players().record(altId, "AltAccount", "4.4.4.4");

        OfflinePlayer target = main;
        AltsGui gui = new AltsGui(plugin, target);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(1, heads);  // the one alt (excluding the target itself)
    }
}
```

- [ ] **Step 3: Write `gui/AltsGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
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

public final class AltsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;
    private final List<PlayerRecord> alts;

    public AltsGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        this.alts = plugin.players().alts(target.getUniqueId());
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-alts-title", "player", name()));
        for (int i = 0; i < PAGE_SIZE && i < alts.size(); i++) {
            PlayerRecord r = alts.get(i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(r.uuid()),
                Component.text(r.name(), NamedTextColor.AQUA),
                List.of(grey("Shared IP: " + r.lastIp()),
                        grey("Last seen: " + DATE.format(Instant.ofEpochMilli(r.lastSeen()))),
                        grey("Click to open their actions"))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Component grey(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new PlayerActionsGui(plugin, target).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot >= 0 && slot < PAGE_SIZE && slot < alts.size()) {
            OfflinePlayer alt = Bukkit.getOfflinePlayer(alts.get(slot).uuid());
            new PlayerActionsGui(plugin, alt).open(mod);
        }
    }
}
```

- [ ] **Step 4: Add an Alts button to `PlayerActionsGui`**

Add `ALTS = 25` constant, set the button (always shown), add click case:

```java
// constructor:
inventory.setItem(ALTS, Items.button(Material.PLAYER_HEAD,
    net.kyori.adventure.text.Component.text("Alts", net.kyori.adventure.text.format.NamedTextColor.AQUA),
    List.of(net.kyori.adventure.text.Component.text("Accounts sharing this IP", net.kyori.adventure.text.format.NamedTextColor.GRAY)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));

// onClick:
case ALTS -> new AltsGui(plugin, target).open(mod);
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests AltsGuiTest --tests PlayerActionsGuiTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: alt-account detection GUI"
```

---

## Task 6: Tab-completion

**Files:**
- Modify: `command/SentinelCommand.java`, `command/PunishmentCommands.java`, `Sentinel.java`
- Test: `command/TabCompleteTest.java`

- [ ] **Step 1: Write the failing test `TabCompleteTest.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TabCompleteTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void sentinelSuggestsSubcommands() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        Command cmd = server.getCommandMap().getCommand("sentinel");
        List<String> out = cmd.tabComplete(op, "sentinel", new String[]{"re"});
        assertTrue(out.contains("reload"));
    }

    @Test void banSuggestsOnlinePlayerNames() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        server.addPlayer("Griefer");
        Command cmd = server.getCommandMap().getCommand("ban");
        List<String> out = cmd.tabComplete(op, "ban", new String[]{"Gr"});
        assertTrue(out.contains("Griefer"));
    }

    @Test void tempbanSuggestsDurationsForSecondArg() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        Command cmd = server.getCommandMap().getCommand("tempban");
        List<String> out = cmd.tabComplete(op, "tempban", new String[]{"Griefer", "1"});
        assertTrue(out.stream().anyMatch(s -> s.startsWith("1")));
    }
}
```

> **Note:** If MockBukkit's `Command.tabComplete(...)` routing to the registered `TabCompleter` differs, call the completer directly: cast the executor to `TabCompleter` and invoke `onTabComplete(sender, command, label, args)`. Keep the assertions.

- [ ] **Step 2: Run it → fails (no completion).**

- [ ] **Step 3: Make `SentinelCommand` implement `TabCompleter`**

Add `implements CommandExecutor, TabCompleter` and the method:

```java
@Override
public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
        org.bukkit.command.Command command, String label, String[] args) {
    if (!sender.isOp()) return java.util.List.of();
    if (args.length == 1) {
        java.util.List<String> opts = new java.util.ArrayList<>(java.util.List.of("reload", "update"));
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) opts.add(p.getName());
        return filter(opts, args[0]);
    }
    return java.util.List.of();
}

private static java.util.List<String> filter(java.util.List<String> options, String prefix) {
    String low = prefix.toLowerCase();
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String o : options) if (o.toLowerCase().startsWith(low)) out.add(o);
    return out;
}
```

Add imports for `org.bukkit.command.TabCompleter`.

- [ ] **Step 4: Make `PunishmentCommands` implement `TabCompleter`**

Add `implements CommandExecutor, TabCompleter` and:

```java
private static final java.util.List<String> DURATIONS =
    java.util.List.of("30m", "1h", "6h", "12h", "1d", "3d", "7d", "30d");

@Override
public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender,
        org.bukkit.command.Command command, String label, String[] args) {
    if (!sender.isOp()) return java.util.List.of();
    boolean temp = command.getName().equalsIgnoreCase("tempban")
        || command.getName().equalsIgnoreCase("tempmute");
    if (args.length == 1) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) names.add(p.getName());
        return filter(names, args[0]);
    }
    if (args.length == 2 && temp) return filter(DURATIONS, args[1]);
    return java.util.List.of();
}

private static java.util.List<String> filter(java.util.List<String> options, String prefix) {
    String low = prefix.toLowerCase();
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String o : options) if (o.toLowerCase().startsWith(low)) out.add(o);
    return out;
}
```

- [ ] **Step 5: Register the completers in `Sentinel.java` `onEnable()`**

After setting the executors:

```java
getCommand("sentinel").setTabCompleter(sentinelCmd);
getCommand("sn").setTabCompleter(sentinelCmd);
for (String c : new String[]{"ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn","history"})
    getCommand(c).setTabCompleter(pc);
```

- [ ] **Step 6: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green (prior + new ones).

- [ ] **Step 7: Manual smoke test**

1. Type `/ban ` then Tab → online names complete.
2. `/tempban Griefer ` + Tab → duration suggestions.
3. `/sn re` + Tab → `reload`.
4. Open a player → Notes → add a note → it appears; Alts → accounts on the same IP show; click one → their actions open.
5. IP-ban an offline player who has joined before → works.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: command tab-completion"
```

---

## Self-Review Notes (plan vs. requirements)

- **Offline support** ✓ (Tasks 1–3): logins recorded; offline IP-ban via stored IP; name/uuid lookup.
- **Staff notes** ✓ (Task 4): NotesGui list + add, button in PlayerActionsGui.
- **Alt detection** ✓ (Task 5): AltsGui from shared last IP, click-through to a player's actions.
- **Tab-completion** ✓ (Task 6): subcommands, online names, duration hints.
- **Deferred to Plan 6:** chat-moderation, warn-escalation, GUI sounds, player search.
- **Type consistency:** new `Sentinel` accessors `players()` (PlayerDirectory) and `notes()` (NoteManager). `PlayerActionsGui` gains slots `NOTES = 24`, `ALTS = 25` (no existing slot reused — existing tests untouched). All DAOs synchronize on the shared `Database` like the others.
- **Testing caveat:** MockBukkit `getOfflinePlayer(UUID)` name handling and `Command.tabComplete` routing flagged inline.
```
