# Sentinel — Plan 2: GUIs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the point-and-click moderation GUIs on top of Plan 1's engine: a Players list, a context-sensitive Player Actions menu, a Reason picker, a Confirmation dialog, and a Punishment History view — plus the chat-input flow for entering durations and custom reasons. After this plan a moderator can ban/mute/warn/kick entirely through `/sentinel` without typing punishment commands.

**Architecture:** A tiny GUI framework: every menu extends `Gui` (which implements `InventoryHolder`), and a single `GuiListener` routes `InventoryClickEvent`/`InventoryCloseEvent` to the GUI by checking the inventory holder. All clicks in Sentinel GUIs are cancelled (display-only items). Durations and custom reasons are collected through a `ChatInputManager` that the existing `ChatListener` consults before applying mutes. A new `ModerationService` centralizes "execute punishment + announce + kick" so both the Plan 1 commands and the new Confirm GUI share one code path (DRY).

**Tech Stack:** Same as Plan 1 (Java 21, Paper 1.21.11 API, Adventure/MiniMessage, SQLite, JUnit 5 + MockBukkit 4.110.0). GUIs use `Bukkit.createInventory(holder, size, Component title)` and Adventure item meta.

> **Build note:** No new dependencies. `Sentinel` is already non-final (required by MockBukkit). Keep it that way.

---

## File Structure

```
src/main/java/de/derfakegamer/sentinel/
  manager/ModerationService.java   NEW  execute punishment + announce + kick (shared)
  manager/ChatInputManager.java    NEW  pending chat-input callbacks per player
  gui/Gui.java                     NEW  abstract base, implements InventoryHolder
  gui/GuiListener.java             NEW  routes click/close events to the GUI
  gui/PlayersGui.java              NEW  online players, paginated
  gui/PlayerActionsGui.java        NEW  context-sensitive actions for one player
  gui/ReasonGui.java               NEW  5 presets + custom reason
  gui/ConfirmGui.java              NEW  confirm / cancel
  gui/HistoryGui.java              NEW  paginated punishment history
  util/Items.java                  NEW  ItemStack builders (buttons, heads, filler)
  command/SentinelCommand.java     MOD  /sentinel [player] opens a GUI; keep reload
  command/PunishmentCommands.java  MOD  delegate execute+announce to ModerationService
  listener/ChatListener.java       MOD  consult ChatInputManager before mute check
  Sentinel.java                    MOD  build services, register GuiListener, expose getters
src/test/java/de/derfakegamer/sentinel/
  manager/ModerationServiceTest.java   NEW
  gui/ConfirmGuiTest.java              NEW
  gui/ReasonGuiTest.java               NEW
  gui/PlayerActionsGuiTest.java        NEW
  gui/HistoryGuiTest.java              NEW
  gui/PlayersGuiTest.java              NEW
```

**Theme constants (used by every GUI):** border/filler = `Material.LIGHT_BLUE_STAINED_GLASS_PANE`; titles via MiniMessage from `messages.yml`; primary blue `#3B82F6`. All GUI item names/lore are Adventure `Component`s built with MiniMessage (no legacy color codes).

---

## Task 1: GUI framework, Items helper, and ModerationService

**Files:**
- Create: `gui/Gui.java`, `gui/GuiListener.java`, `util/Items.java`, `manager/ModerationService.java`
- Modify: `Sentinel.java` (build `ModerationService`, register `GuiListener`, expose `moderation()`)
- Modify: `command/PunishmentCommands.java` (delegate execute/announce to the service)
- Test: `manager/ModerationServiceTest.java`

- [ ] **Step 1: Write the failing test `ModerationServiceTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void applyBanRecordsActiveBan() {
        UUID target = UUID.randomUUID();
        boolean ok = plugin.moderation().apply(
            new UUID(0,0), "Admin", target, "Griefer", null, PunishmentType.BAN, 0, "hax");
        assertTrue(ok);
        assertNotNull(plugin.punishments().activeBan(target, System.currentTimeMillis()));
    }

    @Test void applyExemptReturnsFalseAndRecordsNothing() {
        UUID exempt = UUID.randomUUID();
        plugin.getConfig().set("exempt", java.util.List.of(exempt.toString()));
        plugin.reloadAll();
        boolean ok = plugin.moderation().apply(
            new UUID(0,0), "Admin", exempt, "Owner", null, PunishmentType.BAN, 0, "x");
        assertFalse(ok);
        assertNull(plugin.punishments().activeBan(exempt, System.currentTimeMillis()));
    }

    @Test void removeBanReturnsFalseWhenNotBanned() {
        assertFalse(plugin.moderation().removeBan(new UUID(0,0), "Admin", UUID.randomUUID(), "Nobody"));
    }
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests ModerationServiceTest`
Expected: FAIL — `plugin.moderation()` / `ModerationService` does not exist.

