# Owner Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A hidden owner-only `/sn owner` panel with three persisted owner-self-protection toggles — command protection ("that entity does not exist"), auto-unban, and auto-whitelist.

**Architecture:** A new `OwnerProtectionManager` holds three volatile flags backed by the DB `settings` table and the pure `affectsOwner` detector. A `PlayerCommandPreprocessEvent` listener enforces command protection; `LoginListener` enforces auto-unban/auto-whitelist for the owner; `OwnerPanelGui` (double chest) exposes the toggles; `SentinelCommand` routes the hidden `/sn owner`; `PlayerActionsGui.open` closes the GUI vector.

**Tech Stack:** Paper 1.21 API, Java 21, Gradle (shadow), MockBukkit + JUnit 5.

## Global Constraints

- Owner UUID is hard-coded in `OwnerManager`: `6500ca9a-a10c-40a5-b985-a56ca9ff1d1e`. Do not make it configurable.
- Hidden by design: NO new keys in `config.yml`, `messages.yml`, or `plugin.yml`. Panel title/labels and the block message are hard-coded `Component`s. Toggle state lives in the DB `settings` table under keys `owner_protect`, `owner_auto_unban`, `owner_auto_whitelist`.
- DB writes use `plugin.db().submitWrite(...)` / `execute(...)`, never `submit(...)` (writer-thread/FIFO rule). Reads use `plugin.db().submit(...)`.
- Block / not-found message text is exactly: `that entity does not exist` (red). The hidden-subcommand reply is exactly: `Unknown command. Type "/help" for help.` (red).
- Manager side-effects (persist, unban, whitelist, name resolution) are best-effort and MUST never throw into callers; failures go to `plugin.debug(...)`.
- No new permission nodes.

---

### Task 1: `OwnerProtectionManager` + `OwnerManager` accessors + wiring

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/OwnerManager.java`
- Create: `src/main/java/de/derfakegamer/sentinel/manager/OwnerProtectionManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (field + accessor + construct + load)
- Test: `src/test/java/de/derfakegamer/sentinel/manager/OwnerProtectionManagerTest.java`

