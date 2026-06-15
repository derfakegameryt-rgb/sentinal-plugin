# Sentinel — Plan 12: GUI Polish, Live Specs & Unified Subcommands

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal (GUI is the focus):** Make every GUI cleaner and consistently laid out, sort the lists, and make the Admin Panel's server info refresh live every second. Secondary: every feature is also reachable as a `/sentinel <x>` / `/sn <x>` subcommand.

**Architecture:** A shared GUI layout helper (`Gui.border()` + a standard bottom nav bar) gives every menu a framed, centered look and every list a fixed Back/Close/page bar. Player-based lists sort alphabetically. `ServerInfoGui` starts a 1-second repeating refresh while open. `SentinelCommand` dispatches recognized subcommands to the existing commands.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, MiniMessage, JUnit 5 + MockBukkit 4.110.0).

---

## Task 1 (FOCUS): GUI layout system + cleanup

**Files:**
- Modify: `gui/Gui.java`, `util/Items.java`, and the GUI classes
- Test: existing GUI tests stay green; add `gui/GuiLayoutTest.java`

- [ ] **Step 1: Add layout helpers to `Gui.java`**

```java
/** Fills the outer border (top row, bottom row, left+right columns) with gray-glass filler. */
protected void border() {
    int size = inventory.getSize();
    int rows = size / 9;
    for (int c = 0; c < 9; c++) { set(c); set((rows - 1) * 9 + c); }       // top + bottom rows
    for (int r = 0; r < rows; r++) { set(r * 9); set(r * 9 + 8); }          // left + right columns
}
private void set(int slot) { if (inventory.getItem(slot) == null) inventory.setItem(slot, Items.filler()); }

/** Standard bottom nav for paginated list GUIs (54-slot): prev (45), back (48), close (50), next (53). */
protected void navBar(boolean hasPrev, boolean hasNext) {
    for (int i = 45; i <= 53; i++) if (inventory.getItem(i) == null) inventory.setItem(i, Items.filler());
    if (hasPrev) inventory.setItem(45, Items.button(org.bukkit.Material.ARROW,
        net.kyori.adventure.text.Component.text("Previous", net.kyori.adventure.text.format.NamedTextColor.GRAY), java.util.List.of()));
    inventory.setItem(48, Items.button(org.bukkit.Material.ARROW,
        net.kyori.adventure.text.Component.text("Back", net.kyori.adventure.text.format.NamedTextColor.GRAY), java.util.List.of()));
    inventory.setItem(50, Items.button(org.bukkit.Material.BARRIER,
        net.kyori.adventure.text.Component.text("Close", net.kyori.adventure.text.format.NamedTextColor.RED), java.util.List.of()));
    if (hasNext) inventory.setItem(53, Items.button(org.bukkit.Material.ARROW,
        net.kyori.adventure.text.Component.text("Next", net.kyori.adventure.text.format.NamedTextColor.GRAY), java.util.List.of()));
}
```

> NOTE: Changing nav slots would break existing tests that click fixed slots. To keep this SAFE, do NOT renumber existing list GUIs' Back/Close/Prev/Next constants. Instead: (a) apply `border()` to the 27-slot MENU GUIs (AdminPanelGui, OwnerPanelGui, OrbitalModeGui, OrbitalPayloadGui, OrbitalWhenGui, ServerInfoGui, ConfirmGui) so they get a clean frame with centered content; (b) leave the 54-slot LIST GUIs' nav slots as they are but ensure their whole bottom row (45-53) is gray-glass filler (most already are via `fillEmpty()`). The `navBar` helper above is OPTIONAL for any NEW gui; existing GUIs keep their slots. Focus the visual change on borders + centering + sorting, NOT slot renumbering.

- [ ] **Step 2: Frame the menu GUIs**

In each 27-slot menu GUI (`AdminPanelGui`, `OwnerPanelGui`, `OrbitalModeGui`, `OrbitalPayloadGui`, `OrbitalWhenGui`, `ServerInfoGui`), call `border()` right before `fillEmpty()`, and make sure the action buttons sit in the centered interior slots (10–16). Where a menu currently uses interior slots already (e.g. 11/13/15), keep them. `ConfirmGui` (27-slot): keep confirm=11/summary=13/cancel=15 and call `border()`.

This must not move any slot an existing TEST clicks. Check each menu GUI's test before changing its slots; if a test clicks slot N, keep slot N. (AdminPanelGui test clicks 10–14, OwnerPanelGui test clicks 11, OrbitalFlow clicks 10/12/14, ConfirmGui test clicks 11/15 — all in the interior, unaffected by `border()`.)

- [ ] **Step 3: Sort the player lists alphabetically**

- `PlayersGui`: sort `players` by name (case-insensitive) before paginating.
- `OperatorsGui`: sort `ops` by name (nulls last).
- `OrbitalUsersGui`: sort `users` by value (name).
- `SearchResultsGui`: results already filtered; sort by name.
Use `list.sort(java.util.Comparator.comparing(... , String.CASE_INSENSITIVE_ORDER))` with null-safe name extraction.

- [ ] **Step 4: Add `gui/GuiLayoutTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void menuGuiHasGrayBorder() {
        AdminPanelGui gui = new AdminPanelGui(plugin);
        // corners of a 27-slot menu are gray-glass border
        for (int slot : new int[]{0, 8, 18, 26})
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, gui.getInventory().getItem(slot).getType());
    }

    @Test void playersListIsSorted() {
        server.addPlayer("Zebra");
        server.addPlayer("alpha");
        PlayersGui gui = new PlayersGui(plugin, 0);
        // first head should be "alpha" (case-insensitive sort)
        var first = gui.getInventory().getItem(0);
        assertNotNull(first);
        assertEquals(Material.PLAYER_HEAD, first.getType());
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(first.getItemMeta().displayName()).equalsIgnoreCase("alpha"));
    }
}
```

