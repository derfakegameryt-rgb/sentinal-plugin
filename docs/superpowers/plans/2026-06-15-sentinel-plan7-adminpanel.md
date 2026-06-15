# Sentinel — Plan 7: Admin Panel & Mandatory Auto-Update

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give server operators an Admin Panel GUI with server info, the operator list (with de-op), active bans, active mutes, and open reports. Also make the auto-updater mandatory and fixed at a 5-minute interval (no off switch, no configurable interval).

**Architecture:** A new `AdminPanelGui` (reached from a button in the Players GUI) links to `ServerInfoGui`, `OperatorsGui`, `ActiveBansGui`, `ActiveMutesGui`, and the existing `ReportsGui`. Active bans/mutes come from a new `PunishmentDao.findActiveByType` + `PunishmentManager.activeList`. `PlayerActionsGui` gains an OP/De-OP toggle. `UpdateChecker.start()` no longer reads `update.enabled`/`check-interval-seconds` — it always runs every 300s.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0). The panel is OP-only (like the rest of the plugin).

---

## File Structure

```
src/main/java/de/derfakegamer/sentinel/
  storage/PunishmentDao.java       MOD  findActiveByType(type)
  manager/PunishmentManager.java   MOD  activeList(type, now)
  updater/UpdateChecker.java       MOD  always on, fixed 300s interval
  gui/AdminPanelGui.java           NEW  panel main menu
  gui/ServerInfoGui.java           NEW  server/runtime stats
  gui/OperatorsGui.java            NEW  list of OPs
  gui/ActiveBansGui.java           NEW  active bans
  gui/ActiveMutesGui.java          NEW  active mutes
  gui/PlayersGui.java              MOD  Admin Panel button (slot 50)
  gui/PlayerActionsGui.java        MOD  OP/De-OP toggle (slot 26)
  resources/config.yml             MOD  trim update section to github-token only
  resources/messages.yml           MOD  new keys
  Sentinel.java                    (no change needed beyond existing wiring)
src/test/java/de/derfakegamer/sentinel/
  storage/PunishmentDaoActiveListTest.java   NEW
  gui/AdminPanelGuiTest.java                 NEW
  gui/OperatorsGuiTest.java                  NEW
  gui/ActiveBansGuiTest.java                 NEW
```

---

## Task 1: Active-list query (DAO + manager)

**Files:**
- Modify: `storage/PunishmentDao.java`, `manager/PunishmentManager.java`
- Test: `storage/PunishmentDaoActiveListTest.java`

- [ ] **Step 1: Write the failing test `PunishmentDaoActiveListTest.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentDaoActiveListTest {
    Database db; PunishmentDao dao; File tmp;

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new PunishmentDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Punishment ban(String name) {
        return Punishment.builder().type(PunishmentType.BAN).targetUuid(UUID.randomUUID())
            .targetName(name).reason("x").issuerUuid(UUID.randomUUID()).issuerName("Admin")
            .createdAt(100).expiresAt(0).active(true).build();
    }

    @Test void findActiveByTypeReturnsOnlyActiveOfThatType() {
        dao.insert(ban("A"));
        long id = dao.insert(ban("B"));
        dao.insert(Punishment.builder().type(PunishmentType.MUTE).targetUuid(UUID.randomUUID())
            .targetName("M").reason("x").issuerUuid(UUID.randomUUID()).issuerName("Admin")
            .createdAt(100).expiresAt(0).active(true).build());
        dao.deactivate(id, "Admin", 200);
        assertEquals(1, dao.findActiveByType(PunishmentType.BAN).size()); // only "A" (B deactivated)
        assertEquals(1, dao.findActiveByType(PunishmentType.MUTE).size());
    }
}
```

- [ ] **Step 2: Run it → fails.**

- [ ] **Step 3: Add `findActiveByType` to `PunishmentDao.java`**

```java
public java.util.List<de.derfakegamer.sentinel.model.Punishment> findActiveByType(
        de.derfakegamer.sentinel.model.PunishmentType type) {
    synchronized (db) {
        java.util.List<de.derfakegamer.sentinel.model.Punishment> out = new java.util.ArrayList<>();
        String sql = "SELECT * FROM punishments WHERE type=? AND active=1 ORDER BY created_at DESC";
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, type.name());
            try (java.sql.ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        return out;
    }
}
```

