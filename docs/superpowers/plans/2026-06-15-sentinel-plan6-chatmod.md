# Sentinel — Plan 6: Chat Moderation, Warn Escalation, Sounds & Search

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automated chat moderation (slowmode, anti-spam, anti-advertising, word filter, `/clearchat`), automatic punishment escalation after N warnings, click sounds in the GUIs, and a player search in the Players GUI.

**Architecture:** A config-driven `ChatModeration` evaluates each normal chat message (operators bypass) and the existing `ChatListener` enforces the outcome (block or censor). A `WarnEscalation` reads a config ladder and `ModerationService` applies the mapped punishment when a warn count crosses a threshold. `GuiListener` plays a UI sound on click. A `SearchResultsGui` reachable from a new search button in `PlayersGui` lists matching online + stored players.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0).

---

## File Structure

```
src/main/java/de/derfakegamer/sentinel/
  manager/ChatModeration.java      NEW  evaluate(): slowmode/spam/ads/word-filter
  manager/WarnEscalation.java      NEW  actionFor(count) from config ladder
  model/EscalationAction.java      NEW  record (type, durationMs, reason)
  manager/ModerationService.java   MOD  trigger escalation after a WARN
  listener/ChatListener.java       MOD  run chat moderation for normal messages
  command/ClearChatCommand.java    NEW  /clearchat (cc)
  gui/GuiListener.java             MOD  play a click sound (takes Sentinel)
  gui/PlayersGui.java              MOD  search button at slot 46
  gui/SearchResultsGui.java        NEW  matching players
  Sentinel.java                    MOD  build managers, register, getters
  resources/config.yml             MOD  chat:, warn-actions:, gui: sections
  resources/plugin.yml             MOD  declare clearchat (alias cc)
  resources/messages.yml           MOD  new keys
src/test/java/de/derfakegamer/sentinel/
  manager/ChatModerationTest.java      NEW
  manager/WarnEscalationTest.java      NEW
  gui/SearchResultsGuiTest.java        NEW
  command/ClearChatCommandTest.java    NEW
```

---

## Task 1: Chat moderation

**Files:**
- Modify: `config.yml`, `messages.yml`
- Create: `manager/ChatModeration.java`
- Modify: `listener/ChatListener.java`, `Sentinel.java`
- Test: `manager/ChatModerationTest.java`

- [ ] **Step 1: Add the `chat:` section to `config.yml`**

```yaml
chat:
  slowmode-seconds: 0          # 0 = off; minimum gap between a player's messages
  anti-spam:
    enabled: true
    max-repeats: 3             # blocked after the same message this many times in a row
  anti-advertising:
    enabled: true
    whitelist: []              # domains/IPs that are allowed (substring match)
  word-filter:
    enabled: true
    mode: block                # block | censor
    words: []                  # words to block/censor (case-insensitive)
```

- [ ] **Step 2: Add message keys to `messages.yml`**

```yaml
chat-slowmode: "<red>Slow down — you're chatting too fast."
chat-blocked-spam: "<red>Please don't repeat the same message."
chat-blocked-ad: "<red>Advertising is not allowed here."
chat-blocked-word: "<red>Watch your language."
```

- [ ] **Step 3: Write the failing test `ChatModerationTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatModerationTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private ChatModeration fresh() { return new ChatModeration(plugin); }

    @Test void cleanMessageIsAllowed() {
        assertEquals(ChatModeration.Action.ALLOW, fresh().evaluate(UUID.randomUUID(), "hello there", 1000).action());
    }

    @Test void repeatedMessageIsBlocked() {
        ChatModeration cm = fresh();
        UUID id = UUID.randomUUID();
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "spam", 1000).action());
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "spam", 2000).action());
        assertEquals(ChatModeration.Action.BLOCK, cm.evaluate(id, "spam", 3000).action()); // 3rd repeat
    }

    @Test void advertisingIsBlocked() {
        assertEquals(ChatModeration.Action.BLOCK,
            fresh().evaluate(UUID.randomUUID(), "join play.hypixel.net now", 1000).action());
    }

    @Test void slowmodeBlocksFastSecondMessage() {
        plugin.getConfig().set("chat.slowmode-seconds", 5);
        ChatModeration cm = fresh();
        UUID id = UUID.randomUUID();
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "a", 1000).action());
        assertEquals(ChatModeration.Action.BLOCK, cm.evaluate(id, "b", 2000).action()); // < 5s later
        assertEquals(ChatModeration.Action.ALLOW, cm.evaluate(id, "c", 7000).action());  // > 5s later
    }

    @Test void wordFilterCensors() {
        plugin.getConfig().set("chat.word-filter.mode", "censor");
        plugin.getConfig().set("chat.word-filter.words", java.util.List.of("badword"));
        ChatModeration cm = fresh();
        var out = cm.evaluate(UUID.randomUUID(), "you are a badword", 1000);
        assertEquals(ChatModeration.Action.CENSOR, out.action());
        assertFalse(out.censored().contains("badword"));
    }
}
```