**Interfaces:**
- Produces: `OwnerManager.uuid() → UUID`, `OwnerManager.currentName() → String`;
  `OwnerProtectionManager(Sentinel)`, `load()`, `isEnabled()/isAutoUnban()/isAutoWhitelist() → boolean`,
  `setEnabled(boolean)/setAutoUnban(boolean)/setAutoWhitelist(boolean)`, `ownerName() → String`,
  static `affectsOwner(String commandLine, String ownerName) → boolean`; `Sentinel.ownerProtection() → OwnerProtectionManager`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.manager;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OwnerProtectionManagerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private void drain() throws Exception {
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        server.getScheduler().performTicks(3);
    }

    // ---- pure detector ----
    @Test void affectsOwnerMatchesExactNameCaseInsensitive() {
        assertTrue(OwnerProtectionManager.affectsOwner("/kill DerFakeGamer", "DerFakeGamer"));
        assertTrue(OwnerProtectionManager.affectsOwner("/effect give derfakegamer minecraft:speed", "DerFakeGamer"));
    }
    @Test void affectsOwnerIgnoresSuperstringAndLabel() {
        assertFalse(OwnerProtectionManager.affectsOwner("/give DerFakeGamer123 stone", "DerFakeGamer"));
        assertFalse(OwnerProtectionManager.affectsOwner("/DerFakeGamer", "DerFakeGamer")); // label only
    }
    @Test void affectsOwnerMatchesSelectorsButNotSelf() {
        assertTrue(OwnerProtectionManager.affectsOwner("/kill @a", "DerFakeGamer"));
        assertTrue(OwnerProtectionManager.affectsOwner("/effect @e[type=player] speed", "DerFakeGamer"));
        assertTrue(OwnerProtectionManager.affectsOwner("/kill @p", "Bob"));
        assertFalse(OwnerProtectionManager.affectsOwner("/kill @s", "DerFakeGamer"));
    }
    @Test void affectsOwnerNullSafe() {
        assertFalse(OwnerProtectionManager.affectsOwner(null, "DerFakeGamer"));
        assertFalse(OwnerProtectionManager.affectsOwner("/give Bob stone", null)); // null name, no selector
        assertTrue(OwnerProtectionManager.affectsOwner("/give @a stone", null));   // selector still matches
    }

    // ---- persistence round-trip for all three flags ----
    @Test void flagsPersistAndReload() throws Exception {
        OwnerProtectionManager m = new OwnerProtectionManager(plugin);
        m.setEnabled(true);
        m.setAutoUnban(true);
        m.setAutoWhitelist(true);
        drain();
        OwnerProtectionManager fresh = new OwnerProtectionManager(plugin);
        fresh.load();
        drain();
        assertTrue(fresh.isEnabled());
        assertTrue(fresh.isAutoUnban());
        assertTrue(fresh.isAutoWhitelist());
    }
    @Test void defaultsAreFalse() throws Exception {
        OwnerProtectionManager fresh = new OwnerProtectionManager(plugin);
        fresh.load();
        drain();
        assertFalse(fresh.isEnabled());
        assertFalse(fresh.isAutoUnban());
        assertFalse(fresh.isAutoWhitelist());
    }
}
```

- [ ] **Step 2: Run → FAIL** — `./gradlew test --tests '*OwnerProtectionManagerTest'` (won't compile / fail).

- [ ] **Step 3: Implement**

In `OwnerManager.java` add (keep existing methods/imports; ensure `java.util.UUID` and `org.bukkit.Bukkit` are imported):

```java
    public UUID uuid() { return OWNER; }

    public String currentName() { return org.bukkit.Bukkit.getOfflinePlayer(OWNER).getName(); }
```

Create `OwnerProtectionManager.java`:

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.SettingsDao;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Owner self-protection toggles (command guard, auto-unban, auto-whitelist), persisted in settings. */
public final class OwnerProtectionManager {
    private final Sentinel plugin;
    private volatile boolean protect;
    private volatile boolean autoUnban;
    private volatile boolean autoWhitelist;
    private volatile String ownerName;

    public OwnerProtectionManager(Sentinel plugin) { this.plugin = plugin; }

    /** True if the command line targets the owner: an arg equal to ownerName (case-insensitive) or an @a/@e/@p/@r selector. */
    public static boolean affectsOwner(String commandLine, String ownerName) {
        if (commandLine == null) return false;
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String[] parts = line.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {            // skip parts[0] (the command label)
            String tok = parts[i];
            if (tok.isEmpty()) continue;
            if (ownerName != null && !ownerName.isBlank() && tok.equalsIgnoreCase(ownerName)) return true;
            String low = tok.toLowerCase();
            if (low.startsWith("@a") || low.startsWith("@e") || low.startsWith("@p") || low.startsWith("@r")) return true;
        }
        return false;
    }

    public void load() {
        try {
            SettingsDao dao = new SettingsDao(plugin.db().database());
            plugin.db().submit(() -> new boolean[]{
                "true".equalsIgnoreCase(dao.get("owner_protect", "false")),
                "true".equalsIgnoreCase(dao.get("owner_auto_unban", "false")),
                "true".equalsIgnoreCase(dao.get("owner_auto_whitelist", "false"))
            }).thenAccept(v -> {
                protect = v[0]; autoUnban = v[1]; autoWhitelist = v[2];
                if (autoWhitelist) Bukkit.getScheduler().runTask(plugin, this::whitelistOwnerNow);
            });
        } catch (Throwable t) { plugin.debug("owner load: " + t.getMessage()); }
    }

    public boolean isEnabled() { return protect; }
    public boolean isAutoUnban() { return autoUnban; }
    public boolean isAutoWhitelist() { return autoWhitelist; }

    public void setEnabled(boolean on) { this.protect = on; persist("owner_protect", on); }
    public void setAutoUnban(boolean on) { this.autoUnban = on; persist("owner_auto_unban", on); if (on) unbanOwnerNow(); }
    public void setAutoWhitelist(boolean on) { this.autoWhitelist = on; persist("owner_auto_whitelist", on); if (on) whitelistOwnerNow(); }

    /** Current owner name, cached after first resolution (avoids a lookup on the command hot path). */
    public String ownerName() {
        String n = ownerName;
        if (n != null) return n;
        UUID u = plugin.owner().uuid();
        Player online = Bukkit.getPlayer(u);
        n = (online != null) ? online.getName() : Bukkit.getOfflinePlayer(u).getName();
        if (n != null) ownerName = n;
        return n;
    }

    private void persist(String key, boolean on) {
        try {
            SettingsDao dao = new SettingsDao(plugin.db().database());
            plugin.db().submitWrite(() -> { dao.set(key, String.valueOf(on)); return null; });
        } catch (Throwable t) { plugin.debug("owner persist " + key + ": " + t.getMessage()); }
    }

    private void unbanOwnerNow() {
        try { plugin.punishments().unban(plugin.owner().uuid(), "AUTO", System.currentTimeMillis()); }
        catch (Throwable t) { plugin.debug("owner auto-unban: " + t.getMessage()); }
    }

    private void whitelistOwnerNow() {
        try { Bukkit.getOfflinePlayer(plugin.owner().uuid()).setWhitelisted(true); }
        catch (Throwable t) { plugin.debug("owner auto-whitelist: " + t.getMessage()); }
    }
}
```