(Place it next to the other query methods; `map(rs)` already exists.)

- [ ] **Step 4: Add `activeList` to `PunishmentManager.java`**

```java
/** All currently-active punishments of a type, lazily dropping any that have expired. */
public java.util.List<Punishment> activeList(PunishmentType type, long now) {
    java.util.List<Punishment> out = new java.util.ArrayList<>();
    for (Punishment p : dao.findActiveByType(type)) {
        if (p.isExpired(now)) dao.deactivate(p.id(), "SYSTEM", now);
        else out.add(p);
    }
    return out;
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests PunishmentDaoActiveListTest --tests PunishmentManagerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: query active punishments by type"
```

---

## Task 2: Mandatory auto-update (fixed 5-minute interval)

**Files:**
- Modify: `updater/UpdateChecker.java`, `resources/config.yml`

- [ ] **Step 1: Make `UpdateChecker.start()` always run on a fixed interval**

Replace the `start()` method body with:

```java
/** Starts the periodic update check. Always on; fixed 5-minute interval (not configurable). */
public void start() {
    long ticks = 300L * 20L; // 5 minutes
    plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
        () -> check(null), 20L, ticks);
}
```

(`update.enabled` and `update.check-interval-seconds` are no longer read. The optional `update.github-token` is still used by `httpGet`/`download`.)

- [ ] **Step 2: Trim the `update:` section in `config.yml`**

```yaml
update:
  # Auto-update is always on and checks every 5 minutes; this cannot be disabled.
  github-token: ''   # optional, raises the GitHub API rate limit
```

- [ ] **Step 3: Build & verify existing updater tests still pass**

