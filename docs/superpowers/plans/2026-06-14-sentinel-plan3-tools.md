# Sentinel — Plan 3: Tools, Reports & Staff Chat

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the remaining moderator tools on top of Plans 1–2: Freeze, self-Vanish, live Invsee and EChestSee, a player Reports system (`/report` + staff Reports GUI), and Staff Chat (`/sc`). After this plan the Player Actions and Players GUIs have their full button sets.

**Architecture:** Four small stateful managers (`FreezeManager`, `VanishManager`, `StaffChatManager`, `ReportManager`) hold runtime/persisted state. Two new listeners enforce them: `MoveListener` blocks movement of frozen players; `JoinQuitListener` maintains vanish visibility (and the existing `ChatListener` gains staff-chat routing). Reports persist in a new `reports` SQLite table via `ReportDao`. The existing GUIs gain buttons that call these managers; Invsee/EChestSee open the target's *real* inventory (not a `Gui` holder), so they stay live-editable and are deliberately not click-cancelled.

**Tech Stack:** Same as Plans 1–2 (Java 21, Paper 1.21.11 API, Adventure/MiniMessage, SQLite, JUnit 5 + MockBukkit 4.110.0).

---

## File Structure

```
src/main/java/de/derfakegamer/sentinel/
  model/Report.java                NEW  record of one report
  storage/Database.java            MOD  add `reports` table to schema
  storage/ReportDao.java           NEW  insert / find open / mark handled
  manager/ReportManager.java       NEW  file + notify staff + list + handle
  manager/FreezeManager.java       NEW  frozen players (runtime set)
  manager/VanishManager.java       NEW  vanished staff + visibility application
  manager/StaffChatManager.java    NEW  staff-chat toggles + send
  listener/MoveListener.java       NEW  cancel movement of frozen players
  listener/JoinQuitListener.java   NEW  apply vanish visibility on join
  listener/ChatListener.java       MOD  route staff-chat before mute check
  command/ReportCommand.java       NEW  /report <player> <reason> (everyone)
  command/StaffChatCommand.java    NEW  /sc [message] (OP)
  gui/ReportsGui.java              NEW  paginated open reports
  gui/PlayerActionsGui.java        MOD  add Freeze, Invsee, EChestSee; move History
  gui/PlayersGui.java              MOD  add Vanish, Reports, Staff buttons
  Sentinel.java                    MOD  build managers, register listeners/commands, getters
  resources/plugin.yml             MOD  declare `report` and `sc`
  resources/messages.yml           MOD  new keys
src/test/java/de/derfakegamer/sentinel/
  storage/ReportDaoTest.java           NEW
  manager/ReportManagerTest.java       NEW
  manager/StaffChatManagerTest.java    NEW
  manager/FreezeManagerTest.java       NEW
  manager/VanishManagerTest.java       NEW
  gui/ReportsGuiTest.java              NEW
  gui/PlayerActionsGuiToolsTest.java   NEW
  gui/PlayersGuiButtonsTest.java       NEW
```

---

## Task 1: Reports storage (table, model, DAO)

**Files:**
- Modify: `storage/Database.java`
- Create: `model/Report.java`, `storage/ReportDao.java`
- Test: `storage/ReportDaoTest.java`

- [ ] **Step 1: Add the `reports` table to `Database.createSchema()`**

In `storage/Database.java`, inside `createSchema()` after the punishments index statements, add:

```java
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS reports (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      reporter_uuid TEXT NOT NULL,
      reporter_name TEXT NOT NULL,
      target_uuid TEXT NOT NULL,
      target_name TEXT NOT NULL,
      reason TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      handled INTEGER NOT NULL DEFAULT 0,
      handled_by TEXT
    )""");
st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_report_open ON reports(handled)");
```

- [ ] **Step 2: Write `model/Report.java`**

```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

public record Report(long id, UUID reporterUuid, String reporterName, UUID targetUuid,
                     String targetName, String reason, long createdAt, boolean handled, String handledBy) {}
```