In `Sentinel.java`: add a field near `ownerManager` (`private de.derfakegamer.sentinel.manager.OwnerProtectionManager ownerProtection;`), construct it right after `this.ownerManager = new ...OwnerManager();`:

```java
        this.ownerProtection = new de.derfakegamer.sentinel.manager.OwnerProtectionManager(this);
        this.ownerProtection.load();
```

and add an accessor next to `owner()`:

```java
    public de.derfakegamer.sentinel.manager.OwnerProtectionManager ownerProtection() { return ownerProtection; }
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*OwnerProtectionManagerTest'` → PASS; then `./gradlew test` → green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/OwnerManager.java src/main/java/de/derfakegamer/sentinel/manager/OwnerProtectionManager.java src/main/java/de/derfakegamer/sentinel/Sentinel.java src/test/java/de/derfakegamer/sentinel/manager/OwnerProtectionManagerTest.java
git commit -m "feat: OwnerProtectionManager with persisted owner toggles + detector"
```

---

### Task 2: Command protection listener

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/listener/OwnerProtectionListener.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (register the listener)
- Test: `src/test/java/de/derfakegamer/sentinel/listener/OwnerProtectionListenerTest.java`

**Interfaces:**
- Consumes: `plugin.ownerProtection()` (Task 1), `plugin.owner().isOwner(Player)`, `OwnerProtectionManager.affectsOwner`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.listener;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerProtectionListenerTest {
    ServerMock server;
    Sentinel plugin;
    OwnerProtectionListener listener;
    PlayerMock owner;   // has the hard-coded owner UUID
    PlayerMock attacker;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        listener = new OwnerProtectionListener(plugin);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        attacker = server.addPlayer("Mallory");
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private boolean fire(PlayerMock who, String cmd) {
        PlayerCommandPreprocessEvent e = new PlayerCommandPreprocessEvent(who, cmd);
        listener.onCommand(e);
        return e.isCancelled();
    }

    @Test void offNeverCancels() {
        plugin.ownerProtection().setEnabled(false);
        assertFalse(fire(attacker, "/kill DerFakeGamer"));
    }
    @Test void onBlocksNonOwnerTargetingOwner() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(fire(attacker, "/kill DerFakeGamer"));
        assertNotNull(attacker.nextComponentMessage(), "blocked player must get a message");
    }
    @Test void onBlocksSelector() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(fire(attacker, "/kill @a"));
    }
    @Test void onAllowsUnrelatedCommand() {
        plugin.ownerProtection().setEnabled(true);
        assertFalse(fire(attacker, "/spawn"));
    }
    @Test void onNeverBlocksOwnerThemselves() {
        plugin.ownerProtection().setEnabled(true);
        assertFalse(fire(owner, "/kill DerFakeGamer"));
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `OwnerProtectionListener.java`:

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.OwnerProtectionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class OwnerProtectionListener implements Listener {
    private final Sentinel plugin;
    public OwnerProtectionListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.ownerProtection().isEnabled()) return;
        Player p = event.getPlayer();
        if (plugin.owner().isOwner(p)) return;   // the owner may target himself
        if (OwnerProtectionManager.affectsOwner(event.getMessage(), plugin.ownerProtection().ownerName())) {
            event.setCancelled(true);
            p.sendMessage(Component.text("that entity does not exist", NamedTextColor.RED));
            plugin.debug("owner-protect: blocked " + p.getName() + " -> " + event.getMessage());
        }
    }
}
```

