# Sentinel — Plan 13: Management (Cron, Backup, Whitelist, AFK) + Audit Hardening

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scheduled tasks (cron-lite), a world backup command, a whitelist management GUI, AFK detection + auto-kick, and fix the remaining audit item (typo'd offline names creating phantom UUIDs).

**Architecture:** A `CronManager` runs config-defined timed actions; `BackupManager` zips worlds async; a `WhitelistGui` manages Bukkit's whitelist; an `AfkManager` + activity listener auto-kicks idle players. `PunishmentCommands.resolve()` now rejects never-seen names via `PlayerDirectory`.

**Tech Stack:** Same as before (Java 21, Paper 1.21.11 API, SQLite, MiniMessage, JUnit 5 + MockBukkit 4.110.0). New OP commands gated by `sentinel.use`.

---

## Task 1: Audit fix — reject phantom offline names

**Files:** Modify `command/PunishmentCommands.java`. Test: extend `PunishmentCommandsTest`.

- [ ] **Step 1: Rewrite `resolve(...)`**

```java
private Target resolve(CommandSender sender, String name) {
    org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
        String ip = online.getAddress() != null ? online.getAddress().getAddress().getHostAddress() : null;
        return new Target(online.getUniqueId(), online.getName(), ip);
    }
    de.derfakegamer.sentinel.model.PlayerRecord rec = plugin.players().byName(name);
    if (rec == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return null; }
    return new Target(rec.uuid(), rec.name(), rec.lastIp());
}
```

(Online → real UUID/IP; offline → the stored record (real UUID + last IP); never-seen → rejected. This also removes the now-redundant inline offline-IP lookup elsewhere in the class if present — leave other logic intact.)

- [ ] **Step 2: Test**

```java
@Test void unknownOfflineNameIsRejected() {
    PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
    boolean handled = new PunishmentCommands(plugin).onCommand(op,
        server.getCommandMap().getCommand("ban"), "ban", new String[]{"NeverSeen", "x"});
    assertTrue(handled);
    // nothing was banned for a synthesized uuid
    assertEquals(0, plugin.punishments().activeList(de.derfakegamer.sentinel.model.PunishmentType.BAN, System.currentTimeMillis()).size());
}
```

- [ ] **Step 3: Run tests + commit** — `git commit -m "fix: reject never-seen player names instead of synthesizing a UUID"`

---

## Task 2: AFK detection + auto-kick

**Files:** Create `manager/AfkManager.java`, `listener/ActivityListener.java`. Modify `config.yml`, `messages.yml`, `Sentinel.java`. Test: `manager/AfkManagerTest.java`.

- [ ] **Step 1: Config + message**

config.yml:
```yaml
afk:
  enabled: true
  kick-minutes: 15     # kick a player idle this long (0 = never)
```
messages.yml: `afk-kicked: "<#3B82F6>Sentinel\n<gray>Kicked for being AFK."`

- [ ] **Step 2: `manager/AfkManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AfkManager {
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    public void bump(UUID uuid) { lastActivity.put(uuid, System.currentTimeMillis()); }
    public void forget(UUID uuid) { lastActivity.remove(uuid); }

    /** Idle milliseconds, or 0 if never seen. */
    public long idleMs(UUID uuid, long now) {
        Long t = lastActivity.get(uuid);
        return t == null ? 0 : now - t;
    }
}
```

- [ ] **Step 3: `listener/ActivityListener.java`** — bump on join/move(block-change)/chat/command

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

public final class ActivityListener implements Listener {
    private final Sentinel plugin;
    public ActivityListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler public void onJoin(PlayerJoinEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { plugin.afk().forget(e.getPlayer().getUniqueId()); }
    @EventHandler public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) { plugin.afk().bump(e.getPlayer().getUniqueId()); }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()))
            plugin.afk().bump(e.getPlayer().getUniqueId());
    }
}
```

- [ ] **Step 4: Wire + auto-kick checker in `Sentinel.java`**

Build `afkManager`, add `afk()` getter, register `ActivityListener`. Add a repeating check (every 30s) that kicks idle non-OP/non-owner players:

```java
this.afkManager = new de.derfakegamer.sentinel.manager.AfkManager();
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ActivityListener(this), this);
getServer().getScheduler().runTaskTimer(this, () -> {
    if (!getConfig().getBoolean("afk.enabled", true)) return;
    int mins = getConfig().getInt("afk.kick-minutes", 15);
    if (mins <= 0) return;
    long now = System.currentTimeMillis();
    for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
        if (p.isOp() || owner().isOwner(p)) continue;
        if (afk().idleMs(p.getUniqueId(), now) > mins * 60_000L) p.kick(messages().plain("afk-kicked"));
    }
}, 600L, 600L);
```

- [ ] **Step 5: Test `AfkManagerTest`** — bump then idle is small; without bump idle is 0; after a far-past bump idle is large (set via reflection-free: bump, then `idleMs(id, now + 1_000_000)` > 0).

- [ ] **Step 6: Commit** — `feat: AFK detection and auto-kick`

---

## Task 3: Whitelist management GUI

**Files:** Create `gui/WhitelistGui.java`. Modify `gui/AdminPanelGui.java` (button slot 15), `messages.yml`. Test: `gui/WhitelistGuiTest.java`.

- [ ] **Step 1: `gui/WhitelistGui.java`** — 54-slot: heads of `Bukkit.getWhitelistedPlayers()` (click removes via `setWhitelisted(false)`); Add button (49) chat-prompts a name → `Bukkit.getOfflinePlayer(name).setWhitelisted(true)`; a toggle button (47) flips `Bukkit.setWhitelist(...)`; Back(45)→AdminPanel, Close(53). Follow the established list-GUI pattern (cancel click first, bounds-check, `border()` not needed for 54-slot lists). Sort heads alphabetically.

- [ ] **Step 2: AdminPanel button** — add `WHITELIST = 15` constant + `button(Material.PAPER, "Whitelist", "Manage the server whitelist")` + `case WHITELIST -> new WhitelistGui(plugin).open(p);`. (Slot 15 is free in the 27-slot panel interior.)

- [ ] **Step 3: messages** — `gui-whitelist-title: "<#3B82F6>Sentinel · Whitelist"`, `whitelist-enter: "<#60A5FA>Type a player name to whitelist, or type <white>cancel<#60A5FA>."`, `whitelist-on/off`.

- [ ] **Step 4: Test `WhitelistGuiTest`** — whitelist a player, open gui, assert one PLAYER_HEAD present.

- [ ] **Step 5: Commit** — `feat: whitelist management GUI`

---

## Task 4: World backup

**Files:** Create `manager/BackupManager.java`, `command/BackupCommand.java`. Modify `config.yml`, `messages.yml`, `plugin.yml`, `Sentinel.java`. Test: `manager/BackupManagerTest.java`.

- [ ] **Step 1: Config + messages**

config.yml:
```yaml
backup:
  keep: 5      # how many backup zips to retain