- [ ] **Step 4: Run it → fails (no `ChatModeration`).**

- [ ] **Step 5: Write `manager/ChatModeration.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Evaluates a normal chat message against the configured chat rules. Operators bypass this. */
public final class ChatModeration {
    public enum Action { ALLOW, BLOCK, CENSOR }

    public record Outcome(Action action, String messageKey, String censored) {
        static Outcome allow() { return new Outcome(Action.ALLOW, null, null); }
        static Outcome block(String key) { return new Outcome(Action.BLOCK, key, null); }
        static Outcome censor(String text) { return new Outcome(Action.CENSOR, null, text); }
    }

    private static final Pattern ADVERT = Pattern.compile(
        "(?i)\\b((https?://)?[\\w-]+\\.[a-z]{2,}(/\\S*)?|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?)\\b");

    private final long slowmodeMs;
    private final boolean antiSpam; private final int maxRepeats;
    private final boolean antiAd; private final List<String> whitelist;
    private final boolean wordFilter; private final boolean censorMode; private final List<String> words;

    private final Map<UUID, Long> lastTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMsg = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> repeats = new ConcurrentHashMap<>();

    public ChatModeration(Sentinel plugin) {
        var c = plugin.getConfig();
        this.slowmodeMs = Math.max(0, c.getLong("chat.slowmode-seconds", 0)) * 1000L;
        this.antiSpam = c.getBoolean("chat.anti-spam.enabled", true);
        this.maxRepeats = Math.max(2, c.getInt("chat.anti-spam.max-repeats", 3));
        this.antiAd = c.getBoolean("chat.anti-advertising.enabled", true);
        this.whitelist = c.getStringList("chat.anti-advertising.whitelist");
        this.wordFilter = c.getBoolean("chat.word-filter.enabled", true);
        this.censorMode = c.getString("chat.word-filter.mode", "block").equalsIgnoreCase("censor");
        this.words = c.getStringList("chat.word-filter.words");
    }

    public Outcome evaluate(UUID id, String message, long now) {
        if (slowmodeMs > 0) {
            Long last = lastTime.get(id);
            if (last != null && now - last < slowmodeMs) return Outcome.block("chat-slowmode");
        }
        if (antiSpam) {
            if (message.equalsIgnoreCase(lastMsg.get(id))) {
                int n = repeats.merge(id, 1, Integer::sum);
                if (n + 1 >= maxRepeats) return Outcome.block("chat-blocked-spam");
            } else {
                repeats.put(id, 0);
            }
        }
        if (antiAd && ADVERT.matcher(message).find() && !whitelisted(message))
            return Outcome.block("chat-blocked-ad");
        if (wordFilter && !words.isEmpty()) {
            if (censorMode) {
                String censored = censor(message);
                if (!censored.equals(message)) { accept(id, message, now); return Outcome.censor(censored); }
            } else if (containsBannedWord(message)) {
                return Outcome.block("chat-blocked-word");
            }
        }
        accept(id, message, now);
        return Outcome.allow();
    }

    private void accept(UUID id, String message, long now) {
        lastTime.put(id, now);
        lastMsg.put(id, message);
    }

    private boolean whitelisted(String message) {
        String low = message.toLowerCase();
        for (String w : whitelist) if (!w.isBlank() && low.contains(w.toLowerCase())) return true;
        return false;
    }

    private boolean containsBannedWord(String message) {
        String low = message.toLowerCase();
        for (String w : words) if (!w.isBlank() && low.contains(w.toLowerCase())) return true;
        return false;
    }

    private String censor(String message) {
        String out = message;
        for (String w : words) {
            if (w.isBlank()) continue;
            out = out.replaceAll("(?i)" + Pattern.quote(w), "*".repeat(w.length()));
        }
        return out;
    }
}
```