- [ ] **Step 3: Write `manager/ModerationService.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Executes a punishment and performs the side effects (broadcast + kick). Shared by commands and GUIs. */
public final class ModerationService {
    private final Sentinel plugin;

    public ModerationService(Sentinel plugin) { this.plugin = plugin; }

    /** Applies a punishment. Returns false if the target is exempt (nothing recorded). */
    public boolean apply(UUID issuerId, String issuerName, UUID targetId, String targetName,
                         String ip, PunishmentType type, long expiresAt, String reason) {
        PunishmentManager pm = plugin.punishments();
        PunishmentManager.Result result = switch (type) {
            case BAN   -> pm.ban(targetId, targetName, issuerId, issuerName, reason, expiresAt);
            case IPBAN -> pm.ipBan(targetId, targetName, ip, issuerId, issuerName, reason, expiresAt);
            case MUTE  -> pm.mute(targetId, targetName, issuerId, issuerName, reason, expiresAt);
            case WARN  -> pm.warn(targetId, targetName, issuerId, issuerName, reason);
            case KICK  -> pm.kick(targetId, targetName, issuerId, issuerName, reason);
        };
        if (!result.isSuccess()) return false;

        String key = switch (type) {
            case BAN, IPBAN -> "banned";
            case MUTE       -> "muted";
            case WARN       -> "warned";
            case KICK       -> "kicked";
        };
        Bukkit.broadcast(plugin.messages().prefixed(key, "player", targetName, "reason", reason));
        if (type == PunishmentType.BAN || type == PunishmentType.IPBAN || type == PunishmentType.KICK)
            kickIfOnline(targetId, reason);
        return true;
    }

    public boolean removeBan(UUID issuerId, String issuerName, UUID targetId, String targetName) {
        boolean ok = plugin.punishments().unban(targetId, issuerName, System.currentTimeMillis());
        if (ok) Bukkit.broadcast(plugin.messages().prefixed("unbanned", "player", targetName, "reason", ""));
        return ok;
    }

    public boolean removeMute(UUID issuerId, String issuerName, UUID targetId, String targetName) {
        boolean ok = plugin.punishments().unmute(targetId, issuerName, System.currentTimeMillis());
        if (ok) Bukkit.broadcast(plugin.messages().prefixed("unmuted", "player", targetName, "reason", ""));
        return ok;
    }

    private void kickIfOnline(UUID id, String reason) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.kick(plugin.messages().plain("ban-screen", "reason", reason));
    }
}
```

- [ ] **Step 4: Write `util/Items.java`**

```java
package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class Items {
    private Items() {}

    public static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(owner);
            meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    public static ItemStack filler() {
        return button(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text(" "), null);
    }
}
```

- [ ] **Step 5: Write `gui/Gui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public abstract class Gui implements InventoryHolder {
    protected final Sentinel plugin;
    protected Inventory inventory;

    protected Gui(Sentinel plugin) { this.plugin = plugin; }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    /** Called for every click in this GUI. Implementations MUST cancel the event first. */
    public abstract void onClick(InventoryClickEvent event);

    public void onClose(InventoryCloseEvent event) {}

    public void open(Player player) { player.openInventory(inventory); }

    /** Fills every empty slot with the blue glass filler. */
    protected void fillEmpty() {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Items.filler());
        }
    }
}
```

- [ ] **Step 6: Write `gui/GuiListener.java`**

```java
package de.derfakegamer.sentinel.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public final class GuiListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            gui.onClick(event);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            gui.onClose(event);
        }
    }
}
```

- [ ] **Step 7: Wire into `Sentinel.java`**

Add a field and getter, build the service in `onEnable()` (after the manager is built), register the listener, and rebuild the service in `reloadAll()`:

```java
// fields
private de.derfakegamer.sentinel.manager.ModerationService moderationService;

// in onEnable(), AFTER this.punishmentManager = ...:
this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.gui.GuiListener(), this);

// getter
public de.derfakegamer.sentinel.manager.ModerationService moderation() { return moderationService; }

// in reloadAll(), AFTER rebuilding punishmentManager:
this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
```

- [ ] **Step 8: Refactor `command/PunishmentCommands.java` to use the service**

Replace the inline execute/announce/kick with `plugin.moderation()` calls. Keep argument parsing, the OP gate, the `ipban-requires-online` guard, the `not-banned`/`not-muted` feedback, the `exempt` message on a false result, and `/history` rendering exactly as they are. Concretely:
- In the `ban,ipban,mute` case, after resolving `t` and `reason`, replace the `switch` + `announce` + `kickIfOnline` with:
```java
PunishmentType type = switch (cmd) { case "ban" -> PunishmentType.BAN; case "ipban" -> PunishmentType.IPBAN; default -> PunishmentType.MUTE; };
boolean applied = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason);
if (!applied) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
```
  (Keep the `ipban-requires-online` guard BEFORE this call.)