- [ ] **Step 3: Write the failing test `ReportDaoTest.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Report;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReportDaoTest {
    Database db; ReportDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new ReportDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Report open(String reason) {
        return new Report(0, UUID.randomUUID(), "Reporter", UUID.randomUUID(), "Target",
            reason, 100, false, null);
    }

    @Test void insertThenFindOpen() {
        dao.insert(open("hacking"));
        dao.insert(open("spam"));
        assertEquals(2, dao.findOpen().size());
    }

    @Test void markHandledRemovesFromOpen() {
        long id = dao.insert(open("hacking"));
        dao.markHandled(id, "Admin");
        assertEquals(0, dao.findOpen().size());
    }
}
```

- [ ] **Step 4: Run it to verify failure**

Run: `./gradlew test --tests ReportDaoTest`
Expected: FAIL — `ReportDao` does not exist.

- [ ] **Step 5: Write `storage/ReportDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Report;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ReportDao {
    private final Database db;

    public ReportDao(Database db) { this.db = db; }

    public long insert(Report r) {
        synchronized (db) {
            String sql = """
                INSERT INTO reports (reporter_uuid,reporter_name,target_uuid,target_name,reason,created_at,handled,handled_by)
                VALUES (?,?,?,?,?,?,?,?)""";
            try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, r.reporterUuid().toString());
                ps.setString(2, r.reporterName());
                ps.setString(3, r.targetUuid().toString());
                ps.setString(4, r.targetName());
                ps.setString(5, r.reason());
                ps.setLong(6, r.createdAt());
                ps.setInt(7, r.handled() ? 1 : 0);
                ps.setString(8, r.handledBy());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public List<Report> findOpen() {
        synchronized (db) {
            String sql = "SELECT * FROM reports WHERE handled=0 ORDER BY created_at ASC";
            List<Report> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }

    public void markHandled(long id, String handledBy) {
        synchronized (db) {
            String sql = "UPDATE reports SET handled=1, handled_by=? WHERE id=?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, handledBy);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    private Report map(ResultSet rs) throws SQLException {
        return new Report(rs.getLong("id"),
            UUID.fromString(rs.getString("reporter_uuid")), rs.getString("reporter_name"),
            UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
            rs.getString("reason"), rs.getLong("created_at"),
            rs.getInt("handled") == 1, rs.getString("handled_by"));
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests ReportDaoTest`
Expected: PASS (2 tests). Also run `./gradlew test --tests PunishmentDaoTest` to confirm the schema change didn't break Plan 1.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: reports table, model, and DAO"
```

---

## Task 2: ReportManager + `/report` command

**Files:**
- Create: `manager/ReportManager.java`, `command/ReportCommand.java`
- Modify: `Sentinel.java` (build manager + DAO, register command), `plugin.yml`, `messages.yml`
- Test: `manager/ReportManagerTest.java`

- [ ] **Step 1: Declare `report` and `sc` in `plugin.yml`**

Add under `commands:`:

```yaml
  report: { description: Report a player }
  sc: { description: Staff chat }
```

- [ ] **Step 2: Add message keys to `messages.yml`**

```yaml
report-usage: "<red>Usage: /report <player> <reason>"
report-filed: "<#60A5FA>Thanks — your report has been sent to staff."
report-self: "<red>You cannot report yourself."
report-alert: "<#3B82F6><bold>Report</bold> <dark_gray>»</dark_gray> <#60A5FA><reporter></#60A5FA> <gray>reported <#60A5FA><player></#60A5FA><gray>: <white><reason>"
report-handled: "<#60A5FA>Report marked as handled."
```

- [ ] **Step 3: Write the failing test `ReportManagerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class ReportManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void fileCreatesOpenReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getName());
        // reason is read from a second arg in the command; here we file directly:
    }
}
```

Replace the body once you know the `file` signature — see Step 4. The real assertions:

```java
    @Test void fileCreatesOpenReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        boolean ok = plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");
        assertTrue(ok);
        assertEquals(1, plugin.reports().open().size());
    }

    @Test void handleClosesReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");
        long id = plugin.reports().open().get(0).id();
        plugin.reports().handle(id, "Admin");
        assertEquals(0, plugin.reports().open().size());
    }