- [ ] **Step 6: Enforce in `listener/ChatListener.java`**

After the existing mute block, add (so it only runs for a normal, not-yet-cancelled message from a non-op):

```java
if (!event.isCancelled() && !event.getPlayer().isOp()) {
    String text = PlainTextComponentSerializer.plainText().serialize(event.message());
    ChatModeration.Outcome outcome = plugin.chatModeration().evaluate(id, text, System.currentTimeMillis());
    switch (outcome.action()) {
        case BLOCK -> {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().prefixed(outcome.messageKey()));
        }
        case CENSOR -> event.message(net.kyori.adventure.text.Component.text(outcome.censored()));
        case ALLOW -> {}
    }
}
```

Add the import `import de.derfakegamer.sentinel.manager.ChatModeration;`.

- [ ] **Step 7: Wire into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.ChatModeration chatModeration;

// in onEnable() (after config is loaded) and in reloadAll() (after reloadConfig):
this.chatModeration = new de.derfakegamer.sentinel.manager.ChatModeration(this);

// getter
public de.derfakegamer.sentinel.manager.ChatModeration chatModeration() { return chatModeration; }
```

(Rebuild it in `reloadAll()` so config edits take effect on `/sentinel reload`.)

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests ChatModerationTest --tests ChatListenerTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: configurable chat moderation (slowmode, anti-spam, anti-ad, word filter)"
```

---

## Task 2: /clearchat command

**Files:**
- Modify: `plugin.yml`, `messages.yml`
- Create: `command/ClearChatCommand.java`
- Modify: `Sentinel.java`
- Test: `command/ClearChatCommandTest.java`

- [ ] **Step 1: Declare the command in `plugin.yml`**

```yaml
  clearchat: { description: Clear the chat, aliases: [cc] }
```

- [ ] **Step 2: Add a message key to `messages.yml`**

```yaml
chat-cleared: "<#60A5FA>Chat cleared by <player>."
```

- [ ] **Step 3: Write the failing test `ClearChatCommandTest.java`**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class ClearChatCommandTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void opCanClearChat() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        boolean handled = server.dispatchCommand(op, "clearchat");
        assertTrue(handled);
    }

    @Test void nonOpIsRejected() {
        PlayerMock p = server.addPlayer("Player");
        ClearChatCommand cmd = new ClearChatCommand(plugin);
        cmd.onCommand(p, server.getCommandMap().getCommand("clearchat"), "clearchat", new String[0]);
        // a non-op should get the no-permission message and no exception
        assertNotNull(p.nextMessage());
    }
}
```

> **Note:** If `server.dispatchCommand` routing differs, invoke `new ClearChatCommand(plugin).onCommand(op, ...)` directly. Keep both an op-success and a non-op-rejection assertion.

- [ ] **Step 4: Write `command/ClearChatCommand.java`**

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

public final class ClearChatCommand implements CommandExecutor {
    private final Sentinel plugin;

    public ClearChatCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) continue; // keep staff's view; clear for everyone else
            for (int i = 0; i < 100; i++) p.sendMessage(Component.empty());
        }
        Bukkit.broadcast(plugin.messages().prefixed("chat-cleared", "player", sender.getName()));
        return true;
    }
}
```

- [ ] **Step 5: Register in `Sentinel.java` `onEnable()`**

```java
getCommand("clearchat").setExecutor(new de.derfakegamer.sentinel.command.ClearChatCommand(this));
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests ClearChatCommandTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: /clearchat command"
```

---

## Task 3: Warn escalation

