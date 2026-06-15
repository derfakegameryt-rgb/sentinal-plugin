# Sentinel — Plan 10: Management

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Server-management tooling: a **maintenance mode** that locks out non-OPs, **broadcast + auto-announcements**, a **scheduled restart** with countdown, and **playtime tracking** with a leaderboard.

**Architecture:** A `MaintenanceManager` (config-backed flag) blocks non-OP logins and rewrites the server-list MOTD. An `AutoAnnouncer` cycles config messages on a timer; `/broadcast` sends one-offs. A `RestartManager` runs a countdown then `Bukkit.shutdown()`. Playtime adds a `playtime` column to `players`, tracked by session in `PlayerDirectory`, shown in a `StatsGui`.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0). All new commands are OP-gated via `sentinel.use`.

---

## Task 1: Maintenance mode

**Files:**
- Modify: `config.yml`, `messages.yml`, `listener/LoginListener.java`, `Sentinel.java`, `plugin.yml`
- Create: `manager/MaintenanceManager.java`, `listener/ServerPingListener.java`, `command/MaintenanceCommand.java`
- Test: `manager/MaintenanceManagerTest.java`

- [ ] **Step 1: Config + messages**

config.yml:
```yaml
maintenance:
  enabled: false
  kick-message: "The server is under maintenance. Please come back later."
  motd: "&cUnder maintenance"
```
messages.yml:
```yaml
maintenance-on: "<#60A5FA>Maintenance mode enabled — non-operators are locked out."
maintenance-off: "<#60A5FA>Maintenance mode disabled."
```

- [ ] **Step 2: `manager/MaintenanceManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;

public final class MaintenanceManager {
    private final Sentinel plugin;
    public MaintenanceManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isEnabled() { return plugin.getConfig().getBoolean("maintenance.enabled", false); }

    public void setEnabled(boolean on) {
        plugin.getConfig().set("maintenance.enabled", on);
        plugin.saveConfig();
    }

    public String kickMessage() {
        return plugin.getConfig().getString("maintenance.kick-message", "The server is under maintenance.");
    }

    public String motd() { return plugin.getConfig().getString("maintenance.motd", "Under maintenance"); }
}
```

- [ ] **Step 3: Block non-OP logins in `listener/LoginListener.java`**

At the top of `onPreLogin`, AFTER recording the player but BEFORE the ban checks, add:

```java
if (plugin.maintenance().isEnabled() && !org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).isOp()) {
    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
        net.kyori.adventure.text.Component.text(plugin.maintenance().kickMessage()));
    return;
}
```

- [ ] **Step 4: `listener/ServerPingListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

public final class ServerPingListener implements Listener {
    private final Sentinel plugin;
    public ServerPingListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (plugin.maintenance().isEnabled())
            event.motd(MiniMessage.miniMessage().deserialize(plugin.maintenance().motd()));
    }
}
```

> If `ServerListPingEvent#motd(Component)` isn't available on this API, use the legacy `event.setMotd(String)` with a section-sign-translated string. Keep the maintenance check.

- [ ] **Step 5: `command/MaintenanceCommand.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class MaintenanceCommand implements CommandExecutor {
    private final Sentinel plugin;
    public MaintenanceCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        boolean on = args.length == 0 ? !plugin.maintenance().isEnabled() : args[0].equalsIgnoreCase("on");
        plugin.maintenance().setEnabled(on);
        if (on) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (!p.isOp()) p.kick(Component.text(plugin.maintenance().kickMessage()));
        }
        sender.sendMessage(plugin.messages().prefixed(on ? "maintenance-on" : "maintenance-off"));
        return true;
    }
}
```

- [ ] **Step 6: Wire in `plugin.yml` + `Sentinel.java`**

plugin.yml: `  maintenance: { description: Toggle maintenance mode, permission: sentinel.use }`
Sentinel.java: build `maintenanceManager`, add `maintenance()` getter, register `ServerPingListener`, set `getCommand("maintenance").setExecutor(new MaintenanceCommand(this))`.

- [ ] **Step 7: Test `manager/MaintenanceManagerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void toggleEnables() {
        assertFalse(plugin.maintenance().isEnabled());
        plugin.maintenance().setEnabled(true);
        assertTrue(plugin.maintenance().isEnabled());
    }
}
```

- [ ] **Step 8: Run tests + commit** — `git commit -m "feat: maintenance mode"`

---

## Task 2: Broadcast & auto-announcements

**Files:**
- Modify: `config.yml`, `Sentinel.java`, `plugin.yml`
- Create: `command/BroadcastCommand.java`, `manager/AutoAnnouncer.java`
- Test: `manager/AutoAnnouncerTest.java`

