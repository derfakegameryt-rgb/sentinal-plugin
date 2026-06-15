# Sentinel — Plan 11: Owner System, Secret Orbital Access & Scheduled Strikes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the orbital strike a secret, owner-controlled feature. Only the configured owner (`DerFakeGamer`) sees `/sn owner`; everyone else gets an unknown-command error, no tab-completion, and the command is filtered from the console. From the owner panel the owner manages an allowlist of players who may use the orbital strike, changes the keypad code (stored secretly in the DB, not config.yml), and reviews scheduled strikes. Coordinate-mode strikes can be **scheduled** (e.g. in 10m / 2h), survive a restart, and be cancelled. The keypad and GUIs get a visual refresh.

**Architecture:** An `OwnerManager` resolves the single owner from config. A DB `settings` table holds the secret orbital `code`; an `orbital_allowed` table holds the allowlist; a `scheduled_strikes` table persists timers. `OrbitalAccess` fronts them. `/orbitalstrike` is hidden behind permission `sentinel.orbital` (`default: false`), granted at join only to allowed players via a `PermissionAttachment`. `/sn owner` is gated in `SentinelCommand`. `ScheduledStrikeManager` reschedules persisted strikes on enable.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0).

> **Keypad textures:** digit-textured heads need base64 from head databases (not fetchable here). Task 6 ships a clean themed keypad using single-theme heads + the big digit in the name, via an `Items.numberButton(int)` helper so real digit textures can be swapped in later as a one-liner.

---

## Task 1: Settings store, owner, orbital access

**Files:**
- Modify: `storage/Database.java`, `config.yml`
- Create: `storage/SettingsDao.java`, `storage/OrbitalAllowDao.java`, `manager/OwnerManager.java`, `manager/OrbitalAccess.java`
- Modify: `Sentinel.java`
- Test: `storage/SettingsDaoTest.java`, `manager/OrbitalAccessTest.java`

- [ ] **Step 1: Tables in `Database.createSchema()`**

```java
st.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS orbital_allowed (
      uuid TEXT PRIMARY KEY,
      name TEXT NOT NULL
    )""");
```

- [ ] **Step 2: Config — owner only (the code is NOT in config)**

config.yml:
```yaml
owner: "DerFakeGamer"   # the single owner: a player name or a UUID
```

- [ ] **Step 3: `storage/SettingsDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import java.sql.*;

public final class SettingsDao {
    private final Database db;
    public SettingsDao(Database db) { this.db = db; }

    public String get(String key, String def) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT value FROM settings WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : def; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public void set(String key, String value) {
        synchronized (db) {
            String sql = "INSERT INTO settings (key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }
}
```

- [ ] **Step 4: `storage/OrbitalAllowDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class OrbitalAllowDao {
    private final Database db;
    public OrbitalAllowDao(Database db) { this.db = db; }

    public void add(UUID uuid, String name) {
        synchronized (db) {
            String sql = "INSERT INTO orbital_allowed (uuid,name) VALUES (?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString()); ps.setString(2, name); ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public void remove(UUID uuid) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM orbital_allowed WHERE uuid=?")) {
                ps.setString(1, uuid.toString()); ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    public boolean contains(UUID uuid) {
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT 1 FROM orbital_allowed WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    /** uuid -> name, insertion order. */
    public Map<UUID, String> all() {
        synchronized (db) {
            Map<UUID, String> out = new LinkedHashMap<>();
            try (PreparedStatement ps = db.connection().prepareStatement("SELECT uuid,name FROM orbital_allowed");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(UUID.fromString(rs.getString("uuid")), rs.getString("name"));
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
    }
}
```

- [ ] **Step 5: `manager/OwnerManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public final class OwnerManager {
    private final Sentinel plugin;
    public OwnerManager(Sentinel plugin) { this.plugin = plugin; }

    public boolean isOwner(CommandSender sender) {
        if (!(sender instanceof OfflinePlayer p)) return false;
        String configured = plugin.getConfig().getString("owner", "DerFakeGamer");
        if (configured == null || configured.isBlank()) return false;
        try {
            return p.getUniqueId().equals(UUID.fromString(configured)); // owner is a UUID
        } catch (IllegalArgumentException notUuid) {
            return p.getName() != null && p.getName().equalsIgnoreCase(configured); // owner is a name
        }
    }
}
```