**Files:**
- Modify: `config.yml`, `messages.yml`
- Create: `model/EscalationAction.java`, `manager/WarnEscalation.java`
- Modify: `manager/ModerationService.java`, `Sentinel.java`
- Test: `manager/WarnEscalationTest.java`

- [ ] **Step 1: Add the ladder to `config.yml`**

```yaml
warn-actions:            # warns count -> action: "<kick|ban|tempban|mute|tempmute> [duration] <reason>"
  3: "tempban 1d Reached 3 warnings"
  5: "ban Reached 5 warnings"
```

- [ ] **Step 2: Write `model/EscalationAction.java`**

```java
package de.derfakegamer.sentinel.model;

public record EscalationAction(PunishmentType type, long durationMs, String reason) {}
```

- [ ] **Step 3: Write the failing test `WarnEscalationTest.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.EscalationAction;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class WarnEscalationTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void noActionBelowThreshold() {
        plugin.getConfig().set("warn-actions.3", "ban too many warns");
        assertNull(new WarnEscalation(plugin).actionFor(2));
    }

    @Test void parsesBanAction() {
        plugin.getConfig().set("warn-actions.3", "ban too many warns");
        EscalationAction a = new WarnEscalation(plugin).actionFor(3);
        assertNotNull(a);
        assertEquals(PunishmentType.BAN, a.type());
        assertEquals(0, a.durationMs());
        assertEquals("too many warns", a.reason());
    }

    @Test void parsesTempbanWithDuration() {
        plugin.getConfig().set("warn-actions.5", "tempban 1d serial offender");
        EscalationAction a = new WarnEscalation(plugin).actionFor(5);
        assertEquals(PunishmentType.BAN, a.type());
        assertEquals(86_400_000L, a.durationMs());
        assertEquals("serial offender", a.reason());
    }
}
```

> Note: tempban/tempmute map to `PunishmentType.BAN`/`MUTE` with a non-zero `durationMs` (there is no separate TEMPBAN type — duration distinguishes them, matching how the rest of the plugin works).

- [ ] **Step 4: Write `manager/WarnEscalation.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.EscalationAction;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;

public final class WarnEscalation {
    private final Sentinel plugin;

    public WarnEscalation(Sentinel plugin) { this.plugin = plugin; }

    /** Returns the action configured for exactly this warn count, or null. */
    public EscalationAction actionFor(int warnCount) {
        String spec = plugin.getConfig().getString("warn-actions." + warnCount);
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.trim().split("\\s+");
        String type = parts[0].toLowerCase();
        return switch (type) {
            case "kick"     -> new EscalationAction(PunishmentType.KICK, 0, rest(parts, 1));
            case "ban"      -> new EscalationAction(PunishmentType.BAN, 0, rest(parts, 1));
            case "mute"     -> new EscalationAction(PunishmentType.MUTE, 0, rest(parts, 1));
            case "tempban"  -> temp(PunishmentType.BAN, parts);
            case "tempmute" -> temp(PunishmentType.MUTE, parts);
            default -> null;
        };
    }

    private EscalationAction temp(PunishmentType type, String[] parts) {
        if (parts.length < 2) return null;
        long ms;
        try { ms = DurationParser.parse(parts[1]); } catch (IllegalArgumentException e) { return null; }
        return new EscalationAction(type, ms, rest(parts, 2));
    }

    private String rest(String[] parts, int from) {
        return from >= parts.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }
}
```

- [ ] **Step 5: Trigger escalation in `manager/ModerationService.java`**

After a successful WARN in `apply(...)` (right before `return true;` when `type == WARN`), check the ladder and apply the mapped punishment. Modify the end of `apply`:

```java
// after: Bukkit.broadcast(...) and the kick block, before `return true;`
if (type == PunishmentType.WARN) {
    int count = plugin.punishments().warnCount(targetId);
    de.derfakegamer.sentinel.model.EscalationAction esc = plugin.escalation().actionFor(count);
    if (esc != null) {
        long expiresAt = esc.durationMs() == 0 ? 0 : System.currentTimeMillis() + esc.durationMs();
        apply(issuerId, issuerName, targetId, targetName, ip, esc.type(), expiresAt, esc.reason());
    }
}
return true;
```