Run: `./gradlew test --tests UpdateCheckerTest --tests VersionTest`
Expected: PASS (the tests cover parsing + `isNewer`, which are unchanged).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: auto-update is always on at a fixed 5-minute interval"
```

---

## Task 3: AdminPanelGui + ServerInfoGui + entry button

**Files:**
- Create: `gui/AdminPanelGui.java`, `gui/ServerInfoGui.java`
- Modify: `gui/PlayersGui.java`, `messages.yml`
- Test: `gui/AdminPanelGuiTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
gui-panel-title: "<#3B82F6>Sentinel · Admin Panel"
gui-serverinfo-title: "<#3B82F6>Sentinel · Server Info"
```

- [ ] **Step 2: Write the failing test `AdminPanelGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class AdminPanelGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void panelHasTheFiveSections() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        for (int slot : new int[]{10, 11, 12, 13, 14})
            assertNotNull(gui.getInventory().getItem(slot), "section button at " + slot);
    }

    @Test void serverInfoOpensFromPanel() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(op);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(op, gui, 10); // Server Info
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertInstanceOf(ServerInfoGui.class, op.getOpenInventory().getTopInventory().getHolder());
    }
}
```

- [ ] **Step 3: Write `gui/ServerInfoGui.java`**

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

import java.lang.management.ManagementFactory;
import java.util.List;

public final class ServerInfoGui extends Gui {
    private static final int BACK = 18, CLOSE = 26;

    public ServerInfoGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-serverinfo-title"));

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long maxMb = rt.maxMemory() / 1048576L;

        inventory.setItem(10, info(Material.BOOK, "Version", List.of(
            "MC/Paper: " + Bukkit.getBukkitVersion(),
            "Server: " + Bukkit.getVersion(),
            "Plugin: " + plugin.getPluginMeta().getVersion())));
        inventory.setItem(11, info(Material.CLOCK, "TPS", List.of(tpsLine())));
        inventory.setItem(12, info(Material.IRON_BLOCK, "Memory", List.of(
            "Used: " + usedMb + " MB", "Max: " + maxMb + " MB")));
        inventory.setItem(13, info(Material.COMPARATOR, "System", List.of(
            "OS: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")",
            "CPU cores: " + rt.availableProcessors(),
            "Java: " + System.getProperty("java.version"))));
        inventory.setItem(14, info(Material.PLAYER_HEAD, "Players", List.of(
            "Online: " + Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers())));
        inventory.setItem(15, info(Material.GRASS_BLOCK, "Worlds", List.of(
            "Loaded: " + Bukkit.getWorlds().size())));
        inventory.setItem(16, info(Material.SUNFLOWER, "Uptime", List.of(uptimeLine())));

        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private String tpsLine() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return String.format("1m %.1f · 5m %.1f · 15m %.1f", tps[0], tps[1], tps[2]);
        } catch (Throwable t) { return "n/a"; }
    }

    private String uptimeLine() {
        long ms = ManagementFactory.getRuntimeMXBean().getUptime();
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h + "h " + m + "m";
    }

    private org.bukkit.inventory.ItemStack info(Material m, String title, List<String> lines) {
        java.util.List<Component> lore = new java.util.ArrayList<>();
        for (String l : lines) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        return Items.button(m, Component.text(title, NamedTextColor.AQUA), lore);
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

- [ ] **Step 4: Write `gui/AdminPanelGui.java`**

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

public final class AdminPanelGui extends Gui {
    private static final int INFO = 10, OPS = 11, BANS = 12, MUTES = 13, REPORTS = 14;
    private static final int BACK = 18, CLOSE = 26;

    public AdminPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-panel-title"));
        inventory.setItem(INFO, button(Material.COMPARATOR, "Server Info", "Specs, TPS, memory, uptime"));
        inventory.setItem(OPS, button(Material.PLAYER_HEAD, "Operators", "Everyone with OP"));
        inventory.setItem(BANS, button(Material.IRON_BARS, "Active Bans", "Currently banned players"));
        inventory.setItem(MUTES, button(Material.BOOK, "Active Mutes", "Currently muted players"));
        inventory.setItem(REPORTS, button(Material.PAPER, "Open Reports", "Reports waiting for staff"));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
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
            case INFO -> new ServerInfoGui(plugin).open(p);
            case OPS -> new OperatorsGui(plugin, 0).open(p);
            case BANS -> new ActiveBansGui(plugin, 0).open(p);
            case MUTES -> new ActiveMutesGui(plugin, 0).open(p);
            case REPORTS -> new ReportsGui(plugin, 0).open(p);
            case BACK -> new PlayersGui(plugin, 0).open(p);
            case CLOSE -> p.closeInventory();
        }
    }
}
```

> **Note:** `OperatorsGui`, `ActiveBansGui`, `ActiveMutesGui` are created in Tasks 4–5. Implement Tasks 3–5 together so the project compiles, then run their tests.

- [ ] **Step 5: Add the Admin Panel button to `gui/PlayersGui.java`**

Add a `PANEL = 50` constant. In the bottom-row block of the constructor, set:

```java
inventory.setItem(PANEL, Items.button(Material.COMPARATOR,
    Component.text("Admin Panel", NamedTextColor.AQUA),
    List.of(Component.text("Server info, ops, bans, mutes, reports", NamedTextColor.GRAY)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
```

In `onClick`, add before the head-index handling:

```java
if (slot == PANEL) { new AdminPanelGui(plugin).open(mod); return; }
```

- [ ] **Step 6: Run tests (after Tasks 4–5 exist)**

Run: `./gradlew test --tests AdminPanelGuiTest --tests PlayersGuiTest --tests PlayersGuiButtonsTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: admin panel main menu and server info GUI"
```

---

## Task 4: OperatorsGui + OP/De-OP toggle

**Files:**
- Create: `gui/OperatorsGui.java`
- Modify: `gui/PlayerActionsGui.java` (OP toggle at slot 26), `messages.yml`
- Test: `gui/OperatorsGuiTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
gui-operators-title: "<#3B82F6>Sentinel · Operators"
opped: "<#60A5FA><player></#60A5FA> <gray>is now an operator."
deopped: "<#60A5FA><player></#60A5FA> <gray>is no longer an operator."
```

- [ ] **Step 2: Write the failing test `OperatorsGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OperatorsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void listsOperatorsAsHeads() {
        PlayerMock a = server.addPlayer("Admin1"); a.setOp(true);
        PlayerMock b = server.addPlayer("Admin2"); b.setOp(true);
        server.addPlayer("Normal");
        OperatorsGui gui = new OperatorsGui(plugin, 0);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(2, heads);
    }
}
```