- [ ] **Step 1: Config**

```yaml
announcements:
  enabled: true
  interval-seconds: 300
  prefix: "<#3B82F6>Info <dark_gray>»</dark_gray> "
  messages:
    - "<gray>Read the rules with <white>/rules</white>."
    - "<gray>Need help? Use <white>/report</white> for staff."
```

- [ ] **Step 2: `manager/AutoAnnouncer.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.util.List;

public final class AutoAnnouncer {
    private final Sentinel plugin;
    private int index = 0;

    public AutoAnnouncer(Sentinel plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("announcements.enabled", true)) return;
        long ticks = Math.max(20, plugin.getConfig().getLong("announcements.interval-seconds", 300)) * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::announceNext, ticks, ticks);
    }

    /** Broadcasts the next configured message (round-robin). Returns the raw line, or null if none. */
    public String announceNext() {
        List<String> messages = plugin.getConfig().getStringList("announcements.messages");
        if (messages.isEmpty()) return null;
        String prefix = plugin.getConfig().getString("announcements.prefix", "");
        String line = messages.get(index % messages.size());
        index++;
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(prefix + line));
        return line;
    }
}
```

- [ ] **Step 3: `command/BroadcastCommand.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class BroadcastCommand implements CommandExecutor {
    private final Sentinel plugin;
    public BroadcastCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 0) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/broadcast <message>")); return true; }
        String prefix = plugin.getConfig().getString("announcements.prefix", "");
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(prefix + String.join(" ", args)));
        return true;
    }
}
```

- [ ] **Step 4: Wire** — plugin.yml `broadcast: { description: Broadcast a message, aliases: [bc], permission: sentinel.use }`; Sentinel.java: build `autoAnnouncer = new AutoAnnouncer(this); autoAnnouncer.start();`, register `/broadcast` executor.

- [ ] **Step 5: Test `manager/AutoAnnouncerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class AutoAnnouncerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void cyclesThroughMessages() {
        plugin.getConfig().set("announcements.messages", java.util.List.of("one", "two"));
        AutoAnnouncer a = new AutoAnnouncer(plugin);
        assertEquals("one", a.announceNext());
        assertEquals("two", a.announceNext());
        assertEquals("one", a.announceNext()); // round-robin
    }

    @Test void emptyMessagesIsNoOp() {
        plugin.getConfig().set("announcements.messages", java.util.List.of());
        assertNull(new AutoAnnouncer(plugin).announceNext());
    }
}
```

- [ ] **Step 6: Run tests + commit** — `git commit -m "feat: broadcast and auto-announcements"`

---

## Task 3: Scheduled restart with countdown

**Files:**
- Modify: `messages.yml`, `Sentinel.java`, `plugin.yml`
- Create: `manager/RestartManager.java`, `command/RestartCommand.java`
- Test: `util/DurationParser` already covers parsing; add `manager/RestartManagerTest.java` for the countdown-tick selection.

- [ ] **Step 1: Messages**

```yaml
restart-warning: "<red>Server restarting in <white><time></white>."
restart-scheduled: "<#60A5FA>Restart scheduled in <white><time></white>."
restart-cancelled: "<#60A5FA>Restart cancelled."
```

- [ ] **Step 2: `manager/RestartManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public final class RestartManager {
    // seconds-remaining marks at which players are warned
    private static final List<Integer> MARKS = List.of(600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1);

    private final Sentinel plugin;
    private BukkitTask task;
    private int remaining;

    public RestartManager(Sentinel plugin) { this.plugin = plugin; }

    /** True if a given seconds-remaining value should trigger a warning broadcast. */
    public boolean isWarnTick(int secondsRemaining) { return MARKS.contains(secondsRemaining); }

    public void schedule(int seconds) {
        cancel();
        this.remaining = seconds;
        warn(remaining);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            remaining--;
            if (remaining <= 0) { Bukkit.shutdown(); return; }
            if (isWarnTick(remaining)) warn(remaining);
        }, 20L, 20L);
    }

    public boolean cancel() {
        if (task == null) return false;
        task.cancel(); task = null;
        Bukkit.broadcast(plugin.messages().prefixed("restart-cancelled"));
        return true;
    }

    private void warn(int seconds) {
        Bukkit.broadcast(plugin.messages().prefixed("restart-warning", "time", human(seconds)));
    }

    static String human(int seconds) {
        if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
}
```