(The recursive `apply` is for a different type — BAN/MUTE/KICK — so it does not re-trigger escalation.)

- [ ] **Step 6: Wire `WarnEscalation` into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.manager.WarnEscalation warnEscalation;

// in onEnable() and reloadAll():
this.warnEscalation = new de.derfakegamer.sentinel.manager.WarnEscalation(this);

// getter
public de.derfakegamer.sentinel.manager.WarnEscalation escalation() { return warnEscalation; }
```

- [ ] **Step 7: Run tests + an integration check**

Run: `./gradlew test --tests WarnEscalationTest --tests ModerationServiceTest`
Expected: PASS. (ModerationServiceTest still green — escalation only fires when `warn-actions` is configured, which the default test config does at counts 3/5; a single warn in those tests stays below threshold.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: warn escalation ladder"
```

---

## Task 4: GUI click sounds

**Files:**
- Modify: `config.yml`, `gui/GuiListener.java`, `Sentinel.java`
- Test: optional (see step 4)

- [ ] **Step 1: Add the `gui:` section to `config.yml`**

```yaml
gui:
  sound: true
  sound-name: UI_BUTTON_CLICK
```

- [ ] **Step 2: Update `gui/GuiListener.java` to take the plugin and play a sound**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class GuiListener implements Listener {
    private final Sentinel plugin;

    public GuiListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            playClick(event.getWhoClicked());
            gui.onClick(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) {
            int topSize = event.getInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) gui.onClose(event);
    }

    private void playClick(org.bukkit.entity.HumanEntity who) {
        if (!plugin.getConfig().getBoolean("gui.sound", true)) return;
        if (!(who instanceof Player p)) return;
        try {
            Sound sound = Sound.valueOf(plugin.getConfig().getString("gui.sound-name", "UI_BUTTON_CLICK"));
            p.playSound(p.getLocation(), sound, 0.4f, 1.0f);
        } catch (IllegalArgumentException ignored) { /* unknown sound name in config */ }
    }
}
```

- [ ] **Step 3: Update registration in `Sentinel.java`**

Change `new de.derfakegamer.sentinel.gui.GuiListener()` to `new de.derfakegamer.sentinel.gui.GuiListener(this)`.

- [ ] **Step 4: Verify (light test)**

The existing GUI tests construct GUIs and call `gui.onClick(...)` directly (not via `GuiListener`), so they are unaffected. Add ONE test only if MockBukkit's `PlayerMock` supports sound assertions; otherwise rely on the build. If adding: in a new or existing GUI test, register isn't needed — just confirm the plugin loads and a click via the real listener doesn't throw. Skip if `assertSoundHeard` isn't available; note it.

- [ ] **Step 5: Run the suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all prior tests still green (GuiListener constructor change only affects `Sentinel.java`).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: GUI click sounds"
```

---

## Task 5: Player search in the Players GUI

**Files:**
- Create: `gui/SearchResultsGui.java`
- Modify: `gui/PlayersGui.java`, `messages.yml`
- Test: `gui/SearchResultsGuiTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
gui-search-title: "<#3B82F6>Sentinel · Search"
enter-search: "<#60A5FA>Type a player name to search, or type <white>cancel<#60A5FA>."
```

- [ ] **Step 2: Write the failing test `SearchResultsGuiTest.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class SearchResultsGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void findsOnlinePlayerByPartialName() {
        server.addPlayer("Notch");
        server.addPlayer("Alex");
        SearchResultsGui gui = new SearchResultsGui(plugin, "not");
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(1, heads); // only Notch matches "not"
    }

    @Test void findsStoredOfflinePlayer() {
        java.util.UUID id = java.util.UUID.randomUUID();
        plugin.players().record(id, "OfflineGuy", "1.2.3.4");
        SearchResultsGui gui = new SearchResultsGui(plugin, "offlineguy");
        int heads = 0;
        for (int i = 0; i <= 44; i++) {
            var it = gui.getInventory().getItem(i);
            if (it != null && it.getType() == Material.PLAYER_HEAD) heads++;
        }
        assertEquals(1, heads);
    }
}
```