- [ ] **Step 3: Write `gui/OperatorsGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class OperatorsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;

    private final int page;
    private final List<OfflinePlayer> ops;

    public OperatorsGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.ops = new ArrayList<>(Bukkit.getOperators());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-operators-title"));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < ops.size(); i++) {
            OfflinePlayer op = ops.get(from + i);
            String name = op.getName() != null ? op.getName() : op.getUniqueId().toString();
            inventory.setItem(i, Items.head(op, Component.text(name, NamedTextColor.AQUA),
                List.of(Component.text(op.isOnline() ? "Online" : "Offline", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.text("Click to manage", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back", NamedTextColor.RED), List.of()));
        if (from + PAGE_SIZE < ops.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new OperatorsGui(plugin, page - 1).open(p); return; }
        if (slot == NEXT) { new OperatorsGui(plugin, page + 1).open(p); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < ops.size())
            new PlayerActionsGui(plugin, ops.get(index)).open(p);
    }
}
```

- [ ] **Step 4: Add an OP/De-OP toggle to `gui/PlayerActionsGui.java`**

Add an `OPTOGGLE = 26` constant. In the constructor (after the ALTS button), set a context-sensitive button:

```java
inventory.setItem(OPTOGGLE, Items.button(target.isOp() ? Material.NETHERITE_BLOCK : Material.NETHERITE_SCRAP,
    net.kyori.adventure.text.Component.text(target.isOp() ? "De-OP" : "Make OP",
        target.isOp() ? net.kyori.adventure.text.format.NamedTextColor.RED
                       : net.kyori.adventure.text.format.NamedTextColor.GREEN),
    List.of(net.kyori.adventure.text.Component.text("Toggle operator status", net.kyori.adventure.text.format.NamedTextColor.GRAY)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
```

In `onClick`, add a case:

```java
case OPTOGGLE -> {
    boolean makeOp = !target.isOp();
    target.setOp(makeOp);
    mod.sendMessage(plugin.messages().prefixed(makeOp ? "opped" : "deopped", "player", name()));
    mod.closeInventory();
}
```