```

(Delete the placeholder first test; keep only the two real ones.)

- [ ] **Step 4: Write `manager/ReportManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import de.derfakegamer.sentinel.storage.ReportDao;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class ReportManager {
    private final Sentinel plugin;
    private final ReportDao dao;

    public ReportManager(Sentinel plugin, ReportDao dao) { this.plugin = plugin; this.dao = dao; }

    /** Files a report and alerts online staff. Returns false if reporter == target. */
    public boolean file(CommandSender reporter, UUID targetId, String targetName, String reason) {
        UUID reporterId = (reporter instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        if (reporterId.equals(targetId)) return false;
        dao.insert(new Report(0, reporterId, reporter.getName(), targetId, targetName,
            reason, System.currentTimeMillis(), false, null));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp()) staff.sendMessage(plugin.messages().plain("report-alert",
                "reporter", reporter.getName(), "player", targetName, "reason", reason));
        }
        return true;
    }

    public List<Report> open() { return dao.findOpen(); }

    public void handle(long id, String staffName) { dao.markHandled(id, staffName); }
}
```

- [ ] **Step 5: Write `command/ReportCommand.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ReportCommand implements CommandExecutor {
    private final Sentinel plugin;

    public ReportCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(plugin.messages().prefixed("report-usage")); return true; }
        if (args.length < 2) { sender.sendMessage(plugin.messages().prefixed("report-usage")); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        boolean ok = plugin.reports().file(sender, target.getUniqueId(), args[0], reason);
        sender.sendMessage(ok ? plugin.messages().prefixed("report-filed")
                              : plugin.messages().prefixed("report-self"));
        return true;
    }
}
```

- [ ] **Step 6: Wire into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.ReportManager reportManager;

// in onEnable(), after database is open:
this.reportManager = new de.derfakegamer.sentinel.manager.ReportManager(this,
    new de.derfakegamer.sentinel.storage.ReportDao(database));

// register command
getCommand("report").setExecutor(new de.derfakegamer.sentinel.command.ReportCommand(this));

// getter
public de.derfakegamer.sentinel.manager.ReportManager reports() { return reportManager; }
```

- [ ] **Step 7: Run tests**