- [ ] **Step 6: `manager/OrbitalAccess.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.OrbitalAllowDao;
import de.derfakegamer.sentinel.storage.SettingsDao;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public final class OrbitalAccess {
    private static final String CODE_KEY = "orbital.code";
    private static final String DEFAULT_CODE = "2584";

    private final Sentinel plugin;
    private final SettingsDao settings;
    private final OrbitalAllowDao allow;

    public OrbitalAccess(Sentinel plugin, SettingsDao settings, OrbitalAllowDao allow) {
        this.plugin = plugin; this.settings = settings; this.allow = allow;
    }

    public String code() { return settings.get(CODE_KEY, DEFAULT_CODE); }
    public void setCode(String code) { settings.set(CODE_KEY, code); }

    public boolean isAllowed(Player player) {
        return plugin.owner().isOwner(player) || allow.contains(player.getUniqueId());
    }

    public boolean isAllowed(UUID uuid) { return allow.contains(uuid); }

    public void add(UUID uuid, String name) { allow.add(uuid, name); }
    public void remove(UUID uuid) { allow.remove(uuid); }
    public Map<UUID, String> list() { return allow.all(); }
}
```

- [ ] **Step 7: Wire `Sentinel.java`**

```java
// fields
private de.derfakegamer.sentinel.manager.OwnerManager ownerManager;
private de.derfakegamer.sentinel.manager.OrbitalAccess orbitalAccess;

// in onEnable() after database:
this.ownerManager = new de.derfakegamer.sentinel.manager.OwnerManager(this);
this.orbitalAccess = new de.derfakegamer.sentinel.manager.OrbitalAccess(this,
    new de.derfakegamer.sentinel.storage.SettingsDao(database),
    new de.derfakegamer.sentinel.storage.OrbitalAllowDao(database));

// getters
public de.derfakegamer.sentinel.manager.OwnerManager owner() { return ownerManager; }
public de.derfakegamer.sentinel.manager.OrbitalAccess orbitalAccess() { return orbitalAccess; }
```

- [ ] **Step 8: Tests**

`SettingsDaoTest.java`: insert/get default, set then get, upsert overwrites.
`OrbitalAccessTest.java` (MockBukkit):

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalAccessTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void ownerIsAlwaysAllowed() {
        plugin.getConfig().set("owner", "Boss");
        PlayerMock boss = server.addPlayer("Boss");
        assertTrue(plugin.orbitalAccess().isAllowed(boss));
    }

    @Test void allowlistGrantsAndRevokes() {
        PlayerMock p = server.addPlayer("Helper");
        assertFalse(plugin.orbitalAccess().isAllowed(p));
        plugin.orbitalAccess().add(p.getUniqueId(), "Helper");
        assertTrue(plugin.orbitalAccess().isAllowed(p.getUniqueId()));
        plugin.orbitalAccess().remove(p.getUniqueId());
        assertFalse(plugin.orbitalAccess().isAllowed(p.getUniqueId()));
    }

    @Test void codeDefaultsThenChanges() {
        assertEquals("2584", plugin.orbitalAccess().code());
        plugin.orbitalAccess().setCode("9999");
        assertEquals("9999", plugin.orbitalAccess().code());
    }
}
```

- [ ] **Step 9: Run tests + commit** — `git commit -m "feat: owner manager, orbital access store (settings + allowlist)"`

---

## Task 2: Make the orbital strike secret + allowlist-gated

**Files:**
- Modify: `plugin.yml`, `command/OrbitalStrikeCommand.java`, `listener/OrbitalRodListener.java`, `gui/OrbitalCodeGui.java`, `Sentinel.java`, `messages.yml`
- Create: `listener/OrbitalAccessListener.java`
- Test: extend `OrbitalCodeGuiTest` / add `manager/OrbitalAccessTest` cases (done in Task 1)

- [ ] **Step 1: Hide `/orbitalstrike` behind a default-false permission**

plugin.yml: change the orbitalstrike command to `orbitalstrike: { description: "", permission: sentinel.orbital }` and add to `permissions:`:
```yaml
  sentinel.orbital:
    description: Use the orbital strike
    default: false