- [ ] **Step 5: Run the full suite + commit**

```bash
./gradlew build
git add -A && git commit -m "polish: framed menu GUIs and sorted player lists"
```

---

## Task 2: Live server-spec refresh (every 1 second)

**Files:**
- Modify: `gui/ServerInfoGui.java`
- Test: `gui/ServerInfoGuiTest.java`

- [ ] **Step 1: Split the dynamic info into an `update()` method**

Refactor `ServerInfoGui` so the items that change (TPS, memory, players, uptime) are written in a public `update()` method (called from the constructor too). Keep the static items (version, OS, worlds) as-is.

- [ ] **Step 2: Start a 1-second refresher while the GUI is open**

Override `open(Player)`:

```java
@Override
public void open(Player player) {
    super.open(player);
    new org.bukkit.scheduler.BukkitRunnable() {
        @Override public void run() {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof ServerInfoGui g) || g != ServerInfoGui.this) {
                cancel(); return;            // closed or navigated away
            }
            update();
        }
    }.runTaskTimer(plugin, 20L, 20L);        // every 1 second
}
```

(`update()` re-reads TPS/memory/players/uptime and `inventory.setItem(...)`s them; the open inventory updates in place.)

- [ ] **Step 3: Test `gui/ServerInfoGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class ServerInfoGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void buildsAndUpdatesWithoutError() {
        ServerInfoGui gui = new ServerInfoGui(plugin);
        assertNotNull(gui.getInventory().getItem(12)); // memory item present
        gui.update();                                  // manual refresh does not throw
        assertNotNull(gui.getInventory().getItem(12));
    }
}
```

- [ ] **Step 4: Run tests + commit**

```bash
git add -A && git commit -m "feat: live 1-second refresh of the server info GUI"
```

---

## Task 3: Unified `/sentinel` / `/sn` subcommands

**Files:**
- Modify: `command/SentinelCommand.java`
- Test: `command/SubcommandTest.java`

- [ ] **Step 1: Dispatch recognized subcommands**

In `SentinelCommand.onCommand`, after the `owner`/`reload`/`update` branches and BEFORE the player-name → GUI fallback, add: if `args[0]` (lowercased) is a known feature command, delegate to it via the server's command dispatcher with the remaining args.

```java
private static final java.util.Set<String> SUBCOMMANDS = java.util.Set.of(
    "ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn",
    "shadowmute","unshadowmute","history","sc","clearchat","maintenance",
    "broadcast","bc","restart","playtime","report","rules");

// in onCommand, after reload/update/owner handling:
if (args.length >= 1 && SUBCOMMANDS.contains(args[0].toLowerCase())) {
    String rest = args.length > 1 ? " " + String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
    plugin.getServer().dispatchCommand(sender, args[0].toLowerCase() + rest);
    return true;
}
```

(`dispatchCommand` reuses each target command's own permission + logic — no duplication. The player-name GUI fallback still works because real player names aren't in `SUBCOMMANDS`.)

- [ ] **Step 2: Tab-complete the subcommands**

In `onTabComplete` for `/sentinel`, when completing `args[0]`, also include the subcommand names (plus the existing `reload`, `update`, online player names, and `owner` only for the owner). For `args.length == 2`, if `args[0]` is a player-targeting subcommand (ban/mute/kick/warn/etc.), suggest online player names. Reuse the existing `filter(...)` helper.

- [ ] **Step 3: Test `command/SubcommandTest.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SubcommandTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void snBanDelegatesToBan() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        PlayerMock target = server.addPlayer("Griefer");
        new SentinelCommand(plugin).onCommand(op, server.getCommandMap().getCommand("sentinel"),
            "sentinel", new String[]{"ban", "Griefer", "cheating"});
        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()));
    }

    @Test void subcommandsAppearInTab() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        var out = new SentinelCommand(plugin).onTabComplete(op,
            server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"ma"});
        assertTrue(out.contains("maintenance"));
    }
}
```

- [ ] **Step 4: Run the FULL suite + commit**

```bash
./gradlew build
git add -A && git commit -m "feat: unified /sentinel and /sn subcommands for every feature"
```

- [ ] **Step 5: Manual smoke test**

1. Open the Admin Panel → Server Info → values (TPS, RAM, players, uptime) tick up every second.
2. Every menu GUI has a clean gray frame; player lists are alphabetical.
3. `/sn ban X reason`, `/sn maintenance`, `/sn broadcast hi`, `/sn restart 1m`, `/sn playtime` all work like the standalone commands; tab-completion lists them.

---

## Self-Review Notes

- **GUI focus:** framed menu GUIs (`border()`), sorted player lists, consistent gray filler; no existing nav slot renumbered (tests stay green).
- **Live specs:** `ServerInfoGui.update()` on a 1-second `BukkitRunnable` that self-cancels when the viewer closes/navigates away.
- **Subcommands:** `/sentinel <x>` / `/sn <x>` dispatch to existing commands via `dispatchCommand` (reuses their permission + logic); tab-complete includes them. Owner already runs all of these without OP (sentinel.use) from Plan 11's grant.
- **Testing caveats:** the 1-second refresher uses the scheduler (not ticked in tests — `update()` is tested directly); GUI-border/sort and subcommand dispatch are unit-tested.
```