Register it in `Sentinel.onEnable` with the other listeners:

```java
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OwnerProtectionListener(this), this);
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*OwnerProtectionListenerTest'` → PASS; then `./gradlew test` → green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/OwnerProtectionListener.java src/main/java/de/derfakegamer/sentinel/Sentinel.java src/test/java/de/derfakegamer/sentinel/listener/OwnerProtectionListenerTest.java
git commit -m "feat: owner command-protection listener (that entity does not exist)"
```

---

### Task 3: Auto-unban + auto-whitelist at login

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java`
- Test: `src/test/java/de/derfakegamer/sentinel/listener/OwnerLoginProtectionTest.java`

**Interfaces:**
- Consumes: `plugin.ownerProtection().isAutoUnban()/isAutoWhitelist()`, `plugin.owner().isOwner(UUID)`,
  `plugin.punishments().ban(...)` / `activeBan(...)` / `unban(...)`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.listener;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OwnerLoginProtectionTest {
    ServerMock server;
    Sentinel plugin;
    LoginListener listener;

    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); listener = new LoginListener(plugin); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private void drain() throws Exception { plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS); }

    private AsyncPlayerPreLoginEvent prelogin(UUID id, String name) throws Exception {
        return new AsyncPlayerPreLoginEvent(name, InetAddress.getByName("127.0.0.1"), id);
    }

    @Test void ownerWithAutoUnbanOffIsBanned() throws Exception {
        UUID id = plugin.owner().uuid();
        plugin.punishments().ban(id, "DerFakeGamer", new UUID(0,0), "CONSOLE", "test", 0L).get(2, TimeUnit.SECONDS);
        plugin.ownerProtection().setAutoUnban(false);
        AsyncPlayerPreLoginEvent e = prelogin(id, "DerFakeGamer");
        listener.onPreLogin(e);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, e.getLoginResult());
    }

    @Test void ownerWithAutoUnbanOnIsAllowedAndUnbanned() throws Exception {
        UUID id = plugin.owner().uuid();
        plugin.punishments().ban(id, "DerFakeGamer", new UUID(0,0), "CONSOLE", "test", 0L).get(2, TimeUnit.SECONDS);
        plugin.ownerProtection().setAutoUnban(true);
        drain();
        AsyncPlayerPreLoginEvent e = prelogin(id, "DerFakeGamer");
        listener.onPreLogin(e);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, e.getLoginResult());
        drain();
        assertNull(plugin.punishments().activeBan(id, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void autoWhitelistMarksOwnerWhitelisted() throws Exception {
        plugin.ownerProtection().setAutoWhitelist(true);
        server.getScheduler().performTicks(3);
        assertTrue(org.bukkit.Bukkit.getOfflinePlayer(plugin.owner().uuid()).isWhitelisted());
    }
}
```

> Note: verify the exact `PunishmentManager.ban(...)` signature before writing the test — match its
> parameter order/types (UUID target, String targetName, String ip, String issuerName, String reason,
> long durationOrExpiry). If the signature differs, adapt the `ban(...)` call accordingly; the assertion
> targets (login result + active-ban cleared) stay the same.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** — edit `LoginListener.onPreLogin`.

Add an auto-whitelist belt near the top, right after the `plugin.players().record(...)` line:

```java
        if (plugin.owner().isOwner(event.getUniqueId()) && plugin.ownerProtection().isAutoWhitelist()) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).setWhitelisted(true));
        }