- [ ] **Step 3: `command/RestartCommand.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.DurationParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class RestartCommand implements CommandExecutor {
    private final Sentinel plugin;
    public RestartCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!plugin.restart().cancel()) sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/restart <duration>|cancel"));
            return true;
        }
        if (args.length != 1) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/restart <duration>|cancel")); return true; }
        long ms;
        try { ms = DurationParser.parse(args[0]); }
        catch (IllegalArgumentException e) { sender.sendMessage(plugin.messages().prefixed("bad-duration")); return true; }
        int seconds = (int) Math.max(1, ms / 1000);
        plugin.restart().schedule(seconds);
        sender.sendMessage(plugin.messages().prefixed("restart-scheduled", "time", de.derfakegamer.sentinel.manager.RestartManager.human(seconds)));
        return true;
    }
}
```

- [ ] **Step 4: Wire** — plugin.yml `restart: { description: Schedule a server restart, permission: sentinel.use }`; Sentinel.java: build `restartManager`, add `restart()` getter, register executor.

- [ ] **Step 5: Test `manager/RestartManagerTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class RestartManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void warnTicksAtKnownMarks() {
        RestartManager r = new RestartManager(plugin);
        assertTrue(r.isWarnTick(60));
        assertTrue(r.isWarnTick(5));
        assertFalse(r.isWarnTick(42));
    }

    @Test void humanFormat() {
        assertEquals("5s", RestartManager.human(5));
        assertEquals("2m 5s", RestartManager.human(125));
    }
}
```

- [ ] **Step 6: Run tests + commit** — `git commit -m "feat: scheduled restart with countdown"`

---

## Task 4: Playtime & stats

**Files:**
- Modify: `storage/Database.java`, `storage/PlayerDao.java`, `manager/PlayerDirectory.java`, `listener/JoinQuitListener.java`, `gui/AdminPanelGui.java`, `Sentinel.java`, `plugin.yml`, `messages.yml`
- Create: `gui/StatsGui.java`, `command/PlaytimeCommand.java`
- Test: `storage/PlaytimeDaoTest.java`

- [ ] **Step 1: Add the `playtime` column (safe migration) in `Database.createSchema()`**

After the players table is created, add a defensive column-add (ignored if it already exists):

```java
try (Statement alter = connection.createStatement()) {
    alter.executeUpdate("ALTER TABLE players ADD COLUMN playtime INTEGER NOT NULL DEFAULT 0");
} catch (SQLException ignored) { /* column already exists */ }
```

- [ ] **Step 2: `PlayerDao` playtime methods**

```java
public void addPlaytime(java.util.UUID uuid, long ms) {
    synchronized (db) {
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE players SET playtime = playtime + ? WHERE uuid=?")) {
            ps.setLong(1, ms); ps.setString(2, uuid.toString()); ps.executeUpdate();
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }
}

public long playtime(java.util.UUID uuid) {
    synchronized (db) {
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement("SELECT playtime FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }
}

/** Top players by playtime: list of {name, playtimeMs}. */
public java.util.List<de.derfakegamer.sentinel.model.PlayerRecord> topByPlaytime(int limit) {
    synchronized (db) {
        java.util.List<de.derfakegamer.sentinel.model.PlayerRecord> out = new java.util.ArrayList<>();
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                "SELECT * FROM players ORDER BY playtime DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (java.sql.ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        return out;
    }
}
```

`map(rs)` currently builds `PlayerRecord` from uuid/name/last_ip/first_seen/last_seen — leave it; playtime is read separately via `playtime(uuid)` in the GUI (or extend PlayerRecord with a playtime field — simpler to read separately).

- [ ] **Step 3: Session tracking in `PlayerDirectory`**

```java
private final java.util.Map<java.util.UUID, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();

public void startSession(java.util.UUID uuid) { sessions.put(uuid, System.currentTimeMillis()); }

public void endSession(java.util.UUID uuid) {
    Long start = sessions.remove(uuid);
    if (start != null) dao.addPlaytime(uuid, System.currentTimeMillis() - start);
}

public long playtime(java.util.UUID uuid) { return dao.playtime(uuid); }
public java.util.List<de.derfakegamer.sentinel.model.PlayerRecord> topByPlaytime(int limit) { return dao.topByPlaytime(limit); }
```

- [ ] **Step 4: Track sessions in `JoinQuitListener`**

`onJoin`: `plugin.players().startSession(event.getPlayer().getUniqueId());`
`onQuit`: `plugin.players().endSession(event.getPlayer().getUniqueId());`
(Both in addition to the existing vanish/staffchat handling.)

- [ ] **Step 5: `command/PlaytimeCommand.java`** — `/playtime [player]`

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