- In the `tempban,tempmute` case, after computing `expiresAt` and `reason`:
```java
PunishmentType type = cmd.equals("tempban") ? PunishmentType.BAN : PunishmentType.MUTE;
boolean applied = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, expiresAt, reason);
if (!applied) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
```
- In the `kick,warn` case:
```java
PunishmentType type = cmd.equals("kick") ? PunishmentType.KICK : PunishmentType.WARN;
boolean applied = plugin.moderation().apply(issuerId, issuerName, t.id, t.name, t.ip, type, 0, reason);
if (!applied) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
```
- In the `unban,unmute` case:
```java
boolean ok = cmd.equals("unban")
    ? plugin.moderation().removeBan(issuerId, issuerName, t.id, t.name)
    : plugin.moderation().removeMute(issuerId, issuerName, t.id, t.name);
if (!ok) sender.sendMessage(plugin.messages().prefixed(cmd.equals("unban") ? "not-banned" : "not-muted"));
return true;
```
- Delete the now-unused private helpers `kickIfOnline` and `announce` from `PunishmentCommands` (the service owns them now). Keep `resolve`, `usage`, `join`, and the `Target` record.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All Plan 1 tests (30) still pass, plus the 3 new `ModerationServiceTest` tests = 33.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: GUI framework, Items helper, and shared ModerationService"
```

---

## Task 2: ChatInputManager + chat-input flow

**Files:**
- Create: `manager/ChatInputManager.java`
- Modify: `listener/ChatListener.java`, `Sentinel.java`
- Test: extend `listener/ChatListenerTest.java` (add input tests)

- [ ] **Step 1: Write the failing test (append to `ChatListenerTest.java`)**

```java
    @Test
    void pendingChatInputIsCapturedAndCancelled() {
        org.mockbukkit.mockbukkit.entity.PlayerMock p = server.addPlayer("Mod");
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        plugin.chatInput().await(p.getUniqueId(), captured::set);

        io.papermc.paper.event.player.AsyncChatEvent event = chatEvent(p, "2d6h");
        new ChatListener(plugin).onChat(event);

        assertTrue(event.isCancelled(), "input message must not reach public chat");
        server.getScheduler().performTicks(2); // input callback runs on the main thread
        assertEquals("2d6h", captured.get());
        assertFalse(plugin.chatInput().has(p.getUniqueId()), "pending input is consumed");
    }

    @Test
    void cancelKeywordAbortsInput() {
        org.mockbukkit.mockbukkit.entity.PlayerMock p = server.addPlayer("Mod");
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        plugin.chatInput().await(p.getUniqueId(), captured::set);

        io.papermc.paper.event.player.AsyncChatEvent event = chatEvent(p, "cancel");
        new ChatListener(plugin).onChat(event);

        assertTrue(event.isCancelled());
        server.getScheduler().performTicks(2);
        assertNull(captured.get(), "cancel must NOT invoke the callback");
        assertFalse(plugin.chatInput().has(p.getUniqueId()));
    }
```

Reuse the existing `chatEvent(...)` helper the ChatListener test already defines for constructing an `AsyncChatEvent` (the one created in Plan 1 via `SignedMessage.system(...)` + `ChatRenderer.defaultRenderer()`). If that helper is currently inline inside a test method, extract it into a private method `private AsyncChatEvent chatEvent(PlayerMock p, String msg)` so both old and new tests share it.

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests ChatListenerTest`
Expected: FAIL — `plugin.chatInput()` / `ChatInputManager` does not exist.

- [ ] **Step 3: Write `manager/ChatInputManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Holds one pending chat-input callback per player, used by GUIs to collect durations/reasons. */
public final class ChatInputManager {
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public void await(UUID player, Consumer<String> callback) { pending.put(player, callback); }

    public boolean has(UUID player) { return pending.containsKey(player); }

    public void cancel(UUID player) { pending.remove(player); }

    /** Removes and returns the pending callback, or null if none. */
    public Consumer<String> consume(UUID player) { return pending.remove(player); }
}
```

- [ ] **Step 4: Modify `listener/ChatListener.java`**

The listener must consult pending input BEFORE the mute check. If input is pending: cancel the event; on the main thread, either drop it (message equals `cancel`, case-insensitive) or invoke the callback with the plain text. Replace the class body with:

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.function.Consumer;

public final class ChatListener implements Listener {
    private final Sentinel plugin;

    public ChatListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        if (plugin.chatInput().has(id)) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Consumer<String> callback = plugin.chatInput().consume(id);
            if (callback != null && !text.equalsIgnoreCase("cancel")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(text));
            }
            return;
        }

        Punishment mute = plugin.punishments().activeMute(id, System.currentTimeMillis());
        if (mute != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().prefixed("you-are-muted", "reason", mute.reason()));
        }
    }
}
```

- [ ] **Step 5: Wire `ChatInputManager` into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.ChatInputManager chatInputManager;

// in onEnable(), before registering listeners:
this.chatInputManager = new de.derfakegamer.sentinel.manager.ChatInputManager();

// getter
public de.derfakegamer.sentinel.manager.ChatInputManager chatInput() { return chatInputManager; }
```

(Do not rebuild it in `reloadAll()` — pending inputs should survive a config reload; it holds no config.)

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests ChatListenerTest`
Expected: PASS (4 tests: 2 original + 2 new).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: chat-input manager for GUI duration/reason entry"
```