```

Replace the final `if (ban != null) { ... }` disallow block with an owner-aware version:

```java
        if (ban != null) {
            if (plugin.owner().isOwner(event.getUniqueId()) && plugin.ownerProtection().isAutoUnban()) {
                try { plugin.punishments().unban(event.getUniqueId(), "AUTO", now); }
                catch (Throwable t) { plugin.debug("owner auto-unban login: " + t.getMessage()); }
                return; // allow the owner in
            }
            String url = plugin.getConfig().getString("appeals.url", "");
            String appealSuffix = url.isBlank() ? "" : "\n\nAppeal at: " + url;
            Component screen = plugin.messages().plain("ban-screen", "reason", ban.reason(),
                "duration", de.derfakegamer.sentinel.util.TimeFormat.until(ban.expiresAt(), now),
                "appeal", appealSuffix);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
```

(Keep the rest of `onPreLogin` — maintenance check, ban lookup, fail-open catch — unchanged.)

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*OwnerLoginProtectionTest'` → PASS; then `./gradlew test` → green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java src/test/java/de/derfakegamer/sentinel/listener/OwnerLoginProtectionTest.java
git commit -m "feat: owner auto-unban and auto-whitelist at login"
```

---

### Task 4: Owner Panel GUI + `/sn owner` routing

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/gui/OwnerPanelGui.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java` (owner branch + tab-complete)
- Test: `src/test/java/de/derfakegamer/sentinel/gui/OwnerPanelGuiTest.java`

**Interfaces:**
- Consumes: `plugin.ownerProtection()` toggles, `plugin.owner()`, `plugin.audit().record(...)`,
  `Items.button/head`, `Gui.border()/fillEmpty()`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.gui;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerPanelGuiTest {
    ServerMock server;
    Sentinel plugin;
    PlayerMock owner;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void panelOpensWith54Slots() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        assertEquals(54, gui.getInventory().getSize());
    }

    @Test void clickingProtectToggleFlipsPersistsAndAudits() throws Exception {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        assertFalse(plugin.ownerProtection().isEnabled());
        clickSlot(owner, gui, 20); // PROTECT
        assertTrue(plugin.ownerProtection().isEnabled());
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS);
        server.getScheduler().performTicks(3);
        var audit = plugin.audit().recent(10, 0).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(audit.stream().anyMatch(e -> "OWNER_PROTECT".equals(e.action())));
    }

    @Test void clickingAutoUnbanAndWhitelistToggles() {
        OwnerPanelGui gui = new OwnerPanelGui(plugin);
        gui.open(owner);
        clickSlot(owner, gui, 22); // AUTO_UNBAN
        clickSlot(owner, gui, 24); // AUTO_WHITELIST
        assertTrue(plugin.ownerProtection().isAutoUnban());
        assertTrue(plugin.ownerProtection().isAutoWhitelist());
    }

    // Fire an InventoryClickEvent at a raw slot, mirroring the project's GUI-test idiom.
    private void clickSlot(PlayerMock p, Gui gui, int slot) {
        InventoryView view = p.openInventory(gui.getInventory());
        InventoryClickEvent e = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        gui.onClick(e);
    }
}
```

> Note: this `clickSlot` helper is written inline to avoid coupling to another test's private helper. If a
> shared helper exists in a sibling GUI test (e.g. `ConfirmGuiTest`) and is accessible, prefer mirroring it;
> otherwise keep this inline version. Confirm the `InventoryClickEvent` constructor/imports compile under the
> MockBukkit version in `build.gradle.kts`.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `OwnerPanelGui.java`:

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.OwnerProtectionManager;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Hidden owner-only panel: command protection, auto-unban, auto-whitelist toggles. All text hard-coded. */
public final class OwnerPanelGui extends Gui {
    private static final int STATUS = 4, PROTECT = 20, AUTO_UNBAN = 22, AUTO_WHITELIST = 24, CLOSE = 49;

    public OwnerPanelGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Owner", NamedTextColor.DARK_AQUA));
        build();
    }

    private void build() {
        OwnerProtectionManager op = plugin.ownerProtection();
        String name = op.ownerName();
        inventory.setItem(STATUS, Items.head(Bukkit.getOfflinePlayer(plugin.owner().uuid()),
            Component.text("Owner Panel", NamedTextColor.AQUA),
            List.of(Component.text("Owner: " + (name == null ? "unknown" : name), NamedTextColor.GRAY))));
        inventory.setItem(PROTECT, toggle(Material.SHIELD, "Owner Protection", op.isEnabled(),
            "Blocks others from targeting you"));
        inventory.setItem(AUTO_UNBAN, toggle(Material.IRON_BARS, "Auto Unban", op.isAutoUnban(),
            "Lifts any ban on you automatically"));
        inventory.setItem(AUTO_WHITELIST, toggle(Material.NAME_TAG, "Auto Whitelist", op.isAutoWhitelist(),
            "Keeps you on the whitelist"));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        border();
        fillEmpty();
    }

    private ItemStack toggle(Material m, String label, boolean on, String desc) {
        return Items.button(m,
            Component.text(label + ": " + (on ? "ON" : "OFF"), on ? NamedTextColor.GREEN : NamedTextColor.RED),
            List.of(Component.text(desc, NamedTextColor.GRAY), Component.text("Click to toggle", NamedTextColor.GRAY)));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        if (!plugin.owner().isOwner(p)) { p.closeInventory(); return; }   // owner-only, defense in depth
        OwnerProtectionManager op = plugin.ownerProtection();
        switch (event.getRawSlot()) {
            case PROTECT -> { boolean n = !op.isEnabled(); op.setEnabled(n);
                plugin.audit().record(p.getName(), "OWNER_PROTECT", "self", n ? "on" : "off"); build(); }
            case AUTO_UNBAN -> { boolean n = !op.isAutoUnban(); op.setAutoUnban(n);
                plugin.audit().record(p.getName(), "OWNER_AUTO_UNBAN", "self", n ? "on" : "off"); build(); }
            case AUTO_WHITELIST -> { boolean n = !op.isAutoWhitelist(); op.setAutoWhitelist(n);
                plugin.audit().record(p.getName(), "OWNER_AUTO_WHITELIST", "self", n ? "on" : "off"); build(); }
            case CLOSE -> p.closeInventory();
        }
    }
}
```