```
(With `default: false`, the command is absent from every non-granted player's command tree — hidden from tab + "Unknown command" when typed.)

- [ ] **Step 2: Grant the permission at join to allowed players — `listener/OrbitalAccessListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrbitalAccessListener implements Listener {
    private final Sentinel plugin;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public OrbitalAccessListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { apply(event.getPlayer()); }

    /** Grants or revokes the orbital permission for a player based on the current allowlist. */
    public void apply(Player player) {
        boolean allowed = plugin.orbitalAccess().isAllowed(player);
        PermissionAttachment att = attachments.get(player.getUniqueId());
        if (allowed && att == null) {
            PermissionAttachment a = player.addAttachment(plugin);
            a.setPermission("sentinel.orbital", true);
            attachments.put(player.getUniqueId(), a);
        } else if (!allowed && att != null) {
            player.removeAttachment(att);
            attachments.remove(player.getUniqueId());
        }
    }
}
```

Expose it on `Sentinel` so the owner panel can call `apply` after add/remove:
```java
private de.derfakegamer.sentinel.listener.OrbitalAccessListener orbitalAccessListener;
// onEnable: this.orbitalAccessListener = new OrbitalAccessListener(this); register it; also apply to already-online players in a loop.
public de.derfakegamer.sentinel.listener.OrbitalAccessListener orbitalAccessListener() { return orbitalAccessListener; }
```

- [ ] **Step 3: Gate the command + rod**

`OrbitalStrikeCommand.onCommand`: replace the `isOp()` check with:
```java
if (!(sender instanceof Player p) || !plugin.orbitalAccess().isAllowed(p)) {
    sender.sendMessage(net.kyori.adventure.text.Component.text("Unknown command. Type \"/help\" for help.",
        net.kyori.adventure.text.format.NamedTextColor.RED));
    return true;
}
new OrbitalCodeGui(plugin).open(p);
```

`OrbitalRodListener.onUse`: replace `if (!p.isOp()) return;` with `if (!plugin.orbitalAccess().isAllowed(p)) return;`.

`OrbitalCodeGui`: replace the hard-coded `CODE` with `plugin.orbitalAccess().code()` in the validation check.

- [ ] **Step 4: Run tests + commit**

```bash
./gradlew test --tests OrbitalCodeGuiTest --tests OrbitalAccessTest
git commit -m "feat: orbital strike restricted to a secret allowlist (hidden command)"
```

---

## Task 3: `/sn owner` — owner-only, hidden, console-filtered

**Files:**
- Modify: `command/SentinelCommand.java`, `util/OrbitalConsoleFilter.java`
- Test: `command/OwnerAccessTest.java`

- [ ] **Step 1: Handle the `owner` subcommand in `SentinelCommand`**

In `onCommand`, BEFORE the player-name lookup, add:
```java
if (args.length == 1 && args[0].equalsIgnoreCase("owner")) {
    if (plugin.owner().isOwner(sender) && sender instanceof org.bukkit.entity.Player p) {
        new de.derfakegamer.sentinel.gui.OwnerPanelGui(plugin).open(p);
    } else {
        sender.sendMessage(net.kyori.adventure.text.Component.text(
            "Unknown command. Type \"/help\" for help.", net.kyori.adventure.text.format.NamedTextColor.RED));
    }
    return true;
}
```

In `onTabComplete`, only suggest `owner` when `plugin.owner().isOwner(sender)`:
```java
if (plugin.owner().isOwner(sender)) opts.add("owner");
```
(Add it to the existing options list built for `/sentinel`.)

- [ ] **Step 2: Filter `/sn owner` + `/orbitalstrike` from the console**

Rename/extend `OrbitalConsoleFilter` to drop log lines containing any secret command. Change the match to:
```java
private static boolean secret(String msg) {
    if (msg == null) return false;
    String m = msg.toLowerCase();
    return m.contains("orbitalstrike") || m.contains("sn owner") || m.contains("sentinel owner");
}
```
and have every `filter(...)` overload return `Result.DENY` when `secret(formattedMessage)` is true.

- [ ] **Step 3: Test `command/OwnerAccessTest.java`**

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

class OwnerAccessTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void onlyOwnerSeesOwnerInTab() {
        plugin.getConfig().set("owner", "Boss");
        PlayerMock boss = server.addPlayer("Boss"); boss.setOp(true);
        PlayerMock other = server.addPlayer("Admin"); other.setOp(true);
        SentinelCommand cmd = new SentinelCommand(plugin);
        Command sentinel = server.getCommandMap().getCommand("sentinel");
        assertTrue(cmd.onTabComplete(boss, sentinel, "sentinel", new String[]{"ow"}).contains("owner"));
        assertFalse(cmd.onTabComplete(other, sentinel, "sentinel", new String[]{"ow"}).contains("owner"));
    }

    @Test void nonOwnerOwnerSubcommandDoesNotOpenPanel() {
        plugin.getConfig().set("owner", "Boss");
        PlayerMock other = server.addPlayer("Admin"); other.setOp(true);
        SentinelCommand cmd = new SentinelCommand(plugin);
        cmd.onCommand(other, server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"owner"});
        assertFalse(other.getOpenInventory().getTopInventory().getHolder() instanceof de.derfakegamer.sentinel.gui.OwnerPanelGui);
    }
}
```