Run: `./gradlew test --tests ReportManagerTest`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: report manager and /report command"
```

---

## Task 3: Staff Chat

**Files:**
- Create: `manager/StaffChatManager.java`, `command/StaffChatCommand.java`
- Modify: `listener/ChatListener.java`, `Sentinel.java`
- Test: `manager/StaffChatManagerTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
staffchat-prefix: "<#3B82F6><bold>Staff</bold> <dark_gray>»</dark_gray> <#60A5FA><player></#60A5FA><gray>: <white><message>"
staffchat-on: "<#60A5FA>Staff chat enabled — your messages now go to staff."
staffchat-off: "<#60A5FA>Staff chat disabled."
```

- [ ] **Step 2: Write the failing test `StaffChatManagerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class StaffChatManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void toggleFlipsState() {
        PlayerMock p = server.addPlayer("Mod");
        assertTrue(plugin.staffChat().toggle(p.getUniqueId()));   // now on
        assertTrue(plugin.staffChat().isToggled(p.getUniqueId()));
        assertFalse(plugin.staffChat().toggle(p.getUniqueId()));  // now off
        assertFalse(plugin.staffChat().isToggled(p.getUniqueId()));
    }

    @Test void sendReachesOnlineOps() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        plugin.staffChat().send("Mod", "hello team");
        net.kyori.adventure.text.Component msg = op.nextComponentMessage();
        assertNotNull(msg);
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(msg).contains("hello team"));
    }
}
```

- [ ] **Step 3: Write `manager/StaffChatManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffChatManager {
    private final Sentinel plugin;
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();

    public StaffChatManager(Sentinel plugin) { this.plugin = plugin; }

    /** Flips staff-chat mode for a player. Returns the new state (true = now on). */
    public boolean toggle(UUID player) {
        if (toggled.add(player)) return true;
        toggled.remove(player);
        return false;
    }

    public boolean isToggled(UUID player) { return toggled.contains(player); }

    public void send(String senderName, String message) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp())
                staff.sendMessage(plugin.messages().plain("staffchat-prefix", "player", senderName, "message", message));
        }
    }
}
```

- [ ] **Step 4: Write `command/StaffChatCommand.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class StaffChatCommand implements CommandExecutor {
    private final Sentinel plugin;

    public StaffChatCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 0) {
            if (!(sender instanceof Player p)) return true;
            boolean on = plugin.staffChat().toggle(p.getUniqueId());
            sender.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));
            return true;
        }
        plugin.staffChat().send(sender.getName(), String.join(" ", args));
        return true;
    }
}
```

- [ ] **Step 5: Route staff chat in `listener/ChatListener.java`**

Insert a staff-chat check AFTER the pending-input block and BEFORE the mute check. Add inside `onChat`, right after the `if (plugin.chatInput().has(id)) { ... return; }` block:

```java
if (plugin.staffChat().isToggled(id)) {
    event.setCancelled(true);
    String text = PlainTextComponentSerializer.plainText().serialize(event.message());
    plugin.staffChat().send(event.getPlayer().getName(), text);
    return;
}
```

- [ ] **Step 6: Wire into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.StaffChatManager staffChatManager;

// in onEnable(), before listeners:
this.staffChatManager = new de.derfakegamer.sentinel.manager.StaffChatManager(this);

// register command
getCommand("sc").setExecutor(new de.derfakegamer.sentinel.command.StaffChatCommand(this));

// getter
public de.derfakegamer.sentinel.manager.StaffChatManager staffChat() { return staffChatManager; }
```

- [ ] **Step 7: Run tests**