---

## Task 3: ConfirmGui

**Files:**
- Create: `gui/ConfirmGui.java`
- Test: `gui/ConfirmGuiTest.java`

A 27-slot confirm dialog. Slot 11 = green confirm, slot 13 = summary item, slot 15 = red cancel. Confirm runs a supplied `Runnable` then closes; cancel closes (or re-opens a back GUI if provided).

- [ ] **Step 1: Write the failing test `ConfirmGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void confirmRunsActionAndIsCancelled() {
        PlayerMock p = server.addPlayer("Mod");
        AtomicBoolean ran = new AtomicBoolean(false);
        ConfirmGui gui = new ConfirmGui(plugin, Component.text("Ban Griefer?"), () -> ran.set(true), null);
        gui.open(p);

        InventoryClickEvent event = clickSlot(p, gui, 11); // confirm
        gui.onClick(event);

        assertTrue(event.isCancelled(), "GUI clicks must be cancelled");
        assertTrue(ran.get(), "confirm must run the action");
    }

    @Test void cancelDoesNotRunAction() {
        PlayerMock p = server.addPlayer("Mod");
        AtomicBoolean ran = new AtomicBoolean(false);
        ConfirmGui gui = new ConfirmGui(plugin, Component.text("Ban Griefer?"), () -> ran.set(true), null);
        gui.open(p);

        InventoryClickEvent event = clickSlot(p, gui, 15); // cancel
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertFalse(ran.get());
    }

    static InventoryClickEvent clickSlot(PlayerMock p, Gui gui, int slot) {
        return new InventoryClickEvent(p.openInventory(gui.getInventory()),
            InventoryType.SlotType.CONTAINER, slot, org.bukkit.event.inventory.ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    }
}
```