In `SentinelCommand.onCommand`, add right after the `update` branch (the `if (args.length == 1 && args[0].equalsIgnoreCase("update"))` block):

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

In `SentinelCommand.onTabComplete`, inside the `if (args.length == 1)` block, after `opts` is created and before filtering, add:

```java
            if (plugin.owner().isOwner(sender)) opts.add("owner");
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*OwnerPanelGuiTest'` → PASS; then `./gradlew test` → green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/OwnerPanelGui.java src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java src/test/java/de/derfakegamer/sentinel/gui/OwnerPanelGuiTest.java
git commit -m "feat: hidden /sn owner Owner Panel with three toggles"
```

---

### Task 5: Close the plugin GUI vector in `PlayerActionsGui`

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java` (guard in `open`)
- Test: `src/test/java/de/derfakegamer/sentinel/gui/OwnerActionsGuardTest.java`

**Interfaces:**
- Consumes: `plugin.ownerProtection().isEnabled()`, `plugin.owner().isOwner(UUID)`, `plugin.owner().isOwner(CommandSender)`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.gui;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerActionsGuardTest {
    ServerMock server;
    Sentinel plugin;
    PlayerMock owner;
    PlayerMock staff;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        staff = server.addPlayer("Staff");
        staff.setOp(true);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void protectedOwnerCannotBeOpenedByOthers() {
        plugin.ownerProtection().setEnabled(true);
        PlayerActionsGui.open(plugin, owner, staff);
        server.getScheduler().performTicks(3);
        assertNull(staff.getOpenInventory().getTopInventory().getHolder() instanceof PlayerActionsGui ? Boolean.TRUE : null,
            "staff must not have a PlayerActionsGui open for the owner");
        assertNotNull(staff.nextComponentMessage(), "staff must get the not-found message");
    }

    @Test void protectionOffOpensNormally() {
        plugin.ownerProtection().setEnabled(false);
        PlayerActionsGui.open(plugin, owner, staff);
        server.getScheduler().performTicks(5);
        // No exception, and no block message at the head of the queue.
        assertNull(staff.nextComponentMessage());
    }
}
```

> Note: assert the observable behavior available under this MockBukkit version. The essential checks are
> (a) under protection a non-owner viewer does NOT end up with a `PlayerActionsGui` open for the owner and
> receives the block message, and (b) with protection off `open` proceeds without the block message. If a
> particular accessor (e.g. `getOpenInventory().getTopInventory().getHolder()`) behaves differently in the
> harness, adapt to an equivalent assertion (e.g. assert the block message is/ isn't sent), keeping both
> cases covered.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** — at the very top of `PlayerActionsGui.open(Sentinel plugin, OfflinePlayer target, Player viewer)`, before `long now = ...`:

```java
        if (plugin.ownerProtection().isEnabled()
                && plugin.owner().isOwner(target.getUniqueId())
                && !plugin.owner().isOwner(viewer)) {
            viewer.sendMessage(net.kyori.adventure.text.Component.text(
                "that entity does not exist", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*OwnerActionsGuardTest'` → PASS; then `./gradlew test` → green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java src/test/java/de/derfakegamer/sentinel/gui/OwnerActionsGuardTest.java
git commit -m "feat: block opening the owner's action menu under protection"
```

---

## Self-Review

- **Spec coverage:** detector + state/persistence + ownerName cache (Task 1); command listener (Task 2);
  auto-unban + auto-whitelist at login (Task 3); panel + `/sn owner` + tab-complete (Task 4); GUI vector
  (Task 5). All spec sections mapped.
- **Hidden-by-design:** no config/messages/plugin.yml keys added; panel + block + unknown-command text are
  hard-coded Components; state in DB `settings`.
- **Type consistency:** `OwnerProtectionManager` methods (`isEnabled/isAutoUnban/isAutoWhitelist`,
  `setEnabled/setAutoUnban/setAutoWhitelist`, `ownerName`, static `affectsOwner`), `OwnerManager.uuid()`,
  and `Sentinel.ownerProtection()` are produced in Task 1 and consumed consistently in Tasks 2–5.
- **Write routing:** persistence uses `submitWrite`; reads use `submit`.
- **Verify-before-write reminders:** confirm the `PunishmentManager.ban(...)` signature (Task 3) and the
  `InventoryClickEvent` constructor available under the project's MockBukkit (Tasks 4–5) before finalizing
  the tests; both notes are inline in the tasks.