Run: `./gradlew test --tests StaffChatManagerTest` and `./gradlew test --tests ChatListenerTest`
Expected: PASS (StaffChatManager 2; ChatListener still 5).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: staff chat manager, /sc command, and chat routing"
```

---

## Task 4: Freeze (manager + move enforcement)

**Files:**
- Create: `manager/FreezeManager.java`, `listener/MoveListener.java`
- Modify: `Sentinel.java`, `messages.yml`
- Test: `manager/FreezeManagerTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
freeze-frozen: "<#60A5FA><player></#60A5FA> <gray>is now frozen."
freeze-unfrozen: "<#60A5FA><player></#60A5FA> <gray>is no longer frozen."
you-are-frozen: "<red>You are frozen by staff. Do not log out."
```

- [ ] **Step 2: Write the failing test `FreezeManagerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FreezeManagerTest {
    @Test void toggleTracksFrozenState() {
        FreezeManager mgr = new FreezeManager();
        UUID id = UUID.randomUUID();
        assertFalse(mgr.isFrozen(id));
        assertTrue(mgr.toggle(id));   // now frozen
        assertTrue(mgr.isFrozen(id));
        assertFalse(mgr.toggle(id));  // now unfrozen
        assertFalse(mgr.isFrozen(id));
    }
}
```

- [ ] **Step 3: Write `manager/FreezeManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FreezeManager {
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    /** Flips freeze state. Returns the new state (true = now frozen). */
    public boolean toggle(UUID player) {
        if (frozen.add(player)) return true;
        frozen.remove(player);
        return false;
    }

    public boolean isFrozen(UUID player) { return frozen.contains(player); }

    public void unfreeze(UUID player) { frozen.remove(player); }
}
```

- [ ] **Step 4: Write `listener/MoveListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class MoveListener implements Listener {
    private final Sentinel plugin;

    public MoveListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.freeze().isFrozen(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        // allow looking around, block changing position
        if (to != null && (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ())) {
            event.setCancelled(true);
        }
    }
}
```

- [ ] **Step 5: Wire into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.FreezeManager freezeManager;

// in onEnable(), before listeners:
this.freezeManager = new de.derfakegamer.sentinel.manager.FreezeManager();

// register listener
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.MoveListener(this), this);

// getter
public de.derfakegamer.sentinel.manager.FreezeManager freeze() { return freezeManager; }
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests FreezeManagerTest`
Expected: PASS (1 test).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: freeze manager and movement enforcement"
```

---

## Task 5: Vanish (manager + visibility)

**Files:**
- Create: `manager/VanishManager.java`, `listener/JoinQuitListener.java`
- Modify: `Sentinel.java`
- Test: `manager/VanishManagerTest.java`

Vanished staff are hidden from non-OP players. OPs can always see them.

- [ ] **Step 1: Write the failing test `VanishManagerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class VanishManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void toggleTracksVanishState() {
        PlayerMock staff = server.addPlayer("Mod");
        assertTrue(plugin.vanish().toggle(staff));   // now vanished
        assertTrue(plugin.vanish().isVanished(staff.getUniqueId()));
        assertFalse(plugin.vanish().toggle(staff));  // now visible
        assertFalse(plugin.vanish().isVanished(staff.getUniqueId()));
    }

    @Test void vanishedHiddenFromNonOp() {
        PlayerMock staff = server.addPlayer("Mod");
        PlayerMock normal = server.addPlayer("Player");
        plugin.vanish().toggle(staff); // vanish
        assertFalse(normal.canSee(staff), "non-op should not see a vanished staff member");
    }
}
```

> **Note:** If MockBukkit's `PlayerMock.canSee(...)` does not reflect `hidePlayer`, change the second test to assert manager state plus that `hidePlayer` was invoked via a different observable, or drop the visibility assertion and keep the state assertion. Prefer keeping the `canSee` check if it works.

- [ ] **Step 2: Write `manager/VanishManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VanishManager {
    private final Sentinel plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isVanished(UUID player) { return vanished.contains(player); }

    /** Flips vanish for a staff member and updates visibility. Returns new state (true = now vanished). */
    public boolean toggle(Player staff) {
        boolean nowVanished;
        if (vanished.add(staff.getUniqueId())) { hideFromNonOps(staff); nowVanished = true; }
        else { vanished.remove(staff.getUniqueId()); showToAll(staff); nowVanished = false; }
        return nowVanished;
    }

    /** When a player joins, hide every currently-vanished staff member from them (unless they are op). */
    public void applyOnJoin(Player joiner) {
        if (joiner.isOp()) return;
        for (UUID id : vanished) {
            Player staff = Bukkit.getPlayer(id);
            if (staff != null) joiner.hidePlayer(plugin, staff);
        }
    }

    private void hideFromNonOps(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers())
            if (!other.isOp() && !other.equals(staff)) other.hidePlayer(plugin, staff);
    }

    private void showToAll(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, staff);
    }
}
```

- [ ] **Step 3: Write `listener/JoinQuitListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinQuitListener implements Listener {
    private final Sentinel plugin;

    public JoinQuitListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.vanish().applyOnJoin(event.getPlayer());
    }
}
```

- [ ] **Step 4: Wire into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.VanishManager vanishManager;

// in onEnable(), before listeners:
this.vanishManager = new de.derfakegamer.sentinel.manager.VanishManager(this);

// register listener
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.JoinQuitListener(this), this);

// getter
public de.derfakegamer.sentinel.manager.VanishManager vanish() { return vanishManager; }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests VanishManagerTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: vanish manager and join visibility"
```

---

## Task 6: ReportsGui

**Files:**
- Create: `gui/ReportsGui.java`
- Modify: `messages.yml`
- Test: `gui/ReportsGuiTest.java`

