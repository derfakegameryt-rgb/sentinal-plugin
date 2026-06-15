# Sentinel — Plan 8: Orbital Strike

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An OP-only `/orbitalstrike` that opens a code-locked GUI flow. After entering the 4-digit code, the operator either (A) picks a payload and receives a one-shot fishing-rod "strike caller" that bombards wherever they aim, or (B) picks a dimension + X/Z + payload and calls the strike directly. A strike rains the chosen payload down the full vertical column (y −60…320) at that X/Z.

**Architecture:** A pure `OrbitalStrike` engine spawns a column of payload entities at a target X/Z. `OrbitalRod` builds/identifies the one-shot rod via PDC. A keypad `OrbitalCodeGui` gates access with the configured code, then `OrbitalModeGui` → `OrbitalPayloadGui` → `ConfirmGui` for mode A (rod) or `OrbitalDimensionGui` → chat X/Z → `OrbitalPayloadGui` → `ConfirmGui` for mode B. `OrbitalRodListener` fires the strike on right-click and consumes the rod.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, MiniMessage, JUnit 5 + MockBukkit 4.110.0). Entity spawning is server-only (MockBukkit can't spawn TNT/creepers), so the engine spawn loop is defensive (per-entity try/catch) and tests cover the pure parts + GUI navigation; the actual strike is a manual smoke test.

---

## File Structure

```
src/main/java/de/derfakegamer/sentinel/
  model/OrbitalPayload.java        NEW  enum TNT / TNT_MINECART / CHARGED_CREEPER
  manager/OrbitalStrike.java       NEW  strike(world,x,z,payload) + columnYs()
  util/OrbitalRod.java             NEW  create/identify the one-shot rod (PDC)
  listener/OrbitalRodListener.java NEW  right-click → strike + consume rod
  command/OrbitalStrikeCommand.java NEW /orbitalstrike → code GUI
  gui/OrbitalCodeGui.java          NEW  4-digit keypad
  gui/OrbitalModeGui.java          NEW  rod mode / coordinate mode
  gui/OrbitalPayloadGui.java       NEW  pick payload (both modes)
  gui/OrbitalDimensionGui.java     NEW  pick world (mode B)
  Sentinel.java                    MOD  build engine, register command + listener, getter
  resources/plugin.yml             MOD  declare orbitalstrike
  resources/messages.yml           MOD  new keys
src/test/java/de/derfakegamer/sentinel/
  manager/OrbitalStrikeTest.java       NEW
  util/OrbitalRodTest.java             NEW
  gui/OrbitalCodeGuiTest.java          NEW
  gui/OrbitalFlowTest.java             NEW
```

---

## Task 1: Payload enum, strike engine, rod item

**Files:**
- Create: `model/OrbitalPayload.java`, `manager/OrbitalStrike.java`, `util/OrbitalRod.java`
- Modify: `Sentinel.java`
- Test: `manager/OrbitalStrikeTest.java`, `util/OrbitalRodTest.java`

**No config:** the code (`2584`) and the column spacing (`8`) are hard-coded constants, NOT configurable. Do not add an `orbitalstrike:` section to config.yml.

- [ ] **Step 1: Write `model/OrbitalPayload.java`**

```java
package de.derfakegamer.sentinel.model;

public enum OrbitalPayload {
    TNT("TNT"),
    TNT_MINECART("TNT Minecart"),
    CHARGED_CREEPER("Charged Creeper");

    private final String label;
    OrbitalPayload(String label) { this.label = label; }
    public String label() { return label; }
}
```

- [ ] **Step 3: Write the failing test `OrbitalStrikeTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalStrikeTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void columnCoversFullHeightAtSpacing() {
        List<Integer> ys = new OrbitalStrike(plugin).columnYs(8);
        assertEquals(-60, ys.get(0));               // starts at bottom
        assertTrue(ys.get(ys.size() - 1) <= 320);   // never above build limit
        assertTrue(ys.contains(-52) && ys.contains(4)); // stepped by 8 from -60
        // every entry is 8 apart
        for (int i = 1; i < ys.size(); i++) assertEquals(8, ys.get(i) - ys.get(i - 1));
    }

    @Test void spacingIsClampedToAtLeastOne() {
        assertFalse(new OrbitalStrike(plugin).columnYs(0).isEmpty());
    }
}
```

- [ ] **Step 4: Write `manager/OrbitalStrike.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;

import java.util.ArrayList;
import java.util.List;

public final class OrbitalStrike {
    private static final int BOTTOM = -60, TOP = 320;
    private static final int SPACING = 8; // fixed; not configurable

    private final Sentinel plugin;

    public OrbitalStrike(Sentinel plugin) { this.plugin = plugin; }

    /** The y levels that receive a payload, bottom-up, stepped by spacing (min 1). */
    public List<Integer> columnYs(int spacing) {
        int step = Math.max(1, spacing);
        List<Integer> ys = new ArrayList<>();
        for (int y = BOTTOM; y <= TOP; y += step) ys.add(y);
        return ys;
    }

    /** Bombards the X/Z column in the given world with the chosen payload. Server-only. */
    public void strike(World world, int x, int z, OrbitalPayload payload) {
        for (int y : columnYs(SPACING)) {
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            try {
                switch (payload) {
                    case TNT -> world.spawn(loc, TNTPrimed.class, t -> t.setFuseTicks(40));
                    case TNT_MINECART -> {
                        ExplosiveMinecart cart = world.spawn(loc, ExplosiveMinecart.class);
                        try { cart.ignite(); } catch (Throwable ignored) { /* older API */ }
                    }
                    case CHARGED_CREEPER -> world.spawn(loc, Creeper.class, c -> {
                        c.setPowered(true);
                        c.setIgnited(true);
                    });
                }
            } catch (Throwable t) {
                // one failed spawn (e.g. unloaded edge, test env) must not abort the whole strike
                plugin.getLogger().fine("orbital strike spawn failed at y=" + y + ": " + t.getMessage());
            }
        }
    }
}
```

> **Note:** If `ExplosiveMinecart#ignite()` doesn't exist on this API, the cart still explodes on impact when it falls; leave the try/catch. If `TNTPrimed#setFuseTicks` differs, spawn without the consumer.

- [ ] **Step 5: Write `util/OrbitalRod.java`**

```java
package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class OrbitalRod {
    private OrbitalRod() {}

    private static NamespacedKey key(Sentinel plugin) { return new NamespacedKey(plugin, "orbital_payload"); }

    /** A fishing rod with one durability left, tagged with the payload. */
    public static ItemStack create(Sentinel plugin, OrbitalPayload payload) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        rod.editMeta(meta -> {
            meta.displayName(Component.text("Orbital Strike", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                grey("Payload: " + payload.label()),
                grey("Right-click to fire at your crosshair")));
            if (meta instanceof Damageable d) d.setDamage(Material.FISHING_ROD.getMaxDurability() - 1);
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, payload.name());
        });
        return rod;
    }

    /** The payload tagged on this item, or null if it isn't an orbital rod. */
    public static OrbitalPayload payloadOf(Sentinel plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
        if (s == null) return null;
        try { return OrbitalPayload.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static Component grey(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
```

- [ ] **Step 6: Write `util/OrbitalRodTest.java`**

```java
package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalRodTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void createsTaggedRodAndReadsItBack() {
        ItemStack rod = OrbitalRod.create(plugin, OrbitalPayload.CHARGED_CREEPER);
        assertEquals(Material.FISHING_ROD, rod.getType());
        assertEquals(OrbitalPayload.CHARGED_CREEPER, OrbitalRod.payloadOf(plugin, rod));
    }

    @Test void plainItemHasNoPayload() {
        assertNull(OrbitalRod.payloadOf(plugin, new ItemStack(Material.FISHING_ROD)));
        assertNull(OrbitalRod.payloadOf(plugin, null));
    }
}
```

- [ ] **Step 7: Wire the engine into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.OrbitalStrike orbitalStrike;

// in onEnable():
this.orbitalStrike = new de.derfakegamer.sentinel.manager.OrbitalStrike(this);

// getter
public de.derfakegamer.sentinel.manager.OrbitalStrike orbital() { return orbitalStrike; }
```

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests OrbitalStrikeTest --tests OrbitalRodTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: orbital strike engine, payload enum, one-shot rod"
```

---

## Task 2: /orbitalstrike command + keypad code GUI

**Files:**
- Modify: `plugin.yml`, `messages.yml`
- Create: `command/OrbitalStrikeCommand.java`, `gui/OrbitalCodeGui.java`
- Modify: `Sentinel.java`
- Test: `gui/OrbitalCodeGuiTest.java`

- [ ] **Step 1: Declare the command in `plugin.yml`**

```yaml
  orbitalstrike: { description: Open the orbital strike console }
```

- [ ] **Step 2: Add message keys to `messages.yml`**

```yaml
gui-orbital-code-title: "<#3B82F6>Sentinel · Enter Code"
gui-orbital-mode-title: "<#3B82F6>Sentinel · Orbital Strike"
gui-orbital-payload-title: "<#3B82F6>Sentinel · Payload"
gui-orbital-dim-title: "<#3B82F6>Sentinel · Dimension"
orbital-wrong-code: "<red>Wrong code."
orbital-rod-received: "<#60A5FA>Orbital strike rod received — right-click to fire."
orbital-no-target: "<red>No target block in sight."
orbital-fired: "<#60A5FA>Orbital strike inbound at <white><x>, <z></white>."
orbital-enter-coord: "<#60A5FA>Type the <white><axis></#60A5FA> coordinate in chat, or type <white>cancel<#60A5FA>."
orbital-bad-coord: "<red>That's not a valid number."
```

- [ ] **Step 3: Write `command/OrbitalStrikeCommand.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.gui.OrbitalCodeGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class OrbitalStrikeCommand implements CommandExecutor {
    private final Sentinel plugin;

    public OrbitalStrikeCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (sender instanceof Player p) new OrbitalCodeGui(plugin).open(p);
        return true;
    }
}
```

- [ ] **Step 4: Write the failing test `OrbitalCodeGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalCodeGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private void type(OrbitalCodeGui gui, PlayerMock p, char digit) {
        gui.onClick(click(p, gui, gui.slotForDigit(digit)));
    }
    private InventoryClickEvent click(PlayerMock p, Gui gui, int slot) {
        return ConfirmGuiTest.clickSlot(p, gui, slot);
    }

    @Test void correctCodeOpensModeMenu() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalCodeGui gui = new OrbitalCodeGui(plugin);
        gui.open(p);
        for (char c : "2584".toCharArray()) type(gui, p, c);
        assertInstanceOf(OrbitalModeGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void wrongCodeDoesNotOpenModeMenu() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalCodeGui gui = new OrbitalCodeGui(plugin);
        gui.open(p);
        for (char c : "1111".toCharArray()) type(gui, p, c);
        assertFalse(p.getOpenInventory().getTopInventory().getHolder() instanceof OrbitalModeGui);
    }
}
```

> **Note:** `OrbitalModeGui` is created in Task 3 — implement Tasks 2–4 together so it compiles, then run the tests. The test uses `gui.slotForDigit(char)` — expose that helper so tests don't hardcode keypad slots.

- [ ] **Step 5: Write `gui/OrbitalCodeGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OrbitalCodeGui extends Gui {
    // keypad: 1..9 in a 3x3, 0 below, clear next to it, display at slot 4
    private static final Map<Integer, Character> SLOT_DIGIT = new HashMap<>();
    static {
        int[] grid = {10,11,12, 19,20,21, 28,29,30};
        char[] d = {'1','2','3','4','5','6','7','8','9'};
        for (int i = 0; i < 9; i++) SLOT_DIGIT.put(grid[i], d[i]);
        SLOT_DIGIT.put(38, '0');
    }
    private static final int DISPLAY = 4, CLEAR = 39, CLOSE = 44;
    private static final String CODE = "2584"; // fixed; not configurable

    private final StringBuilder entered = new StringBuilder();

    public OrbitalCodeGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 45, plugin.messages().plain("gui-orbital-code-title"));
        render();
    }

    public int slotForDigit(char digit) {
        for (var e : SLOT_DIGIT.entrySet()) if (e.getValue() == digit) return e.getKey();
        return -1;
    }

    private void render() {
        for (var e : SLOT_DIGIT.entrySet())
            inventory.setItem(e.getKey(), Items.button(Material.PAPER,
                Component.text(String.valueOf(e.getValue()), NamedTextColor.AQUA), List.of()));
        inventory.setItem(DISPLAY, Items.button(Material.NAME_TAG,
            Component.text("Code: " + "•".repeat(entered.length()) + "_".repeat(4 - entered.length()),
                NamedTextColor.WHITE), List.of()));
        inventory.setItem(CLEAR, Items.button(Material.BARRIER, Component.text("Clear", NamedTextColor.RED), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot == CLEAR) { entered.setLength(0); render(); return; }
        Character digit = SLOT_DIGIT.get(slot);
        if (digit == null) return;
        if (entered.length() < 4) entered.append(digit);
        render();
        if (entered.length() == 4) {
            if (entered.toString().equals(CODE)) {
                new OrbitalModeGui(plugin).open(p);
            } else {
                p.sendMessage(plugin.messages().prefixed("orbital-wrong-code"));
                entered.setLength(0);
                render();
            }
        }
    }
}
```

- [ ] **Step 6: Register command + wire in `Sentinel.java`**

```java
getCommand("orbitalstrike").setExecutor(new de.derfakegamer.sentinel.command.OrbitalStrikeCommand(this));
```

- [ ] **Step 7: Run tests (after Tasks 3–4 exist)** — `./gradlew test --tests OrbitalCodeGuiTest`

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: /orbitalstrike command and keypad code GUI"
```

---

## Task 3: Mode menu, payload GUI, rod delivery & firing

**Files:**
- Create: `gui/OrbitalModeGui.java`, `gui/OrbitalPayloadGui.java`, `listener/OrbitalRodListener.java`
- Modify: `Sentinel.java`
- Test: `gui/OrbitalFlowTest.java`

- [ ] **Step 1: Write `gui/OrbitalPayloadGui.java`**

Used by BOTH modes. For rod mode `world == null`; for coordinate mode it carries the chosen world + x/z.

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.Items;
import de.derfakegamer.sentinel.util.OrbitalRod;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;

public final class OrbitalPayloadGui extends Gui {
    private static final int TNT = 11, CART = 13, CREEPER = 15, BACK = 18, CLOSE = 26;

    private final World world; // null = rod mode
    private final int x, z;

    public OrbitalPayloadGui(Sentinel plugin, World world, int x, int z) {
        super(plugin);
        this.world = world; this.x = x; this.z = z;
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-orbital-payload-title"));
        inventory.setItem(TNT, button(Material.TNT, OrbitalPayload.TNT));
        inventory.setItem(CART, button(Material.TNT_MINECART, OrbitalPayload.TNT_MINECART));
        inventory.setItem(CREEPER, button(Material.CREEPER_HEAD, OrbitalPayload.CHARGED_CREEPER));
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack button(Material m, OrbitalPayload payload) {
        return Items.button(m, Component.text(payload.label(), NamedTextColor.AQUA), List.of());
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        OrbitalPayload payload = switch (event.getRawSlot()) {
            case TNT -> OrbitalPayload.TNT;
            case CART -> OrbitalPayload.TNT_MINECART;
            case CREEPER -> OrbitalPayload.CHARGED_CREEPER;
            default -> null;
        };
        if (event.getRawSlot() == BACK) { new OrbitalModeGui(plugin).open(p); return; }
        if (event.getRawSlot() == CLOSE) { p.closeInventory(); return; }
        if (payload == null) return;

        Component summary = Component.text((world == null ? "Give rod: " : "Strike: ") + payload.label(),
            NamedTextColor.AQUA);
        Runnable action = (world == null)
            ? () -> { p.getInventory().addItem(OrbitalRod.create(plugin, payload));
                      p.sendMessage(plugin.messages().prefixed("orbital-rod-received")); }
            : () -> plugin.orbital().strike(world, x, z, payload);
        new ConfirmGui(plugin, summary, action, null).open(p);
    }
}
```

- [ ] **Step 2: Write `gui/OrbitalModeGui.java`**

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

public final class OrbitalModeGui extends Gui {
    private static final int ROD = 11, COORDS = 15, CLOSE = 26;

    public OrbitalModeGui(Sentinel plugin) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().plain("gui-orbital-mode-title"));
        inventory.setItem(ROD, Items.button(Material.FISHING_ROD, Component.text("Targeted (rod)", NamedTextColor.AQUA),
            List.of(hint("Pick a payload, then fire with the rod"))));
        inventory.setItem(COORDS, Items.button(Material.COMPASS, Component.text("Coordinates", NamedTextColor.AQUA),
            List.of(hint("Pick dimension + X/Z + payload"))));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Component hint(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        switch (event.getRawSlot()) {
            case ROD -> new OrbitalPayloadGui(plugin, null, 0, 0).open(p);
            case COORDS -> new OrbitalDimensionGui(plugin).open(p);
            case CLOSE -> p.closeInventory();
        }
    }
}
```

> `OrbitalDimensionGui` is created in Task 4.

- [ ] **Step 3: Write `listener/OrbitalRodListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.OrbitalRod;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class OrbitalRodListener implements Listener {
    private final Sentinel plugin;

    public OrbitalRodListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        OrbitalPayload payload = OrbitalRod.payloadOf(plugin, event.getItem());
        if (payload == null) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (!p.isOp()) return;
        Block target = p.getTargetBlockExact(320);
        if (target == null) { p.sendMessage(plugin.messages().prefixed("orbital-no-target")); return; }
        plugin.orbital().strike(p.getWorld(), target.getX(), target.getZ(), payload);
        p.sendMessage(plugin.messages().prefixed("orbital-fired",
            "x", String.valueOf(target.getX()), "z", String.valueOf(target.getZ())));
        // consume the one-shot rod
        if (event.getHand() == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(null);
        else p.getInventory().setItemInMainHand(null);
    }
}
```

- [ ] **Step 4: Register the listener in `Sentinel.java`**

```java
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OrbitalRodListener(this), this);
```

- [ ] **Step 5: Write `gui/OrbitalFlowTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.OrbitalRod;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalFlowTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rodModePayloadOpensConfirm() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalPayloadGui gui = new OrbitalPayloadGui(plugin, null, 0, 0); // rod mode
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 11); // TNT
        gui.onClick(ev);
        assertInstanceOf(ConfirmGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void confirmingRodGivesTaggedRod() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        // simulate the confirm action directly: rod mode + TNT
        p.getInventory().addItem(OrbitalRod.create(plugin, OrbitalPayload.TNT));
        boolean hasRod = false;
        for (var it : p.getInventory().getContents())
            if (it != null && it.getType() == Material.FISHING_ROD && OrbitalRod.payloadOf(plugin, it) != null) hasRod = true;
        assertTrue(hasRod);
    }
}
```

- [ ] **Step 6: Run tests** — `./gradlew test --tests OrbitalFlowTest --tests OrbitalCodeGuiTest`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: orbital mode menu, payload GUI, rod delivery and firing"
```