> `OwnerPanelGui` is created in Task 4 — implement Tasks 3–5 together so it compiles, then run tests.

- [ ] **Step 4: Run tests + commit** — `git commit -m "feat: hidden owner-only /sn owner with console filtering"`

---

## Task 4: Owner panel GUI + orbital-users management + code change

**Files:**
- Create: `gui/OwnerPanelGui.java`, `gui/OrbitalUsersGui.java`
- Modify: `messages.yml`
- Test: `gui/OwnerPanelGuiTest.java`

- [ ] **Step 1: messages**

```yaml
gui-owner-title: "<#3B82F6>Sentinel · Owner Panel"
gui-orbital-users-title: "<#3B82F6>Sentinel · Orbital Users"
owner-enter-user: "<#60A5FA>Type the player's name to allow, or type <white>cancel<#60A5FA>."
owner-user-added: "<#60A5FA><player></#60A5FA> <gray>can now use the orbital strike."
owner-user-removed: "<#60A5FA><player></#60A5FA> <gray>can no longer use the orbital strike."
owner-enter-code: "<#60A5FA>Type the new 4-digit code, or type <white>cancel<#60A5FA>."
owner-code-changed: "<#60A5FA>Orbital strike code updated."
owner-bad-code: "<red>The code must be 4 digits."
```