A 54-slot paginated list of open reports (slots 0–44, one paper item per report with reporter/target/reason/time lore). Bottom: slot 45 ◀ (page > 0), slot 49 Close, slot 53 ▶. Left-click an entry = teleport the staff to the (online) target; Right-click = open the target's `PlayerActionsGui`; Shift-click = mark handled (and refresh).

- [ ] **Step 1: Add the title key to `messages.yml`**

```yaml
gui-reports-title: "<#3B82F6>Sentinel · Reports"
```

- [ ] **Step 2: Write the failing test `ReportsGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class ReportsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerOpenReport() {
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");

        ReportsGui gui = new ReportsGui(plugin, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            if (item != null && item.getType() != Material.LIGHT_BLUE_STAINED_GLASS_PANE) items++;
        }
        assertEquals(1, items);
    }

    @Test void shiftClickMarksHandled() {
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        PlayerMock reporter = server.addPlayer("Reporter");
        PlayerMock target = server.addPlayer("Target");
        plugin.reports().file(reporter, target.getUniqueId(), target.getName(), "hacking");

        ReportsGui gui = new ReportsGui(plugin, 0);
        gui.open(mod);
        InventoryClickEvent event = new InventoryClickEvent(mod.openInventory(gui.getInventory()),
            InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertEquals(0, plugin.reports().open().size(), "shift-click handles the report");
    }
}
```

- [ ] **Step 3: Write `gui/ReportsGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Report;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ReportsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, CLOSE = 49, NEXT = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final int page;
    private final List<Report> reports;

    public ReportsGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.reports = plugin.reports().open();
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-reports-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < reports.size(); i++) {
            Report r = reports.get(from + i);
            inventory.setItem(i, Items.button(Material.PAPER, Component.text(r.targetName()), List.of(
                Component.text("Reported by: " + r.reporterName()),
                Component.text("Reason: " + r.reason()),
                Component.text("At: " + DATE.format(Instant.ofEpochMilli(r.createdAt()))),
                Component.text("Left: teleport  Right: actions  Shift: handled"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous"), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        if (from + PAGE_SIZE < reports.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new ReportsGui(plugin, page - 1).open(mod); return; }
        if (slot == NEXT) { new ReportsGui(plugin, page + 1).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }

        int index = page * PAGE_SIZE + slot;
        if (slot < 0 || slot >= PAGE_SIZE || index >= reports.size()) return;
        Report r = reports.get(index);

        if (event.isShiftClick()) {
            plugin.reports().handle(r.id(), mod.getName());
            mod.sendMessage(plugin.messages().prefixed("report-handled"));
            new ReportsGui(plugin, page).open(mod);
        } else if (event.isRightClick()) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(r.targetUuid());
            new PlayerActionsGui(plugin, target).open(mod);
        } else {
            Player target = Bukkit.getPlayer(r.targetUuid());
            if (target != null) { mod.teleport(target.getLocation()); mod.closeInventory(); }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests ReportsGuiTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: reports GUI"
```

---

## Task 7: Wire tool buttons into the GUIs

**Files:**
- Modify: `gui/PlayerActionsGui.java` (add Freeze, Invsee, EChestSee; move History to slot 23)
- Modify: `gui/PlayersGui.java` (add Vanish, Reports, Staff buttons)
- Test: `gui/PlayerActionsGuiToolsTest.java`, `gui/PlayersGuiButtonsTest.java`

- [ ] **Step 1: Update `gui/PlayerActionsGui.java` slot constants and items**

Change the slot constants line to:

```java
private static final int IPBAN = 19, FREEZE = 20, INVSEE = 21, ECHEST = 22, HISTORY = 23, BACK = 36, CLOSE = 44;
```

In the constructor, where `IPBAN` and `HISTORY` items are set, replace that region so the new buttons render (Freeze/Invsee/EChestSee only when the target is online):