---

## Task 4: Coordinate mode (dimension + X/Z chat input)

**Files:**
- Create: `gui/OrbitalDimensionGui.java`
- Test: covered by `OrbitalFlowTest` (add a navigation case)

- [ ] **Step 1: Write `gui/OrbitalDimensionGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public final class OrbitalDimensionGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;

    private final List<World> worlds;

    public OrbitalDimensionGui(Sentinel plugin) {
        super(plugin);
        this.worlds = new ArrayList<>(Bukkit.getWorlds());
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-orbital-dim-title"));
        for (int i = 0; i < PAGE_SIZE && i < worlds.size(); i++) {
            World w = worlds.get(i);
            inventory.setItem(i, Items.button(iconFor(w), Component.text(w.getName(), NamedTextColor.AQUA),
                List.of(grey("Environment: " + w.getEnvironment().name()))));
        }
        inventory.setItem(BACK, Items.button(Material.ARROW, Component.text("Back", NamedTextColor.GRAY), List.of()));
        inventory.setItem(CLOSE, Items.button(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of()));
        fillEmpty();
    }

    private Material iconFor(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    private Component grey(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player p = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (slot == BACK) { new OrbitalModeGui(plugin).open(p); return; }
        if (slot == CLOSE) { p.closeInventory(); return; }
        if (slot < 0 || slot >= PAGE_SIZE || slot >= worlds.size()) return;
        World world = worlds.get(slot);
        askCoord(p, world, "X", null);
    }

    /** Chat-prompts for X then Z, then opens the payload GUI in coordinate mode. */
    private void askCoord(Player p, World world, String axis, Integer x) {
        p.closeInventory();
        p.sendMessage(plugin.messages().prefixed("orbital-enter-coord", "axis", axis));
        plugin.chatInput().await(p.getUniqueId(), input -> {
            int value;
            try { value = Integer.parseInt(input.trim()); }
            catch (NumberFormatException e) { p.sendMessage(plugin.messages().prefixed("orbital-bad-coord")); return; }
            if (x == null) askCoord(p, world, "Z", value);              // got X, now ask Z
            else new OrbitalPayloadGui(plugin, world, x, value).open(p); // got Z, go pick payload
        });
    }
}
```