- [ ] **Step 2: `gui/OwnerPanelGui.java`** — 27-slot menu: Orbital Users (11), Change Code (13), Scheduled Strikes (15), Close (26).

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class OwnerPanelGui extends Gui {
    private static final int USERS = 11, CODE = 13, SCHEDULED = 15, CLOSE = 26;

    public OwnerPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-owner-title"));
        inventory.setItem(USERS, button(Material.PLAYER_HEAD, "Orbital users", "Add or remove who may strike"));
        inventory.setItem(CODE, button(Material.TRIPWIRE_HOOK, "Change code", "Set a new keypad code"));
        inventory.setItem(SCHEDULED, button(Material.CLOCK, "Scheduled strikes", "Review / cancel timers"));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack button(Material m, String title, String hint) {
        return Items.button(m, Component.text(title, NamedTextColor.AQUA),
            List.of(Component.text(hint, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case USERS -> new OrbitalUsersGui(plugin).open(p);
            case SCHEDULED -> new ScheduledStrikesGui(plugin).open(p);
            case CODE -> {
                p.closeInventory();
                p.sendMessage(plugin.messages().prefixed("owner-enter-code"));
                plugin.chatInput().await(p.getUniqueId(), code -> {
                    if (!code.matches("\\d{4}")) { p.sendMessage(plugin.messages().prefixed("owner-bad-code")); return; }
                    plugin.orbitalAccess().setCode(code);
                    p.sendMessage(plugin.messages().prefixed("owner-code-changed"));
                });
            }
            case CLOSE -> p.closeInventory();
        }
    }
}
```

> References `ScheduledStrikesGui` (Task 5) — implement Tasks 4–5 together.

- [ ] **Step 3: `gui/OrbitalUsersGui.java`** — heads of allowed players (click to remove) + an Add button (slot 49) that chat-prompts a name.

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrbitalUsersGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int ADD = 49, BACK = 45, CLOSE = 53;

    private final List<Map.Entry<UUID, String>> users;

    public OrbitalUsersGui(Sentinel plugin) {
        super(plugin);
        this.users = new ArrayList<>(plugin.orbitalAccess().list().entrySet());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-orbital-users-title"));
        for (int i = 0; i < PAGE_SIZE && i < users.size(); i++) {
            var e = users.get(i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(e.getKey()),
                Component.text(e.getValue(), NamedTextColor.AQUA),
                List.of(Component.text("Click to remove", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))));
        }
        inventory.setItem(ADD, Items.button(Material.LIME_DYE, Component.text("Add player", NamedTextColor.GREEN),
            List.of(Component.text("Type a name in chat", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new OwnerPanelGui(plugin).open(p); return; }
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot == ADD) {
            p.closeInventory();
            p.sendMessage(plugin.messages().prefixed("owner-enter-user"));
            plugin.chatInput().await(p.getUniqueId(), name -> {
                var rec = plugin.players().byName(name);
                UUID id = rec != null ? rec.uuid() : Bukkit.getOfflinePlayer(name).getUniqueId();
                plugin.orbitalAccess().add(id, name);
                Player online = Bukkit.getPlayer(id);
                if (online != null) plugin.orbitalAccessListener().apply(online);
                p.sendMessage(plugin.messages().prefixed("owner-user-added", "player", name));
            });
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE && slot < users.size()) {
            var e = users.get(slot);
            plugin.orbitalAccess().remove(e.getKey());
            Player online = Bukkit.getPlayer(e.getKey());
            if (online != null) plugin.orbitalAccessListener().apply(online);
            p.sendMessage(plugin.messages().prefixed("owner-user-removed", "player", e.getValue()));
            new OrbitalUsersGui(plugin).open(p);
        }
    }
}
```

- [ ] **Step 4: `gui/OwnerPanelGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.event.inventory.InventoryClickEvent;
import static org.junit.jupiter.api.Assertions.*;

class OwnerPanelGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void usersButtonOpensUsersGui() {
        PlayerMock p = server.addPlayer("Boss");
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 11);
        gui.onClick(ev);
        assertInstanceOf(OrbitalUsersGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }
}
```

- [ ] **Step 5: Run tests + commit** — `git commit -m "feat: owner panel and orbital-users management GUI"`

---

## Task 5: Scheduled coordinate strikes (persistent, cancellable)

**Files:**
- Modify: `storage/Database.java`, `gui/OrbitalPayloadGui.java`, `Sentinel.java`, `messages.yml`
- Create: `model/ScheduledStrike.java`, `storage/ScheduledStrikeDao.java`, `manager/ScheduledStrikeManager.java`, `gui/OrbitalWhenGui.java`, `gui/ScheduledStrikesGui.java`
- Test: `storage/ScheduledStrikeDaoTest.java`

- [ ] **Step 1: Table**

```java
st.executeUpdate("""
    CREATE TABLE IF NOT EXISTS scheduled_strikes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      world TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL,
      payload TEXT NOT NULL, fire_at INTEGER NOT NULL
    )""");
```

- [ ] **Step 2: `model/ScheduledStrike.java`**

```java
package de.derfakegamer.sentinel.model;

public record ScheduledStrike(long id, String world, int x, int z, OrbitalPayload payload, long fireAt) {}
```

- [ ] **Step 3: `storage/ScheduledStrikeDao.java`** — insert / pending / delete (synchronized on db). Standard pattern; `pending()` returns all rows ordered by `fire_at`.

- [ ] **Step 4: `manager/ScheduledStrikeManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.model.ScheduledStrike;
import de.derfakegamer.sentinel.storage.ScheduledStrikeDao;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ScheduledStrikeManager {
    private final Sentinel plugin;
    private final ScheduledStrikeDao dao;
    private final Map<Long, Integer> tasks = new HashMap<>();

    public ScheduledStrikeManager(Sentinel plugin, ScheduledStrikeDao dao) { this.plugin = plugin; this.dao = dao; }

    public long schedule(World world, int x, int z, OrbitalPayload payload, long fireAt) {
        long id = dao.insert(world.getName(), x, z, payload.name(), fireAt);
        arm(new ScheduledStrike(id, world.getName(), x, z, payload, fireAt));
        return id;
    }

    /** On enable: re-arm all persisted strikes (firing immediately any that are already due). */
    public void rearmAll() {
        for (ScheduledStrike s : dao.pending()) arm(s);
    }

    public List<ScheduledStrike> pending() { return dao.pending(); }

    public boolean cancel(long id) {
        Integer task = tasks.remove(id);
        if (task != null) Bukkit.getScheduler().cancelTask(task);
        return dao.delete(id) > 0;
    }

    private void arm(ScheduledStrike s) {
        long delayMs = s.fireAt() - System.currentTimeMillis();
        long delayTicks = Math.max(1, delayMs / 50);
        int task = Bukkit.getScheduler().runTaskLater(plugin, () -> fire(s), delayTicks).getTaskId();
        tasks.put(s.id(), task);
    }

    private void fire(ScheduledStrike s) {
        tasks.remove(s.id());
        World world = Bukkit.getWorld(s.world());
        if (world != null) plugin.orbital().strike(world, s.x(), s.z(), s.payload());
        dao.delete(s.id());
    }
}
```

- [ ] **Step 5: Coordinate-mode "now or schedule" — `gui/OrbitalWhenGui.java`**

After the coordinate-mode payload is chosen, `OrbitalPayloadGui` opens `OrbitalWhenGui(plugin, world, x, z, payload)` instead of going straight to `ConfirmGui`. (Rod mode is unchanged — it still gives the rod.)

`OrbitalWhenGui` (27 slots): "Strike now" (11) → `ConfirmGui` whose action calls `plugin.orbital().strike(...)`; "Schedule" (15) → close, chat-prompt a delay (`DurationParser`), then `plugin.scheduledStrikes().schedule(world, x, z, payload, now + delay)` + a confirmation message; Close (26).

In `OrbitalPayloadGui.onClick`, the coordinate branch (`world != null`) changes to:
```java
new OrbitalWhenGui(plugin, world, x, z, payload).open(p);
```
and the rod branch (`world == null`) stays as the existing ConfirmGui-gives-rod path.

messages.yml:
```yaml
gui-orbital-when-title: "<#3B82F6>Sentinel · When"
orbital-enter-delay: "<#60A5FA>Type a delay (e.g. 10m, 2h), or type <white>cancel<#60A5FA>."
orbital-scheduled: "<#60A5FA>Strike scheduled in <white><time></white>."
gui-scheduled-title: "<#3B82F6>Sentinel · Scheduled Strikes"
scheduled-cancelled: "<#60A5FA>Scheduled strike cancelled."
```

- [ ] **Step 6: `gui/ScheduledStrikesGui.java`** — one item per pending strike (world/coords/payload + remaining time); click cancels it.

```java
// 54 slots; for each pending strike show a CLOCK item with lore
//   "World: <world>", "At: <x>,<z>", "Payload: <payload>", "Fires in: <human remaining>"
// onClick (cancel): plugin.scheduledStrikes().cancel(id); send "scheduled-cancelled"; reopen.
// BACK=45 -> OwnerPanelGui, CLOSE=53.
```
(Implement following the established list-GUI pattern; store the strike list as a field and map slot→id.)

- [ ] **Step 7: Wire `Sentinel.java`**

```java
this.scheduledStrikeManager = new ScheduledStrikeManager(this, new ScheduledStrikeDao(database));
this.scheduledStrikeManager.rearmAll();   // survive restarts
public ScheduledStrikeManager scheduledStrikes() { return scheduledStrikeManager; }
```

- [ ] **Step 8: Test `storage/ScheduledStrikeDaoTest.java`** — insert two, `pending()` returns both ordered by fire_at, `delete` removes one.

- [ ] **Step 9: Run tests + commit** — `git commit -m "feat: scheduled, restart-surviving, cancellable coordinate strikes"`

---

## Task 6: Keypad & GUI visual refresh

**Files:**
- Modify: `util/Items.java`, `gui/OrbitalCodeGui.java`
- Test: `gui/OrbitalCodeGuiTest.java` still green

- [ ] **Step 1: `Items.numberButton(int digit)`** — a clean, themed digit button (single-theme player head with the big digit in the display name). This is the one place to later swap in real digit-textured heads (set a base64 texture per digit).

```java
public static ItemStack numberButton(int digit) {
    ItemStack item = new ItemStack(Material.PLAYER_HEAD);
    item.editMeta(meta -> meta.displayName(
        net.kyori.adventure.text.Component.text(String.valueOf(digit),
            net.kyori.adventure.text.format.NamedTextColor.AQUA)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)));
    return item;
}
```

- [ ] **Step 2: Use it in `OrbitalCodeGui`** — replace the `Material.PAPER` digit items with `Items.numberButton(digit)`; give the display item a nicer material (`Material.NAME_TAG` → keep), and put a `LIGHT_BLUE_STAINED_GLASS_PANE` (now grey filler) border so the keypad reads as a panel. Keep the slot map and `slotForDigit` so `OrbitalCodeGuiTest` still passes.

- [ ] **Step 3: Run the FULL suite** — `./gradlew build` → BUILD SUCCESSFUL, all tests green.

- [ ] **Step 4: Manual smoke test**

1. As a non-owner OP: `/sn owner` → "Unknown command"; tab shows no `owner`; `/orbitalstrike` → "Unknown command", not in tab. Console shows nothing for these.
2. As the owner: `/sn owner` → panel. Add a player → they can now `/orbitalstrike`. Remove → they can't. Change the code → the keypad needs the new code.
3. Coordinate strike → choose Schedule → "2m" → wait/relog the server → the strike still fires; cancel one from the Scheduled Strikes GUI.
4. Keypad heads show the digits cleanly.

- [ ] **Step 5: Commit** — `git commit -m "feat: themed keypad and GUI polish"`

---

## Self-Review Notes

- **Secret + owner-only:** `/orbitalstrike` hidden behind `sentinel.orbital` (default false), granted only to allowlisted/owner players at join; `/sn owner` owner-gated with an unknown-command response + no tab for others; both filtered from console.
- **Code in DB:** stored in `settings` (not config.yml), default `2584`, changed via the owner panel.
- **Scheduled strikes:** persisted in `scheduled_strikes`, re-armed on enable (restart-surviving), cancellable from a GUI.
- **Type consistency:** new `Sentinel` accessors `owner()`, `orbitalAccess()`, `orbitalAccessListener()`, `scheduledStrikes()`. New GUIs extend `Gui`.
- **Testing caveats:** permission-attachment behavior, console filtering, and entity-spawning are server-side; tests cover the access store, owner gating, scheduled-strike DAO, and GUI navigation. Digit-head textures are a known visual follow-up (need base64 from a head DB).
```