```java
if (target.isOnline()) {
    inventory.setItem(IPBAN, Items.button(Material.IRON_BARS, Component.text("IP-Ban"), List.of()));
    boolean frozen = plugin.freeze().isFrozen(target.getUniqueId());
    inventory.setItem(FREEZE, Items.button(Material.ICE, Component.text(frozen ? "Unfreeze" : "Freeze"), List.of()));
    inventory.setItem(INVSEE, Items.button(Material.CHEST, Component.text("View inventory"), List.of()));
    inventory.setItem(ECHEST, Items.button(Material.ENDER_CHEST, Component.text("View ender chest"), List.of()));
}
inventory.setItem(HISTORY, Items.button(Material.WRITABLE_BOOK, Component.text("History"), List.of()));
```

In `onClick`, add cases (and keep the existing ones; `HISTORY` slot value changed automatically via the constant):

```java
case FREEZE -> {
    Player online = target.getPlayer();
    if (online != null) {
        boolean nowFrozen = plugin.freeze().toggle(target.getUniqueId());
        if (nowFrozen) online.sendMessage(plugin.messages().prefixed("you-are-frozen"));
        mod.sendMessage(plugin.messages().prefixed(nowFrozen ? "freeze-frozen" : "freeze-unfrozen", "player", name()));
        mod.closeInventory();
    }
}
case INVSEE -> {
    Player online = target.getPlayer();
    if (online != null) mod.openInventory(online.getInventory());
}
case ECHEST -> {
    Player online = target.getPlayer();
    if (online != null) mod.openInventory(online.getEnderChest());
}
```

> Note: `mod.openInventory(online.getInventory())` and `online.getEnderChest()` open the target's REAL containers — these are intentionally NOT `Gui` holders, so `GuiListener` does not cancel clicks and the moderator can move/take/add items live.

- [ ] **Step 2: Write `gui/PlayerActionsGuiToolsTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayerActionsGuiToolsTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void freezeButtonTogglesFreeze() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayerMock target = server.addPlayer("Suspect");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 20); // Freeze
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(plugin.freeze().isFrozen(target.getUniqueId()));
    }

    @Test void invseeOpensTargetInventory() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayerMock target = server.addPlayer("Suspect");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 21); // Invsee
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertSame(target.getInventory(), mod.getOpenInventory().getTopInventory(),
            "moderator is now viewing the target's real inventory");
    }
}
```

> **Note:** If `assertSame` on the inventory identity doesn't hold under MockBukkit, assert instead that `mod.getOpenInventory().getTopInventory()` is not a `Gui` holder and has the target inventory's size/contents. Keep the freeze test as-is.

- [ ] **Step 3: Update `gui/PlayersGui.java` to add Vanish, Reports, Staff buttons**

Change the bottom-row constants:

```java
private static final int PREV = 45, REPORTS = 47, STAFF = 48, VANISH = 49, CLOSE = 52, NEXT = 53;
```

In the constructor, after the heads loop and before `fillEmpty()`, replace the old nav block (`PREV`/`CLOSE`/`NEXT` items) with:

```java
if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous"), List.of()));
inventory.setItem(REPORTS, Items.button(Material.BOOK, Component.text("Reports"),
    List.of(Component.text("Open: " + plugin.reports().open().size()))));
inventory.setItem(STAFF, Items.button(Material.NETHER_STAR, Component.text("Toggle staff chat"), List.of()));
inventory.setItem(VANISH, Items.button(Material.ENDER_EYE, Component.text("Toggle vanish"), List.of()));
inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
if (from + PAGE_SIZE < players.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next"), List.of()));
```

In `onClick`, handle the new buttons (keep the head-click logic; note `CLOSE` moved to 52):