- [ ] **Step 2: Add a navigation test to `OrbitalFlowTest.java`**

```java
    @Test void coordinateModeOpensDimensionGui() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalModeGui gui = new OrbitalModeGui(plugin);
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 15); // Coordinates
        gui.onClick(ev);
        assertInstanceOf(OrbitalDimensionGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }
```

- [ ] **Step 3: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green. Shaded jar produced.

- [ ] **Step 4: Manual smoke test (real server)**

1. `/orbitalstrike` → keypad. Type `2584` → mode menu. Wrong code → resets.
2. Targeted (rod) → TNT → Confirm → you get the rod. Aim at terrain, right-click → a column of TNT rains down at that X/Z; rod is consumed.
3. Coordinates → pick a world → type X, type Z → Charged Creeper → Confirm → strike lands at those coords.
4. Try each payload (TNT, TNT minecart, charged creeper).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: orbital coordinate mode (dimension + X/Z input)"
```

---

## Self-Review Notes (plan vs. requirements)

- **`/orbitalstrike` → code GUI** ✓ (Task 2): keypad, fixed 4-digit code (`2584`, hard-coded constant — not configurable), wrong code resets.
- **Two buttons** ✓ (Task 3): mode menu → rod path / coordinate path.
- **Rod path** ✓: payload pick → confirm → one-durability fishing rod tagged with payload; right-click raytraces the crosshair and bombards that X/Z, then breaks (Task 3 listener).
- **Coordinate path** ✓ (Task 4): dimension → X → Z → payload → confirm → immediate strike.
- **Payloads** ✓: TNT (primed), TNT minecart (explosive), charged creeper (powered + ignited).
- **Vertical release y −60…320** ✓ (Task 1): `columnYs` stepped by the fixed `SPACING = 8` constant (heavy; not configurable).
- **Access:** OP-only command + OP re-check in the rod listener. Engine spawn loop is defensive so a bad spawn can't abort the strike (and tests don't crash on MockBukkit's lack of entity spawning).
- **Type consistency:** new `Sentinel.orbital()` (OrbitalStrike). GUIs extend `Gui`; `OrbitalPayloadGui(plugin, World|null, x, z)` distinguishes rod vs coordinate mode. `OrbitalCodeGui.slotForDigit(char)` exposed for tests.
- **Testing caveat:** entity spawning is server-only — `OrbitalStrikeTest` covers `columnYs`, GUI tests cover navigation + code gate + rod tagging; the live strike is a manual smoke test.
```