> **Note:** `p.openInventory(inv)` returns the `InventoryView` needed to build the click event. If MockBukkit's `InventoryClickEvent` constructor signature differs, adapt the `clickSlot` helper to whatever the installed API provides (it must produce a click on `slot` within `gui`'s inventory). Keep the assertions.

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests ConfirmGuiTest`
Expected: FAIL — `ConfirmGui` does not exist.

- [ ] **Step 3: Write `gui/ConfirmGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ConfirmGui extends Gui {
    private static final int CONFIRM = 11, SUMMARY = 13, CANCEL = 15;

    private final Runnable onConfirm;
    private final Gui back;

    public ConfirmGui(Sentinel plugin, Component summary, Runnable onConfirm, Gui back) {
        super(plugin);
        this.onConfirm = onConfirm;
        this.back = back;
        this.inventory = Bukkit.createInventory(this, 27,
            plugin.messages().plain("gui-confirm-title"));
        inventory.setItem(CONFIRM, Items.button(Material.LIME_WOOL,
            Component.text("Confirm"), List.of()));
        inventory.setItem(SUMMARY, Items.button(Material.PAPER, summary, List.of()));
        inventory.setItem(CANCEL, Items.button(Material.RED_WOOL,
            Component.text("Cancel"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        if (event.getRawSlot() == CONFIRM) {
            player.closeInventory();
            onConfirm.run();
        } else if (event.getRawSlot() == CANCEL) {
            if (back != null) back.open(player); else player.closeInventory();
        }
    }
}
```

- [ ] **Step 4: Add the title key to `messages.yml`**

```yaml
gui-confirm-title: "<#3B82F6>Sentinel · Confirm"
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests ConfirmGuiTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: confirmation GUI"
```

---

## Task 4: ReasonGui

**Files:**
- Create: `gui/ReasonGui.java`
- Test: `gui/ReasonGuiTest.java`

A 27-slot reason picker: the 5 config presets in slots 10–14, a custom (anvil/chat) option in slot 22, close in slot 26. Picking a preset opens a `ConfirmGui` whose action calls `ModerationService.apply(...)`. Picking custom closes the GUI and awaits chat input; the entered reason opens the `ConfirmGui` on the main thread.

- [ ] **Step 1: Write the failing test `ReasonGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class ReasonGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void presetHasFivePresetButtons() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        ReasonGui gui = new ReasonGui(plugin, target, null, PunishmentType.BAN, 0);
        // five presets in slots 10..14
        for (int slot = 10; slot <= 14; slot++)
            assertNotNull(gui.getInventory().getItem(slot), "preset at slot " + slot);
    }

    @Test void clickingPresetOpensConfirm() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        ReasonGui gui = new ReasonGui(plugin, target, null, PunishmentType.BAN, 0);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // first preset
        gui.onClick(event);

        assertTrue(event.isCancelled());
        // the moderator is now looking at a ConfirmGui
        assertInstanceOf(ConfirmGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests ReasonGuiTest`
Expected: FAIL — `ReasonGui` does not exist.

- [ ] **Step 3: Write `gui/ReasonGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class ReasonGui extends Gui {
    private static final int FIRST_PRESET = 10; // presets occupy 10..14
    private static final int CUSTOM = 22, CLOSE = 26;

    private final OfflinePlayer target;
    private final String ip;
    private final PunishmentType type;
    private final long expiresAt;
    private final List<String> presets;

    public ReasonGui(Sentinel plugin, OfflinePlayer target, String ip, PunishmentType type, long expiresAt) {
        super(plugin);
        this.target = target;
        this.ip = ip;
        this.type = type;
        this.expiresAt = expiresAt;
        this.presets = plugin.getConfig().getStringList("reasons");
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-reason-title"));
        for (int i = 0; i < 5; i++) {
            String label = i < presets.size() ? presets.get(i) : "—";
            inventory.setItem(FIRST_PRESET + i,
                Items.button(Material.PAPER, Component.text(label), List.of()));
        }
        inventory.setItem(CUSTOM, Items.button(Material.NAME_TAG,
            Component.text("Custom reason"), List.of(Component.text("Type it in chat"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot >= FIRST_PRESET && slot < FIRST_PRESET + 5) {
            int index = slot - FIRST_PRESET;
            if (index < presets.size()) openConfirm(mod, presets.get(index));
        } else if (slot == CUSTOM) {
            mod.closeInventory();
            mod.sendMessage(plugin.messages().prefixed("enter-reason"));
            plugin.chatInput().await(mod.getUniqueId(), reason -> openConfirm(mod, reason));
        } else if (slot == CLOSE) {
            mod.closeInventory();
        }
    }

    private void openConfirm(Player mod, String reason) {
        Component summary = plugin.messages().plain("confirm-summary",
            "type", type.name(), "player", target.getName() == null ? "?" : target.getName(), "reason", reason);
        Runnable action = () -> plugin.moderation().apply(
            mod.getUniqueId(), mod.getName(), target.getUniqueId(),
            target.getName() == null ? "?" : target.getName(), ip, type, expiresAt, reason);
        new ConfirmGui(plugin, summary, action, null).open(mod);
    }
}
```

- [ ] **Step 4: Add message keys to `messages.yml`**

```yaml
gui-reason-title: "<#3B82F6>Sentinel · Reason"
enter-reason: "<#60A5FA>Enter a reason in chat, or type <white>cancel<#60A5FA>."
confirm-summary: "<gray><type> <#60A5FA><player></#60A5FA><gray>: <white><reason>"
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests ReasonGuiTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: reason picker GUI"
```

---

## Task 5: PlayerActionsGui

**Files:**
- Create: `gui/PlayerActionsGui.java`
- Test: `gui/PlayerActionsGuiTest.java`

A 45-slot menu for one target. Row 1 (slot 4): target head + status. Row 2 (slots 10–15): Ban/Unban, Tempban, Mute/Unmute, Tempmute, Kick, Warn. Row 3 (slots 19, 22): IP-Ban, History. Row 5 (slot 36 Back, slot 44 Close). Freeze/Invsee/EChestSee are added in Plan 3 — leave their slots as filler for now.

Context-sensitive: if the target has an active ban, slot 10 shows "Unban" and clicking it calls `removeBan`; otherwise "Ban" opens `ReasonGui(BAN, 0)`. Same for mute at slot 12. Tempban/Tempmute close the GUI and await a duration in chat, then open `ReasonGui` with the parsed `expiresAt`. IP-Ban is only offered when the target is online (so an IP is known).

- [ ] **Step 1: Write the failing test `PlayerActionsGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayerActionsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void banButtonOpensReasonGui() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // Ban
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(ReasonGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void unbanButtonShownWhenBannedAndRemovesBan() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        plugin.punishments().ban(target.getUniqueId(), "Griefer", mod.getUniqueId(), "Mod", "x", 0);
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // now "Unban"
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()),
            "clicking Unban removes the active ban");
    }
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests PlayerActionsGuiTest`
Expected: FAIL — `PlayerActionsGui` does not exist.

- [ ] **Step 3: Write `gui/PlayerActionsGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class PlayerActionsGui extends Gui {
    private static final int HEAD = 4;
    private static final int BAN = 10, TEMPBAN = 11, MUTE = 12, TEMPMUTE = 13, KICK = 14, WARN = 15;
    private static final int IPBAN = 19, HISTORY = 22, BACK = 36, CLOSE = 44;

    private final OfflinePlayer target;
    private final boolean banned;
    private final boolean muted;

    public PlayerActionsGui(Sentinel plugin, OfflinePlayer target) {
        super(plugin);
        this.target = target;
        long now = System.currentTimeMillis();
        this.banned = plugin.punishments().activeBan(target.getUniqueId(), now) != null;
        this.muted = plugin.punishments().activeMute(target.getUniqueId(), now) != null;
        this.inventory = Bukkit.createInventory(this, 45,
            plugin.messages().plain("gui-actions-title", "player", name()));

        inventory.setItem(HEAD, Items.head(target, Component.text(name()),
            List.of(Component.text(banned ? "Banned" : "Not banned"),
                    Component.text(muted ? "Muted" : "Not muted"),
                    Component.text("Warns: " + plugin.punishments().warnCount(target.getUniqueId())))));

        inventory.setItem(BAN, Items.button(Material.BARRIER,
            Component.text(banned ? "Unban" : "Ban"), List.of()));
        inventory.setItem(TEMPBAN, Items.button(Material.CLOCK, Component.text("Tempban"), List.of()));
        inventory.setItem(MUTE, Items.button(Material.BOOK,
            Component.text(muted ? "Unmute" : "Mute"), List.of()));
        inventory.setItem(TEMPMUTE, Items.button(Material.CLOCK, Component.text("Tempmute"), List.of()));
        inventory.setItem(KICK, Items.button(Material.LEATHER_BOOTS, Component.text("Kick"), List.of()));
        inventory.setItem(WARN, Items.button(Material.YELLOW_BANNER, Component.text("Warn"), List.of()));
        if (target.isOnline())
            inventory.setItem(IPBAN, Items.button(Material.IRON_BARS, Component.text("IP-Ban"), List.of()));
        inventory.setItem(HISTORY, Items.button(Material.WRITABLE_BOOK, Component.text("History"), List.of()));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back"), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        fillEmpty();
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    private String ip() {
        Player online = target.getPlayer();
        return (online != null && online.getAddress() != null)
            ? online.getAddress().getAddress().getHostAddress() : null;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case BAN -> {
                if (banned) { plugin.moderation().removeBan(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()); mod.closeInventory(); }
                else new ReasonGui(plugin, target, null, PunishmentType.BAN, 0).open(mod);
            }
            case MUTE -> {
                if (muted) { plugin.moderation().removeMute(mod.getUniqueId(), mod.getName(), target.getUniqueId(), name()); mod.closeInventory(); }
                else new ReasonGui(plugin, target, null, PunishmentType.MUTE, 0).open(mod);
            }
            case TEMPBAN -> awaitDuration(mod, PunishmentType.BAN);
            case TEMPMUTE -> awaitDuration(mod, PunishmentType.MUTE);
            case KICK -> new ReasonGui(plugin, target, null, PunishmentType.KICK, 0).open(mod);
            case WARN -> new ReasonGui(plugin, target, null, PunishmentType.WARN, 0).open(mod);
            case IPBAN -> {
                String ip = ip();
                if (ip != null) new ReasonGui(plugin, target, ip, PunishmentType.IPBAN, 0).open(mod);
                else mod.sendMessage(plugin.messages().prefixed("ipban-requires-online"));
            }
            case HISTORY -> new HistoryGui(plugin, target, 0).open(mod);
            case BACK -> new PlayersGui(plugin, 0).open(mod);
            case CLOSE -> mod.closeInventory();
        }
    }

    private void awaitDuration(Player mod, PunishmentType type) {
        mod.closeInventory();
        mod.sendMessage(plugin.messages().prefixed("enter-duration"));
        plugin.chatInput().await(mod.getUniqueId(), input -> {
            long expiresAt;
            try { expiresAt = System.currentTimeMillis() + DurationParser.parse(input); }
            catch (IllegalArgumentException e) { mod.sendMessage(plugin.messages().prefixed("bad-duration")); return; }
            new ReasonGui(plugin, target, null, type, expiresAt).open(mod);
        });
    }
}
```

> **Note:** This references `HistoryGui` (Task 6) and `PlayersGui` (Task 7), which do not exist yet, so the project will not compile until those are written. That is expected with TDD ordering — write a minimal stub for `HistoryGui` and `PlayersGui` (a class extending `Gui` with an empty 9-slot inventory and a no-op `onClick`) ONLY if you need this task's test to compile before Tasks 6–7 land, then flesh them out in their own tasks. Simpler: implement Tasks 6 and 7 right after this one and run all three test classes together. If you stub, mark the task DONE_WITH_CONCERNS noting the stubs.

- [ ] **Step 4: Add message keys to `messages.yml`**

```yaml
gui-actions-title: "<#3B82F6>Sentinel · <player>"
enter-duration: "<#60A5FA>Enter a duration in chat (e.g. 30s, 10m, 3h, 8d, 2w), or type <white>cancel<#60A5FA>."
bad-duration: "<red>Invalid duration. Try again from the menu."
```

- [ ] **Step 5: Run tests (after Tasks 6–7 exist, or with stubs)**

Run: `./gradlew test --tests PlayerActionsGuiTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: player actions GUI"
```

---

## Task 6: HistoryGui

**Files:**
- Create: `gui/HistoryGui.java`
- Test: `gui/HistoryGuiTest.java`

A 54-slot paginated history. Slots 0–44 hold up to 45 entries (one item per `Punishment`, newest first), icon by type, lore with reason/issuer/date/status. Bottom row: slot 45 ◀ (page > 0), slot 49 Back (to PlayerActionsGui), slot 53 ▶ (more entries exist).

- [ ] **Step 1: Write the failing test `HistoryGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HistoryGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rendersOneItemPerPunishment() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        UUID t = target.getUniqueId();
        plugin.punishments().warn(t, "Griefer", mod.getUniqueId(), "Mod", "spam");
        plugin.punishments().warn(t, "Griefer", mod.getUniqueId(), "Mod", "spam again");

        HistoryGui gui = new HistoryGui(plugin, target, 0);
        int items = 0;
        for (int i = 0; i <= 44; i++)
            if (gui.getInventory().getItem(i) != null && gui.getInventory().getItem(i).getType() != org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS_PANE)
                items++;
        assertEquals(2, items);
    }

    @Test void emptyHistoryHasNoEntryItems() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Clean");
        HistoryGui gui = new HistoryGui(plugin, target, 0);
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            assertTrue(item == null || item.getType() == org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                "no real entries for a clean player");
        }
    }
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests HistoryGuiTest`
Expected: FAIL — `HistoryGui` does not exist.

- [ ] **Step 3: Write `gui/HistoryGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
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
import java.util.ArrayList;
import java.util.List;

public final class HistoryGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, BACK = 49, NEXT = 53;
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final OfflinePlayer target;
    private final int page;
    private final int total;

    public HistoryGui(Sentinel plugin, OfflinePlayer target, int page) {
        super(plugin);
        this.target = target;
        this.page = page;
        List<Punishment> all = plugin.punishments().history(target.getUniqueId());
        this.total = all.size();
        this.inventory = Bukkit.createInventory(this, 54,
            plugin.messages().plain("gui-history-title", "player", name()));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < all.size(); i++) {
            Punishment p = all.get(from + i);
            inventory.setItem(i, Items.button(iconFor(p.type()), Component.text(p.type().name()), List.of(
                Component.text("Reason: " + p.reason()),
                Component.text("By: " + p.issuerName()),
                Component.text("Date: " + DATE.format(Instant.ofEpochMilli(p.createdAt()))),
                Component.text(p.active() ? "Active" : "Removed/expired"))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous"), List.of()));
        inventory.setItem(BACK, Items.button(Material.BARRIER, Component.text("Back"), List.of()));
        if (from + PAGE_SIZE < total) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next"), List.of()));
        fillEmpty();
    }

    private String name() { return target.getName() == null ? "?" : target.getName(); }

    private Material iconFor(PunishmentType type) {
        return switch (type) {
            case BAN, IPBAN -> Material.RED_WOOL;
            case MUTE       -> Material.BOOK;
            case WARN       -> Material.YELLOW_BANNER;
            case KICK       -> Material.LEATHER_BOOTS;
        };
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case PREV -> new HistoryGui(plugin, target, page - 1).open(mod);
            case NEXT -> new HistoryGui(plugin, target, page + 1).open(mod);
            case BACK -> new PlayerActionsGui(plugin, target).open(mod);
            default -> {}
        }
    }
}
```

- [ ] **Step 4: Add the title key to `messages.yml`**

```yaml
gui-history-title: "<#3B82F6>Sentinel · History · <player>"
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests HistoryGuiTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: punishment history GUI"
```

---

## Task 7: PlayersGui + `/sentinel` entry point

**Files:**
- Create: `gui/PlayersGui.java`
- Modify: `command/SentinelCommand.java`
- Test: `gui/PlayersGuiTest.java`

A 54-slot paginated list of online players (heads in slots 0–44). Bottom row: slot 45 ◀ (page > 0), slot 49 Close, slot 53 ▶ (more than 45 players online). Clicking a head opens that player's `PlayerActionsGui`. (The Vanish / Reports / Staff-settings buttons from the spec are added in Plan 3.) `/sentinel` with no args opens page 0; `/sentinel <player>` opens that player's actions directly; `/sentinel reload` stays.

- [ ] **Step 1: Write the failing test `PlayersGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class PlayersGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void showsOnlinePlayersAsHeads() {
        server.addPlayer("Alice");
        server.addPlayer("Bob");
        PlayersGui gui = new PlayersGui(plugin, 0);
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var item = gui.getInventory().getItem(i);
            if (item != null && item.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(2, heads);
    }

    @Test void clickingHeadOpensActions() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayersGui gui = new PlayersGui(plugin, 0);
        gui.open(mod);
        // find the slot holding a head (Mod is the only online player)
        int slot = -1;
        for (int i = 0; i <= 44; i++)
            if (gui.getInventory().getItem(i) != null && gui.getInventory().getItem(i).getType() == Material.PLAYER_HEAD) { slot = i; break; }
        assertTrue(slot >= 0);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, slot);
        gui.onClick(event);

        assertTrue(event.isCancelled());
        assertInstanceOf(PlayerActionsGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
```

- [ ] **Step 2: Run it to verify failure**

Run: `./gradlew test --tests PlayersGuiTest`
Expected: FAIL — `PlayersGui` does not exist.

- [ ] **Step 3: Write `gui/PlayersGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class PlayersGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45, CLOSE = 49, NEXT = 53;

    private final int page;
    private final List<Player> players;

    public PlayersGui(Sentinel plugin, int page) {
        super(plugin);
        this.page = page;
        this.players = new ArrayList<>(Bukkit.getOnlinePlayers());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-players-title"));

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < players.size(); i++) {
            Player p = players.get(from + i);
            long now = System.currentTimeMillis();
            inventory.setItem(i, Items.head(p, Component.text(p.getName()), List.of(
                Component.text(plugin.punishments().activeMute(p.getUniqueId(), now) != null ? "Muted" : "Not muted"),
                Component.text("Warns: " + plugin.punishments().warnCount(p.getUniqueId())))));
        }
        if (page > 0) inventory.setItem(PREV, Items.button(Material.ARROW, Component.text("Previous"), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close"), List.of()));
        if (from + PAGE_SIZE < players.size()) inventory.setItem(NEXT, Items.button(Material.ARROW, Component.text("Next"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player mod = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == PREV) { new PlayersGui(plugin, page - 1).open(mod); return; }
        if (slot == NEXT) { new PlayersGui(plugin, page + 1).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        int index = page * PAGE_SIZE + slot;
        if (slot >= 0 && slot < PAGE_SIZE && index < players.size()) {
            new PlayerActionsGui(plugin, players.get(index)).open(mod);
        }
    }
}
```

- [ ] **Step 4: Add the title key to `messages.yml`**

```yaml
gui-players-title: "<#3B82F6>Sentinel · Players"
```

- [ ] **Step 5: Update `command/SentinelCommand.java`**

`/sentinel` opens the Players GUI; `/sentinel reload` unchanged; `/sentinel <player>` opens that player's actions. Replace `onCommand` with:

```java
@Override
public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                         @NotNull String label, @NotNull String[] args) {
    if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
    if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
        plugin.reloadAll();
        sender.sendMessage(plugin.messages().prefixed("reloaded"));
        return true;
    }
    if (!(sender instanceof org.bukkit.entity.Player mod)) {
        sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/sentinel reload"));
        return true;
    }
    if (args.length == 1) {
        org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(args[0]);
        new de.derfakegamer.sentinel.gui.PlayerActionsGui(plugin, target).open(mod);
    } else {
        new de.derfakegamer.sentinel.gui.PlayersGui(plugin, 0).open(mod);
    }
    return true;
}
```

- [ ] **Step 6: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All tests pass: Plan 1 (30) + ModerationService (3) + ChatListener input (2 new) + ConfirmGui (2) + ReasonGui (2) + PlayerActionsGui (2) + HistoryGui (2) + PlayersGui (2). Shaded jar produced.

- [ ] **Step 7: Manual in-game smoke test (one MC 1.21.x server)**

1. `/sentinel` → Players GUI opens with online heads.
2. Click a head → Player Actions. Click Ban → Reason GUI → pick a preset → Confirm → player is banned and kicked.
3. Click Tempban → type `1h` in chat → Reason GUI → Custom → type a reason → Confirm → temp ban applied.
4. Open a player's History → entries show with type/reason/date.
5. Ban an already-banned player's menu shows "Unban"; clicking it unbans.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: players GUI and /sentinel GUI entry point"
```

---

## Self-Review Notes (plan vs. spec)

- **Spec coverage (Plan 2 scope):** Players GUI ✓ (Task 7), Player Actions with context-sensitive Ban/Unban + Mute/Unmute ✓ (Task 5), Reason GUI with 5 presets + custom chat input ✓ (Task 4), Confirmation GUI guarding misclicks ✓ (Task 3), History GUI ✓ (Task 6), chat-input duration/reason flow with `cancel` ✓ (Task 2), blue theme + English MiniMessage titles ✓, `/sentinel [player]` entry ✓ (Task 7). Deferred to Plan 3: the Players-GUI Vanish/Reports/Staff buttons, Freeze, Invsee, EChestSee (their slots are left as filler here). Deferred to Plan 4: auto-updater.
- **DRY:** `ModerationService` is the single execute+announce+kick path for both commands and the Confirm GUI (Task 1 refactors the commands onto it).
- **Type consistency:** GUIs all extend `Gui` (holder-based routing via `GuiListener`); `ReasonGui(plugin, OfflinePlayer, String ip, PunishmentType, long expiresAt)`, `ConfirmGui(plugin, Component, Runnable, Gui back)`, `PlayerActionsGui(plugin, OfflinePlayer)`, `HistoryGui(plugin, OfflinePlayer, int page)`, `PlayersGui(plugin, int page)` signatures are used consistently across tasks. New accessors on `Sentinel`: `moderation()`, `chatInput()`.
- **Testing caveat:** MockBukkit `InventoryClickEvent`/`InventoryView` construction (the shared `ConfirmGuiTest.clickSlot` helper) may need adapting to the installed API — flagged in Task 3. The chat-input tests rely on `server.getScheduler().performTicks(...)` because the callback is dispatched to the main thread.
- **Compile ordering:** `PlayerActionsGui` (Task 5) references `HistoryGui` and `PlayersGui` (Tasks 6–7); implement Tasks 5–7 as a group and run their tests together, or stub then fill.
```