public final class PlaytimeCommand implements CommandExecutor {
    private final Sentinel plugin;
    public PlaytimeCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        OfflinePlayer target = args.length >= 1 ? Bukkit.getOfflinePlayer(args[0])
            : (sender instanceof Player p ? p : null);
        if (target == null) { sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/playtime <player>")); return true; }
        long ms = plugin.players().playtime(target.getUniqueId());
        sender.sendMessage(plugin.messages().prefixed("playtime", "player",
            args.length >= 1 ? args[0] : sender.getName(), "time", format(ms)));
        return true;
    }

    static String format(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h + "h " + m + "m";
    }
}
```

messages.yml: `playtime: "<#60A5FA><player></#60A5FA> <gray>has played <white><time></white>."`

- [ ] **Step 6: `gui/StatsGui.java`** — leaderboard

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
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class StatsGui extends Gui {
    private static final int BACK = 45, CLOSE = 53;

    public StatsGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-stats-title"));
        List<PlayerRecord> top = plugin.players().topByPlaytime(45);
        for (int i = 0; i < top.size() && i < 45; i++) {
            PlayerRecord r = top.get(i);
            long ms = plugin.players().playtime(r.uuid());
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(r.uuid()),
                Component.text("#" + (i + 1) + " " + r.name(), NamedTextColor.AQUA),
                List.of(Component.text("Playtime: " + (ms / 3600000) + "h " + (ms / 60000 % 60) + "m", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (event.getRawSlot() == BACK) new AdminPanelGui(plugin).open(p);
        else if (event.getRawSlot() == CLOSE) p.closeInventory();
    }
}
```

messages.yml: `gui-stats-title: "<#3B82F6>Sentinel · Playtime"`.

- [ ] **Step 7: Add a Stats button to `AdminPanelGui` (slot 16) → StatsGui**

Add constant `STATS = 16`; render `button(Material.CLOCK, "Playtime", "Top players by playtime")`; click `case STATS -> new StatsGui(plugin).open(p);`. (Slot 16 is free in the 27-slot panel.)

- [ ] **Step 8: Wire** — plugin.yml `playtime: { description: Show playtime, permission: sentinel.use }`; Sentinel.java: register `/playtime` executor. (PlayerDirectory + JoinQuitListener already wired from Plan 5.)

- [ ] **Step 9: Test `storage/PlaytimeDaoTest.java`**

```java
package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlaytimeDaoTest {
    Database db; PlayerDao dao; File tmp;
    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp); dao = new PlayerDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void accumulatesAndRanks() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        dao.upsert(a, "A", "1.1.1.1", 1);
        dao.upsert(b, "B", "2.2.2.2", 1);
        dao.addPlaytime(a, 1000);
        dao.addPlaytime(a, 4000);   // A total 5000
        dao.addPlaytime(b, 2000);   // B total 2000
        assertEquals(5000, dao.playtime(a));
        assertEquals("A", dao.topByPlaytime(2).get(0).name()); // A ranks first
    }
}
```

- [ ] **Step 10: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green. Shaded jar produced.

- [ ] **Step 11: Manual smoke test**

1. `/maintenance` → non-OPs are kicked and can't join; server list shows the maintenance MOTD; `/maintenance off` restores.
2. `/broadcast hello` → everyone sees it; wait for an auto-announcement to cycle.
3. `/restart 1m` → countdown warnings, then the server stops (and the restart script + pending auto-update apply); `/restart cancel` aborts.
4. Play for a bit, rejoin → `/playtime` shows accumulated time; Admin Panel → Playtime → leaderboard.

- [ ] **Step 12: Commit** — `git commit -m "feat: playtime tracking and stats leaderboard"`

---

## Self-Review Notes (plan vs. requirements)

- **Maintenance mode** ✓ (Task 1): config flag (survives restart), non-OP login block, server-list MOTD, `/maintenance` toggle that kicks non-OPs.
- **Broadcast & auto-announcements** ✓ (Task 2): `/broadcast` + round-robin timed announcements from config.
- **Scheduled restart** ✓ (Task 3): `/restart <duration>` countdown → `Bukkit.shutdown()`, `/restart cancel`.
- **Playtime & stats** ✓ (Task 4): `playtime` column, session tracking on join/quit, `/playtime`, leaderboard GUI from the Admin Panel.
- **Type consistency:** new `Sentinel` accessors `maintenance()`, `restart()`; `AutoAnnouncer`/`AutoAnnouncer.start()`; `PlayerDirectory.startSession/endSession/playtime/topByPlaytime`. `AdminPanelGui` slot `STATS = 16` is free.
- **OP gating:** all new commands declared with `permission: sentinel.use`.
- **Testing caveats:** restart shutdown, login block, MOTD, and broadcasting are server-side; tests cover the manager logic (toggle, round-robin, warn-tick marks, human formatting) and the playtime DAO.
```