```
messages.yml: `backup-started: "<#60A5FA>Backup started…"`, `backup-done: "<#60A5FA>Backup saved: <white><file></white>."`, `backup-failed: "<red>Backup failed: <error>"`.

- [ ] **Step 2: `manager/BackupManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class BackupManager {
    private final Sentinel plugin;
    public BackupManager(Sentinel plugin) { this.plugin = plugin; }

    /** Saves all worlds (sync) then zips them into backups/ async; reports to requester. */
    public void backup(CommandSender requester, long stamp) {
        requester.sendMessage(plugin.messages().prefixed("backup-started"));
        List<File> worldDirs = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) { w.save(); worldDirs.add(w.getWorldFolder()); }
        File dir = new File(plugin.getDataFolder(), "backups");
        dir.mkdirs();
        File zip = new File(dir, "backup-" + stamp + ".zip");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                zipWorlds(worldDirs, zip);
                prune(dir, plugin.getConfig().getInt("backup.keep", 5));
                Bukkit.getScheduler().runTask(plugin, () ->
                    requester.sendMessage(plugin.messages().prefixed("backup-done", "file", zip.getName())));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                    requester.sendMessage(plugin.messages().prefixed("backup-failed", "error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void zipWorlds(List<File> dirs, File zip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            for (File dir : dirs) addDir(dir.getParentFile().toPath(), dir, zos);
        }
    }

    private void addDir(Path root, File f, ZipOutputStream zos) throws IOException {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().equals("session.lock")) continue;
            if (k.isDirectory()) { addDir(root, k, zos); continue; }
            zos.putNextEntry(new ZipEntry(root.relativize(k.toPath()).toString()));
            Files.copy(k.toPath(), zos);
            zos.closeEntry();
        }
    }

    private void prune(File dir, int keep) {
        File[] zips = dir.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (zips == null || zips.length <= keep) return;
        Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < zips.length - keep; i++) zips[i].delete();
    }
}
```

- [ ] **Step 3: `command/BackupCommand.java`** — OP-gated; `plugin.backup().backup(sender, System.currentTimeMillis())`. plugin.yml: `backup: { description: Back up the worlds, permission: sentinel.use }`. Wire in Sentinel.

- [ ] **Step 4: Test `BackupManagerTest`** — the zip helpers are hard to unit-test under MockBukkit; at minimum test `prune` keeps the newest N (create temp files, call a package-private prune, assert). If prune is private, expose it or skip the test and rely on the build. Keep it simple.

- [ ] **Step 5: Commit** — `feat: world backup command`

---

## Task 5: Scheduled tasks (cron-lite)

**Files:** Create `manager/CronManager.java`. Modify `config.yml`, `Sentinel.java`. Test: `manager/CronManagerTest.java`.

- [ ] **Step 1: Config**

```yaml
scheduled-tasks:
  # run a command on an interval or at a daily time. "do" runs as console.
  - { every: "2h", do: "broadcast <gray>Vote for the server!" }
  - { at: "04:00", do: "restart 60s" }