(`target` is an `OfflinePlayer`; `OfflinePlayer.setOp(boolean)` and `isOp()` work for online and offline players.)

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests OperatorsGuiTest --tests PlayerActionsGuiTest --tests PlayerActionsGuiToolsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: operators GUI and OP/de-OP toggle"
```

---

## Task 5: ActiveBansGui + ActiveMutesGui

**Files:**
- Create: `gui/ActiveBansGui.java`, `gui/ActiveMutesGui.java`
- Modify: `messages.yml`
- Test: `gui/ActiveBansGuiTest.java`

- [ ] **Step 1: Add title keys to `messages.yml`**

```yaml
gui-bans-title: "<#3B82F6>Sentinel · Active Bans"
gui-mutes-title: "<#3B82F6>Sentinel · Active Mutes"
```

- [ ] **Step 2: Write the failing test `ActiveBansGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ActiveBansGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void listsActiveBans() {
        plugin.punishments().ban(UUID.randomUUID(), "Banned1", new UUID(0,0), "Admin", "x", 0);
        plugin.punishments().ban(UUID.randomUUID(), "Banned2", new UUID(0,0), "Admin", "x", 0);
        ActiveBansGui gui = new ActiveBansGui(plugin, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) items++;
        }
        assertEquals(2, items);
    }
}
```

- [ ] **Step 3: Write `gui/ActiveBansGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ActiveBansGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;

    private final int page;
    private final List<Punishment> bans;

    public ActiveBansGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.bans = plugin.punishments().activeList(PunishmentType.BAN, System.currentTimeMillis());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-bans-title"));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < bans.size(); i++) {
            Punishment b = bans.get(from + i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(b.targetUuid()),
                Component.text(b.targetName(), NamedTextColor.AQUA),
                List.of(grey("Reason: " + b.reason()),
                        grey("By: " + b.issuerName()),
                        grey(b.isPermanent() ? "Permanent" : "Temporary"),
                        grey("Click to manage / unban"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back", NamedTextColor.RED), List.of()));
        if (from + PAGE_SIZE < bans.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
        fillEmpty();
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new ActiveBansGui(plugin, page - 1).open(p); return; }
        if (slot == NEXT) { new ActiveBansGui(plugin, page + 1).open(p); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < bans.size())
            new PlayerActionsGui(plugin, Bukkit.getOfflinePlayer(bans.get(index).targetUuid())).open(p);
    }
}
```

- [ ] **Step 4: Write `gui/ActiveMutesGui.java`**

Identical to `ActiveBansGui` but for mutes: replace class name with `ActiveMutesGui`, `PunishmentType.BAN` → `PunishmentType.MUTE`, the field/title to `bans`→`mutes`/`gui-mutes-title`, and the back/nav recursion to `new ActiveMutesGui(...)`. (Copy the file and rename — the two are intentionally parallel.)

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ActiveMutesGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;

    private final int page;
    private final List<Punishment> mutes;

    public ActiveMutesGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.mutes = plugin.punishments().activeList(PunishmentType.MUTE, System.currentTimeMillis());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-mutes-title"));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < mutes.size(); i++) {
            Punishment b = mutes.get(from + i);
            inventory.setItem(i, Items.head(Bukkit.getOfflinePlayer(b.targetUuid()),
                Component.text(b.targetName(), NamedTextColor.AQUA),
                List.of(grey("Reason: " + b.reason()),
                        grey("By: " + b.issuerName()),
                        grey(b.isPermanent() ? "Permanent" : "Temporary"),
                        grey("Click to manage / unmute"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back", NamedTextColor.RED), List.of()));
        if (from + PAGE_SIZE < mutes.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
        fillEmpty();
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new ActiveMutesGui(plugin, page - 1).open(p); return; }
        if (slot == NEXT) { new ActiveMutesGui(plugin, page + 1).open(p); return; }
        if (slot == BACK) { new AdminPanelGui(plugin).open(p); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < mutes.size())
            new PlayerActionsGui(plugin, Bukkit.getOfflinePlayer(mutes.get(index).targetUuid())).open(p);
    }
}
```

- [ ] **Step 5: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green. Shaded jar produced.

- [ ] **Step 6: Manual smoke test**

1. `/sentinel` → Admin Panel (slot 50) → opens the panel.
2. Server Info → shows TPS, RAM, CPU, version, uptime, players, worlds.
3. Operators → shows OPs; click one → actions → De-OP works.
4. Active Bans / Active Mutes → list entries; click → that player's actions (Unban/Unmute available).
5. Open Reports → Reports GUI.
6. Confirm the updater runs every 5 min and config has no enable/interval toggle.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: active bans and active mutes GUIs"
```

---

## Self-Review Notes (plan vs. requirements)

- **Admin Panel for OPs** ✓ — reached from the Players GUI (slot 50), OP-only like the rest of the plugin (no special owner identity, per the revised request).
- **1. Server Info** ✓ (Task 3): version, Java, OS + cores, RAM used/max, TPS 1/5/15, uptime, players online/max, worlds, plugin version.
- **2. Operators** ✓ (Task 4): OP heads → actions incl. De-OP toggle.
- **3. Active Bans** ✓ / **4. Active Mutes** ✓ (Task 5): from the DB, click → manage/unban/unmute.
- **5. Open Reports** ✓: panel button opens the existing `ReportsGui`.
- **No settings section** ✓ (dropped, per request).
- **Auto-update mandatory + fixed 5 min** ✓ (Task 2): `update.enabled`/interval no longer read; always 300s.
- **Type consistency:** `PunishmentManager.activeList(type, now)`, `PunishmentDao.findActiveByType(type)`; new GUIs all extend `Gui`. `PlayersGui` slot `PANEL = 50` (free filler), `PlayerActionsGui` slot `OPTOGGLE = 26` (free) — no existing test clicks those.
- **Testing caveat:** `Bukkit.getServer().getTPS()` is wrapped in try/catch (returns "n/a" under MockBukkit, which may not implement it), so `ServerInfoGui` builds in tests.
```