- [ ] **Step 3: Write `gui/SearchResultsGui.java`**

```java
package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PlayerRecord;
import de.derfakegamer.sentinel.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SearchResultsGui extends Gui {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45, CLOSE = 53;

    private final List<OfflinePlayer> results = new ArrayList<>();

    public SearchResultsGui(Sentinel plugin, String query) {
        super(plugin);
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().plain("gui-search-title"));
        String low = query.toLowerCase();

        Map<UUID, OfflinePlayer> found = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase().contains(low)) found.put(p.getUniqueId(), p);
        PlayerRecord stored = plugin.players().byName(query);
        if (stored != null) found.putIfAbsent(stored.uuid(), Bukkit.getOfflinePlayer(stored.uuid()));

        results.addAll(found.values());
        for (int i = 0; i < PAGE_SIZE && i < results.size(); i++) {
            OfflinePlayer op = results.get(i);
            String name = op.getName() != null ? op.getName() : query;
            inventory.setItem(i, Items.head(op, Component.text(name, NamedTextColor.AQUA),
                List.of(Component.text("Click to open actions", NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
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
        if (slot == BACK) { new PlayersGui(plugin, 0).open(mod); return; }
        if (slot == CLOSE) { mod.closeInventory(); return; }
        if (slot >= 0 && slot < PAGE_SIZE && slot < results.size())
            new PlayerActionsGui(plugin, results.get(slot)).open(mod);
    }
}
```

- [ ] **Step 4: Add a search button to `gui/PlayersGui.java`**

Add a `SEARCH = 46` constant. In the constructor (bottom-row block), set the button:

```java
inventory.setItem(SEARCH, Items.button(Material.OAK_SIGN,
    Component.text("Search", NamedTextColor.AQUA),
    List.of(Component.text("Find a player by name", NamedTextColor.GRAY)
        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
```

In `onClick`, add (before the head-index handling):

```java
if (slot == SEARCH) {
    mod.closeInventory();
    mod.sendMessage(plugin.messages().prefixed("enter-search"));
    plugin.chatInput().await(mod.getUniqueId(), q -> new SearchResultsGui(plugin, q).open(mod));
    return;
}
```

Add the imports `NamedTextColor` and `TextDecoration` if not present.

- [ ] **Step 5: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green. Shaded jar produced.

- [ ] **Step 6: Manual smoke test**

1. As a non-op, send the same message 3× → blocked; post `play.example.net` → blocked; (with a configured word) → blocked/censored; chat fast with slowmode on → blocked.
2. `/clearchat` (or `/cc`) → chat clears for non-staff.
3. Configure `warn-actions: {2: "kick test"}`, `/warn X test` twice → X is auto-kicked on the 2nd.
4. Click any GUI button → click sound plays.
5. `/sentinel` → Search → type a name → matching players (online + seen offline) show → click → their actions.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: player search in the Players GUI"
```

---

## Self-Review Notes (plan vs. requirements)

- **Chat moderation** ✓ (Task 1): slowmode, anti-spam (repeat), anti-advertising (URL/IP + whitelist), word filter (block/censor); OP bypass; config-driven, rebuilt on reload. `/clearchat` ✓ (Task 2).
- **Warn escalation** ✓ (Task 3): config ladder, applied via `ModerationService` after a warn; tempban/tempmute via duration.
- **GUI sounds** ✓ (Task 4): config-toggled click sound in `GuiListener`.
- **Player search** ✓ (Task 5): search button → chat query → results GUI (online + stored offline).
- **Type consistency:** new `Sentinel` accessors `chatModeration()`, `escalation()`. `GuiListener` now requires the plugin (only `Sentinel.java` constructs it). `PlayersGui` gains `SEARCH = 46` (was filler — no existing test clicks 46). `ChatModeration.Outcome`/`Action` used by `ChatListener`.
- **Reload:** `ChatModeration` and `WarnEscalation` are rebuilt in `reloadAll()` so `/sentinel reload` picks up config edits.
- **Testing caveats:** `server.dispatchCommand` routing (Task 2) and `PlayerMock` sound assertions (Task 4) flagged inline.
```