```

- [ ] **Step 2: `manager/CronManager.java`**

Reads the list; a 1-minute repeating task evaluates each entry:
- `every: "<duration>"` → run when `now - lastRun >= duration` (parse via `DurationParser`).
- `at: "HH:mm"` → run once when the server clock reaches that minute (track the last date+minute run to avoid double-fire).
Running a task = `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), entry.do)`.

Expose `tick(long now, java.time.LocalTime localTime)` as the pure decision method returning the list of `do`-commands to run, so it's unit-testable without the scheduler. The scheduler wrapper calls `tick(...)` each minute and dispatches the returned commands.

```java
// CronManager:
//   List<String> due(long nowMs, java.time.LocalTime time)  -> commands to run now (updates internal lastRun/lastAt state)
//   void start() -> runTaskTimer every 1200 ticks (60s): for (String cmd : due(now, LocalTime.now())) Bukkit.dispatchCommand(console, cmd);
```

- [ ] **Step 3: Test `CronManagerTest`** — configure an `every: "1s"` task; first `due(...)` returns it; immediate second call returns empty; after advancing the passed-in `now` by >1s returns it again. Configure `at: "04:00"`; `due(now, LocalTime.of(4,0))` returns it once, a second call same minute returns empty.

- [ ] **Step 4: Wire + commit** — build `cronManager`, `cronManager.start()` in onEnable. `git commit -m "feat: scheduled tasks (cron-lite)"`

---

## Task 6: Full suite + smoke test

- [ ] `./gradlew build` → BUILD SUCCESSFUL, all tests green. Shaded jar produced.
- [ ] Manual: `/backup`, AFK auto-kick after the configured time, Admin Panel → Whitelist add/remove, a scheduled broadcast fires, `/ban <typo>` is rejected.
- [ ] Commit any final touch-ups.

---

## Self-Review Notes
- **Audit fix:** never-seen names rejected (no phantom UUID); offline targets resolve via the directory (real UUID + last IP).
- **AFK:** activity bumped on join/move/chat/command; 30s checker kicks idle non-staff after `afk.kick-minutes`.
- **Whitelist GUI:** lists + add/remove + toggle, from the Admin Panel.
- **Backup:** save-all then async zip with retention.
- **Cron:** config tasks on interval or daily time, dispatched as console; the `due(...)` decision is unit-tested.
- **New `Sentinel` accessors:** `afk()`, `backup()`. New OP command `/backup`. AdminPanel slot `WHITELIST = 15`.
- **onDisable** already cancels all scheduler tasks (Plan/audit fix), so the new AFK/cron timers are torn down on reload.