```java
if (slot == PREV) { new PlayersGui(plugin, page - 1).open(mod); return; }
if (slot == NEXT) { new PlayersGui(plugin, page + 1).open(mod); return; }
if (slot == CLOSE) { mod.closeInventory(); return; }
if (slot == REPORTS) { new ReportsGui(plugin, 0).open(mod); return; }
if (slot == STAFF) {
    boolean on = plugin.staffChat().toggle(mod.getUniqueId());
    mod.sendMessage(plugin.messages().prefixed(on ? "staffchat-on" : "staffchat-off"));
    return;
}
if (slot == VANISH) {
    boolean vanished = plugin.vanish().toggle(mod);
    mod.sendMessage(plugin.messages().prefixed(vanished ? "vanish-on" : "vanish-off"));
    return;
}
int index = page * PAGE_SIZE + slot;
if (slot >= 0 && slot < PAGE_SIZE && index < players.size()) {
    new PlayerActionsGui(plugin, players.get(index)).open(mod);
}
```

- [ ] **Step 4: Add the vanish message keys to `messages.yml`**

```yaml
vanish-on: "<#60A5FA>You are now vanished."
vanish-off: "<#60A5FA>You are now visible."
```

- [ ] **Step 5: Write `gui/PlayersGuiButtonsTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayersGuiButtonsTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void vanishButtonTogglesVanish() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = new PlayersGui(plugin, 0);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 49); // Vanish
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(plugin.vanish().isVanished(mod.getUniqueId()));
    }

    @Test void reportsButtonOpensReportsGui() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = new PlayersGui(plugin, 0);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 47); // Reports
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(ReportsGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
```

- [ ] **Step 6: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All prior tests plus the new ones pass.

- [ ] **Step 7: Manual in-game smoke test**

1. `/sentinel` → click Vanish (slot 49) → you disappear for non-OPs; click again to reappear.
2. Open a player → Freeze → they cannot move; Unfreeze restores it.
3. Open a player → View inventory → you can move their items live; same for ender chest.
4. As a normal player run `/report <staff> testing` → OPs get the alert; `/sentinel` → Reports → shift-click marks it handled.
5. `/sc` toggles staff chat; your normal chat now only reaches OPs; `/sc hello` sends one message.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: wire freeze, invsee, echest, vanish, reports, and staff-chat buttons into GUIs"
```

---

## Self-Review Notes (plan vs. spec)

- **Spec coverage (Plan 3 scope):** Reports (`/report`, persistence, staff alert, Reports GUI with teleport/actions/handle) ✓ (Tasks 1,2,6,7); Staff chat (`/sc` toggle + message, chat routing) ✓ (Task 3, button in Task 7); Freeze (toggle + movement block) ✓ (Task 4, button in Task 7); Vanish (self-toggle + visibility, join handling) ✓ (Task 5, button in Task 7); Invsee + EChestSee live-editable ✓ (Task 7); Players-GUI Vanish/Reports/Staff buttons ✓ (Task 7); Player-Actions Freeze/Invsee/EChest ✓ (Task 7). Auto-updater remains for Plan 4.
- **Click-safety:** Invsee/EChestSee deliberately open the target's REAL inventory (not a `Gui` holder) so `GuiListener` does NOT cancel — that is the intended live-edit behavior, and it does not weaken theft protection for the menu GUIs (those still cancel).
- **Type consistency:** new accessors on `Sentinel`: `reports()`, `staffChat()`, `freeze()`, `vanish()`. Manager toggle methods all return "new state" booleans (`FreezeManager.toggle`, `VanishManager.toggle`, `StaffChatManager.toggle`). `ReportManager.file(CommandSender, UUID, String, String)`, `open()`, `handle(long, String)` used consistently across command, GUI, and tests.
- **Slot changes:** PlayerActionsGui History moved 22 → 23 to make room for Freeze(20)/Invsee(21)/EChest(22); PlayersGui Close moved 49 → 52 to free slot 49 for Vanish (matching the spec's bottom row). Tests that referenced the old History/Close slots from Plan 2 are not affected (Plan 2 tests click Ban=10 and heads, not those slots) — verify the Plan 2 `PlayerActionsGuiTest` and `PlayersGuiTest` still pass after the slot edits.
- **Testing caveats:** `VanishManagerTest.canSee` and `PlayerActionsGuiToolsTest` inventory-identity assertions may need adapting to MockBukkit behavior — flagged inline.
```
