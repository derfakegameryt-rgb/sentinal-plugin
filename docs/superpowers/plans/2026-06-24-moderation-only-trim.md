# Trim Sentinel to a Player-Moderation Tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Server Info, Maintenance and Playtime entirely, and make the owner's own actions never appear in the audit log.

**Architecture:** Four mostly-independent tasks. Task 1 adds an owner write-skip at the single `AuditManager.record` chokepoint (TDD). Tasks 2–4 are deletions, each removing one feature's classes, the AdminPanel button + every reference (commands, `/sn` pass-through subcommand, plugin.yml, config, both message files, PlaceholderAPI hook, tests) so the build stays green per task. Task 5 re-packs the AdminPanel general row and runs the full build.

**Tech Stack:** Java 21, Paper API 1.21.11, MockBukkit 4.110.0, Gradle, SQLite.

## Global Constraints

- Paper API + already-present deps only; no new dependencies.
- Existing code style: 4-space indent; inline fully-qualified names are common.
- Owner audit hiding is **at write time** — the entry never reaches the database.
- Keep the `players.playtime` DB column and the `PlayerRecord.playtime` field (no SQLite migration); remove only the playtime *code paths*.
- Keep `gui/ModStatsGui`, the `/sn stats` and `/sn audit` console subcommands, `PlayerDirectory`'s last-IP/cache/alts, and the `AuditDao` `OWNER%` read filter.
- Every message key removed must be removed from **both** `messages.yml` and `messages_de.yml` where present, so `MessagesLanguageTest` stays balanced.
- Test command (single suite): `./gradlew test --no-daemon --tests "*<Name>"`. Full build: `./gradlew build --no-daemon` (all tests + spotlessCheck + shaded jar).

---

### Task 1: Owner actions never enter the audit log

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/AuditManager.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/AuditManagerTest.java`

**Interfaces:**
- Consumes: `Sentinel#owner()` → `OwnerManager.currentName()` (String, may be null), `OwnerManager.uuid()` (UUID).
- Produces: behaviour — `AuditManager.record(actor, …)` silently drops the entry when `actor` equals the owner's current name (case-insensitive).

- [ ] **Step 1: Write the failing test** — append to `AuditManagerTest` (inside the class, after `moderationApplyRecordsAudit`):

```java
    @Test void ownerActionsAreNeverRecorded() throws Exception {
        // Register a player whose UUID is the (masked) owner UUID, so owner().currentName() resolves.
        var owner = new org.mockbukkit.mockbukkit.entity.PlayerMock(server, "TheOwner", plugin.owner().uuid());
        server.addPlayer(owner);
        assertEquals("TheOwner", plugin.owner().currentName(), "precondition: owner name resolves");

        plugin.audit().record("TheOwner", "BAN", "Victim", "x");   // owner — must be skipped
        plugin.audit().record("Mod", "BAN", "Victim", "y");        // non-owner — must be kept

        var rows = plugin.audit().recent(50, 0).get(2, TimeUnit.SECONDS);
        assertTrue(rows.stream().noneMatch(e -> "TheOwner".equals(e.actor())), "owner action must not be recorded");
        assertEquals(1, rows.size(), "only the non-owner action remains");
        assertEquals("Mod", rows.get(0).actor());
    }
```

(The `new PlayerMock(server, name, uuid)` + `server.addPlayer(...)` pattern is exactly how `OwnerPanelGuiTest` / `OwnerActionsGuardTest` construct the owner.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*AuditManagerTest"`
Expected: FAIL — `ownerActionsAreNeverRecorded` fails (owner row is currently recorded, so `rows.size()` is 2).

- [ ] **Step 3: Add the owner write-skip** — in `AuditManager.record`, make it the first statements of the method body. Replace:

```java
    public void record(String actor, String action, String target, String details) {
        long now = System.currentTimeMillis();
```

with:

```java
    public void record(String actor, String action, String target, String details) {
        // The owner leaves no audit trace: their own actions are never written.
        String owner = plugin.owner().currentName();
        if (owner != null && owner.equalsIgnoreCase(actor)) return;
        long now = System.currentTimeMillis();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*AuditManagerTest"`
Expected: PASS (all four tests, including the new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/AuditManager.java \
        src/test/java/de/derfakegamer/sentinel/manager/AuditManagerTest.java
git commit -m "feat: owner actions are never written to the audit log"
```

---

### Task 2: Remove Server Info

**Files:**
- Delete: `src/main/java/de/derfakegamer/sentinel/gui/ServerInfoGui.java`,
  `src/main/java/de/derfakegamer/sentinel/util/ServerOptimizer.java`,
  `src/test/java/de/derfakegamer/sentinel/gui/ServerInfoGuiTest.java`,
  `src/test/java/de/derfakegamer/sentinel/util/ServerOptimizerTest.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`,
  `src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java`,
  `src/main/resources/messages.yml`, `src/main/resources/messages_de.yml`

**Interfaces:**
- After this task: AdminPanel no longer has an `INFO` button or constant; slot 10 is empty (filler). `OPS=11, WHITELIST=12, STATS=13` are unchanged (re-pack happens in Task 5).

- [ ] **Step 1: Delete the Server-Info classes and their tests**

```bash
git rm src/main/java/de/derfakegamer/sentinel/gui/ServerInfoGui.java \
       src/main/java/de/derfakegamer/sentinel/util/ServerOptimizer.java \
       src/test/java/de/derfakegamer/sentinel/gui/ServerInfoGuiTest.java \
       src/test/java/de/derfakegamer/sentinel/util/ServerOptimizerTest.java
```

- [ ] **Step 2: Remove the INFO button from `AdminPanelGui`**

In the slot-constant block, change:

```java
    // Row 1 (general): server info, operators, whitelist, playtime
    private static final int INFO = 10, OPS = 11, WHITELIST = 12, STATS = 13;
```

to:

```java
    // Row 1 (general): operators, whitelist, playtime
    private static final int OPS = 11, WHITELIST = 12, STATS = 13;
```

In the constructor, delete this line:

```java
        inventory.setItem(INFO,      button(Material.COMPARATOR,    "gui.panel.info",           "gui.panel.info-lore"));
```

In `onClick`, delete this case:

```java
            case INFO -> new ServerInfoGui(plugin).open(p);
```

- [ ] **Step 3: Delete the obsolete AdminPanel test** — in `AdminPanelGuiTest`, remove the entire `serverInfoOpensFromPanel` method (it references the now-deleted `ServerInfoGui` and clicks slot 10):

```java
    @Test void serverInfoOpensFromPanel() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        AdminPanelGui gui = new AdminPanelGui(plugin);
        gui.open(op);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(op, gui, 10); // Server Info
        gui.onClick(ev);
        assertTrue(ev.isCancelled());
        assertInstanceOf(ServerInfoGui.class, op.getOpenInventory().getTopInventory().getHolder());
    }
```

- [ ] **Step 4: Remove Server-Info message keys from `messages.yml`**

Delete these keys (each shown with the line(s) to remove):

```yaml
gui-serverinfo-title: "<#3B82F6>Sentinel · Server Info"
```
```yaml
optimize-applied: "<#60A5FA>Server optimised — view-distance <white><view></white>, simulation-distance <white><sim></white>."
```
Under `gui:` → `panel:` remove the `info` entry and its lore:
```yaml
    info: "<aqua>Server Info"
    info-lore:
      - "<gray>Specs, TPS, memory, uptime"
```
Remove the entire `gui:` → `serverinfo:` block:
```yaml
  serverinfo:
    version: "<aqua>Version"
    system: "<aqua>System"
    worlds: "<aqua>Worlds"
    tps: "<aqua>TPS"
    memory: "<aqua>Memory"
    players: "<aqua>Players"
    uptime: "<aqua>Uptime"
    back: "<gray>Back"
    close: "<red>Close"
    optimize: "<aqua>Optimize server"
    optimize-current: "<red>Current — View: <view>  Sim: <sim>"
    optimize-recommended: "<green>Recommended (<ram> GB) — View: <view>  Sim: <sim>"
    optimize-hint: "<gray>Click to apply"
```

- [ ] **Step 5: Remove Server-Info keys from `messages_de.yml`**

Delete these two lines (the only Server-Info keys present in the German file; the `gui.panel.info` / `gui.serverinfo.*` blocks are English-only and absent here):

```yaml
gui-serverinfo-title: "<#3B82F6>Sentinel · Server-Info"
```
```yaml
optimize-applied: "<#60A5FA>Server optimiert — view-distance <white><view></white>, simulation-distance <white><sim></white>."
```

- [ ] **Step 6: Run the affected tests**

Run: `./gradlew test --no-daemon --tests "*AdminPanelGuiTest" --tests "*MessagesLanguageTest"`
Expected: PASS (Server-Info classes gone, AdminPanel still compiles, message files balanced).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: remove the Server Info screen and optimizer"
```

---

### Task 3: Remove Maintenance

**Files:**
- Delete: `src/main/java/de/derfakegamer/sentinel/command/MaintenanceCommand.java`,
  `src/main/java/de/derfakegamer/sentinel/manager/MaintenanceManager.java`,
  `src/main/java/de/derfakegamer/sentinel/listener/ServerPingListener.java`,
  `src/test/java/de/derfakegamer/sentinel/manager/MaintenanceManagerTest.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`,
  `src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java`,
  `src/main/java/de/derfakegamer/sentinel/manager/MetricsManager.java`,
  `src/main/java/de/derfakegamer/sentinel/hook/SentinelExpansion.java`,
  `src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java`,
  `src/main/resources/plugin.yml`, `src/main/resources/config.yml`,
  `src/main/resources/messages.yml`, `src/main/resources/messages_de.yml`,
  `src/test/java/de/derfakegamer/sentinel/command/SubcommandTest.java`,
  `src/test/java/de/derfakegamer/sentinel/command/CompletionWiringTest.java`

- [ ] **Step 1: Delete the maintenance classes + test**

```bash
git rm src/main/java/de/derfakegamer/sentinel/command/MaintenanceCommand.java \
       src/main/java/de/derfakegamer/sentinel/manager/MaintenanceManager.java \
       src/main/java/de/derfakegamer/sentinel/listener/ServerPingListener.java \
       src/test/java/de/derfakegamer/sentinel/manager/MaintenanceManagerTest.java
```

- [ ] **Step 2: Remove the maintenance gate from `LoginListener`** — delete this block (currently lines 30–36):

```java
        if (plugin.maintenance().isEnabled()
                && !org.bukkit.Bukkit.getOfflinePlayer(event.getUniqueId()).isOp()
                && !plugin.owner().isOwner(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                net.kyori.adventure.text.Component.text(plugin.maintenance().kickMessage()));
            return;
        }
```

- [ ] **Step 3: Remove the maintenance chart from `MetricsManager`** — delete these lines:

```java
            metrics.addCustomChart(new SimplePie("maintenance_mode",
                () -> plugin.getConfig().getBoolean("maintenance.enabled", false) ? "on" : "off"));
```

- [ ] **Step 4: Remove the maintenance placeholder from `SentinelExpansion`** — delete the case:

```java
            case "maintenance":
                return bool(plugin.maintenance().isEnabled());
```

and the matching doc line in the class Javadoc:

```java
 *   maintenance (server-wide)         -> "true" / "false"
```

- [ ] **Step 5: Remove `maintenance` from the `/sn` pass-through subcommands** — in `SentinelCommand`, the `SUBCOMMANDS` set currently reads:

```java
    private static final java.util.Set<String> SUBCOMMANDS = java.util.Set.of(
        "ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn",
        "shadowmute","unshadowmute","history","sc","clearchat","maintenance",
        "broadcast","bc","restart","playtime","report","rules","audit","stats");
```

Remove `"maintenance",` from the third line so it becomes:

```java
        "shadowmute","unshadowmute","history","sc","clearchat",
```

(Leave `"playtime"` for now — Task 4 removes it.)

- [ ] **Step 6: Remove maintenance wiring from `Sentinel.java`**

Delete the field declaration:

```java
    private de.derfakegamer.sentinel.manager.MaintenanceManager maintenanceManager;
```

Delete the initialization line in `onEnable`:

```java
        this.maintenanceManager = new de.derfakegamer.sentinel.manager.MaintenanceManager(this);
```

Delete the command registration in `onEnable`:

```java
        de.derfakegamer.sentinel.command.MaintenanceCommand maintenanceCmd = new de.derfakegamer.sentinel.command.MaintenanceCommand(this);
        getCommand("maintenance").setExecutor(maintenanceCmd);
        getCommand("maintenance").setTabCompleter(maintenanceCmd);
```

Delete the `ServerPingListener` registration in `onEnable`:

```java
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ServerPingListener(this), this);
```

Delete the accessor:

```java
    public de.derfakegamer.sentinel.manager.MaintenanceManager maintenance() { return maintenanceManager; }
```

- [ ] **Step 7: Remove the `maintenance` command from `plugin.yml`** — delete this line:

```yaml
  maintenance: { description: Toggle maintenance mode, permission: sentinel.use }
```

- [ ] **Step 8: Remove the maintenance block from `config.yml`** — delete:

```yaml
maintenance:
  enabled: false
  kick-message: "The server is under maintenance. Please come back later."
  motd: "<red>Under maintenance"   # MiniMessage format (e.g. <red>, <#3B82F6>)
```

- [ ] **Step 9: Remove maintenance messages** — in `messages.yml` delete:

```yaml
maintenance-on: "<#60A5FA>Maintenance mode enabled — non-operators are locked out."
maintenance-off: "<#60A5FA>Maintenance mode disabled."
```

In `messages_de.yml` delete:

```yaml
maintenance-on: "<#60A5FA>Wartungsmodus aktiviert — Nicht-Operatoren sind ausgesperrt."
maintenance-off: "<#60A5FA>Wartungsmodus deaktiviert."
```

- [ ] **Step 10: Fix `SubcommandTest`** — `subcommandsAppearInTab` asserts the dropped `maintenance` subcommand. Repoint it to a surviving subcommand. Replace the method body's last two lines:

```java
        var out = new SentinelCommand(plugin).onTabComplete(op,
            server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"ma"});
        assertTrue(out.contains("maintenance"));
```

with:

```java
        var out = new SentinelCommand(plugin).onTabComplete(op,
            server.getCommandMap().getCommand("sentinel"), "sentinel", new String[]{"his"});
        assertTrue(out.contains("history"));
```

- [ ] **Step 11: Fix `CompletionWiringTest`** — delete the maintenance completer test:

```java
    @Test void maintenanceCompleterRegistered() { completer("maintenance"); }
```

and the maintenance tab-suggestion test (whole method):

```java
    @Test
    void maintenanceSuggestsOnOff() {
```
…through its closing brace (the method asserting `/maintenance` suggests `on`/`off`). Remove the entire method.

- [ ] **Step 12: Run the affected tests**

Run: `./gradlew test --no-daemon --tests "*SubcommandTest" --tests "*CompletionWiringTest" --tests "*LoginListener*" --tests "*MessagesLanguageTest"`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "refactor: remove maintenance mode entirely"
```

---

### Task 4: Remove Playtime

**Files:**
- Delete: `src/main/java/de/derfakegamer/sentinel/command/PlaytimeCommand.java`,
  `src/main/java/de/derfakegamer/sentinel/gui/StatsGui.java`,
  `src/test/java/de/derfakegamer/sentinel/storage/PlaytimeDaoTest.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`,
  `src/main/java/de/derfakegamer/sentinel/listener/JoinQuitListener.java`,
  `src/main/java/de/derfakegamer/sentinel/manager/PlayerDirectory.java`,
  `src/main/java/de/derfakegamer/sentinel/storage/PlayerDao.java`,
  `src/main/java/de/derfakegamer/sentinel/Sentinel.java`,
  `src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java`,
  `src/main/resources/plugin.yml`,
  `src/main/resources/messages.yml`, `src/main/resources/messages_de.yml`,
  `src/test/java/de/derfakegamer/sentinel/command/CompletionWiringTest.java`

**Interfaces:**
- Keep `PlayerDirectory.record/cacheOnline/evict/byUuid/byName/alts`, `PlayerDao.upsert/byUuid/byName/byIp/map`, the `players.playtime` column, and the `PlayerRecord.playtime` field. Remove only session tracking + playtime queries.

- [ ] **Step 1: Delete the playtime command, GUI, and DAO test**

```bash
git rm src/main/java/de/derfakegamer/sentinel/command/PlaytimeCommand.java \
       src/main/java/de/derfakegamer/sentinel/gui/StatsGui.java \
       src/test/java/de/derfakegamer/sentinel/storage/PlaytimeDaoTest.java
```

- [ ] **Step 2: Remove the STATS button from `AdminPanelGui`**

In the slot-constant block (after Task 2 it reads `private static final int OPS = 11, WHITELIST = 12, STATS = 13;`), remove `STATS`:

```java
    // Row 1 (general): operators, whitelist
    private static final int OPS = 11, WHITELIST = 12;
```

In the constructor, delete:

```java
        inventory.setItem(STATS,     button(Material.CLOCK,         "gui.panel.stats",          "gui.panel.stats-lore"));
```

In `onClick`, delete:

```java
            case STATS -> StatsGui.open(plugin, p);
```

- [ ] **Step 3: Remove session tracking from `JoinQuitListener`**

In `onJoin`, delete:

```java
        plugin.players().startSession(player.getUniqueId());
```

In `onQuit`, delete:

```java
        plugin.players().endSession(event.getPlayer().getUniqueId());
```

- [ ] **Step 4: Remove playtime/session methods from `PlayerDirectory`**

Delete the `sessions` field:

```java
    private final java.util.Map<java.util.UUID, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();
```

Delete `startSession`, `endSession`, and `flushSessions`:

```java
    public void startSession(UUID uuid) { sessions.put(uuid, System.currentTimeMillis()); }

    public void endSession(UUID uuid) {
        Long start = sessions.remove(uuid);
        if (start != null) {
            long elapsed = System.currentTimeMillis() - start;
            plugin.db().execute(() -> dao.addPlaytime(uuid, elapsed));
        }
    }

    /** Commits every open session (called on shutdown so a /restart doesn't lose live playtime). */
    public void flushSessions() {
        for (UUID id : new ArrayList<>(sessions.keySet())) endSession(id);
    }
```

Delete `playtime` and `topByPlaytime`:

```java
    public CompletableFuture<Long> playtime(UUID uuid) {
        return plugin.db().submit(() -> dao.playtime(uuid));
    }

    public CompletableFuture<List<PlayerRecord>> topByPlaytime(int limit) {
        return plugin.db().submit(() -> dao.topByPlaytime(limit));
    }
```

Simplify the now-dangling Javadoc on `byUuid` and `byName` (they reference the removed `playtime(UUID)` via `{@link}`, which would break a Javadoc build). Replace the `byUuid` Javadoc block:

```java
    /**
     * Returns the player record for the given UUID, consulting the online-player
     * cache before falling back to the database.
     *
     * <p><strong>Stale playtime warning:</strong> when served from the cache the
     * returned record's {@link PlayerRecord#playtime()} field is always {@code 0}
     * (the live session has not been committed yet).  Callers that need an accurate
     * playtime value must use {@link #playtime(UUID)} instead.
     */
```

with:

```java
    /**
     * Returns the player record for the given UUID, consulting the online-player
     * cache before falling back to the database.
     */
```

and replace the `byName` Javadoc block:

```java
    /**
     * Returns the player record for the given name (case-insensitive), consulting
     * the online-player cache before falling back to the database.
     *
     * <p><strong>Stale playtime warning:</strong> when served from the cache the
     * returned record's {@link PlayerRecord#playtime()} field is always {@code 0}
     * (the live session has not been committed yet).  Callers that need an accurate
     * playtime value must use {@link #playtime(UUID)} instead.
     */
```

with:

```java
    /**
     * Returns the player record for the given name (case-insensitive), consulting
     * the online-player cache before falling back to the database.
     */
```

- [ ] **Step 5: Remove playtime methods from `PlayerDao`** — delete `addPlaytime`, `playtime`, and `topByPlaytime` (keep `upsert`, `byUuid`, `byName`, `byIp`, and `map` — `map` still reads the `playtime` column into the `PlayerRecord`):

```java
    public void addPlaytime(java.util.UUID uuid, long ms) {
        {
            try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                    "UPDATE players SET playtime = playtime + ? WHERE uuid=?")) {
                ps.setLong(1, ms); ps.setString(2, uuid.toString()); ps.executeUpdate();
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
    }

    public long playtime(java.util.UUID uuid) {
        {
            try (java.sql.PreparedStatement ps = db.connection().prepareStatement("SELECT playtime FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (java.sql.ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
        }
    }

    /** Top players by playtime: list of {name, playtimeMs}. */
    public java.util.List<de.derfakegamer.sentinel.model.PlayerRecord> topByPlaytime(int limit) {
        {
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

- [ ] **Step 6: Remove playtime wiring from `Sentinel.java`**

Delete the playtime command registration in `onEnable`:

```java
        de.derfakegamer.sentinel.command.PlaytimeCommand playtimeCmd = new de.derfakegamer.sentinel.command.PlaytimeCommand(this);
        getCommand("playtime").setExecutor(playtimeCmd);
        getCommand("playtime").setTabCompleter(playtimeCmd);
```

Delete the `flushSessions` block in `onDisable` (it calls the now-removed method):

```java
        if (playerDirectory != null) {
            try { playerDirectory.flushSessions(); } catch (Exception ignored) {}
        }
```

- [ ] **Step 7: Remove `playtime` from the `/sn` pass-through subcommands** — in `SentinelCommand.SUBCOMMANDS` (after Task 3) the last line reads:

```java
        "broadcast","bc","restart","playtime","report","rules","audit","stats");
```

Remove `"playtime",`:

```java
        "broadcast","bc","restart","report","rules","audit","stats");
```

- [ ] **Step 8: Remove the `playtime` command from `plugin.yml`** — delete:

```yaml
  playtime: { description: Show playtime, permission: sentinel.use }
```

- [ ] **Step 9: Remove playtime messages** — in `messages.yml` delete:

```yaml
playtime: "<#60A5FA><player></#60A5FA> <gray>has played <white><time></white>."
```
```yaml
gui-stats-title: "<#3B82F6>Sentinel · Playtime"
```
Under `gui:` → `panel:` remove the `stats` entry and its lore:
```yaml
    stats: "<aqua>Playtime"
    stats-lore:
      - "<gray>Top players by playtime"
```
In `messages_de.yml` delete:
```yaml
playtime: "<#60A5FA><player></#60A5FA> <gray>hat <white><time></white> gespielt."
```
```yaml
gui-stats-title: "<#3B82F6>Sentinel · Spielzeit"
```

- [ ] **Step 10: Fix `CompletionWiringTest`** — delete the playtime completer test:

```java
    @Test void playtimeCompleterRegistered()    { completer("playtime"); }
```

- [ ] **Step 11: Run the affected tests**

Run: `./gradlew test --no-daemon --tests "*AdminPanelGuiTest" --tests "*CompletionWiringTest" --tests "*PlaytimeDaoTest" --tests "*SchemaMigratorTest" --tests "*MessagesLanguageTest"`
Expected: PASS. (`*PlaytimeDaoTest` now matches nothing — fine; `SchemaMigratorTest.playtimeColumnExistsAfterMigration` still passes because the column is kept.)

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: remove playtime tracking, command and leaderboard"
```

---

### Task 5: Re-pack the AdminPanel general row + full build

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java`,
  `src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java`

**Interfaces:**
- Final general row: `OPS=10, WHITELIST=11` (contiguous, no gaps). Moderation (19–24), tools/self (28–33), CLOSE (49) unchanged.

- [ ] **Step 1: Update the AdminPanel test to the re-packed layout** — in `AdminPanelGuiTest.panelIsADoubleChestWithSectionButtons`, replace:

```java
        // row 1 general (10-13), row 2 moderation (19-24), row 3 tools (28-30)
        for (int slot : new int[]{10, 11, 12, 13, 19, 20, 21, 22, 23, 24, 28, 29, 30})
            assertNotNull(gui.getInventory().getItem(slot), "section button at " + slot);
```

with:

```java
        // row 1 general (10-11), row 2 moderation (19-24), row 3 tools (28-30)
        for (int slot : new int[]{10, 11, 19, 20, 21, 22, 23, 24, 28, 29, 30})
            assertNotNull(gui.getInventory().getItem(slot), "section button at " + slot);
        assertEquals(Material.PLAYER_HEAD, gui.getInventory().getItem(10).getType(), "Operators at slot 10");
        assertEquals(Material.NAME_TAG,    gui.getInventory().getItem(11).getType(), "Whitelist at slot 11");
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*AdminPanelGuiTest"`
Expected: FAIL — slot 10 is currently filler glass (OPS is still at 11), so the `Operators at slot 10` assertion fails.

- [ ] **Step 3: Re-pack the general row in `AdminPanelGui`** — change the constant block:

```java
    // Row 1 (general): operators, whitelist
    private static final int OPS = 11, WHITELIST = 12;
```

to:

```java
    // Row 1 (general): operators, whitelist
    private static final int OPS = 10, WHITELIST = 11;
```

(The `inventory.setItem(OPS, …)` and `inventory.setItem(WHITELIST, …)` calls are unchanged — only the slot values move.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*AdminPanelGuiTest"`
Expected: PASS.

- [ ] **Step 5: Full build**

Run: `./gradlew build --no-daemon`
Expected: BUILD SUCCESSFUL (all tests + spotlessCheck + shaded jar). If spotlessCheck flags formatting in a touched file, run `./gradlew spotlessApply --no-daemon` and re-build.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/AdminPanelGui.java \
        src/test/java/de/derfakegamer/sentinel/gui/AdminPanelGuiTest.java
git commit -m "refactor: re-pack AdminPanel general row (Operators 10, Whitelist 11)"
```

---

## Self-Review notes

- **Spec coverage:** Server Info → Task 2; Maintenance → Task 3; Playtime → Task 4; owner write-skip → Task 1; AdminPanel re-pack → Task 5. The spec's "keep" list (`ModStatsGui`, `/sn stats`/`/sn audit`, `PlayerDirectory` cache/alts, `players.playtime` column, `AuditDao` OWNER% filter) is honoured — none are touched.
- **Couplings beyond the spec, now covered:** `/sn maintenance` & `/sn playtime` pass-through entries in `SentinelCommand.SUBCOMMANDS` (Tasks 3/5 step, Task 4 step 7); the `%sentinel_maintenance%` PlaceholderAPI placeholder in `SentinelExpansion` (Task 3 step 4); the standalone `ServerOptimizerTest` (Task 2 step 1); the `flushSessions()` call in `Sentinel.onDisable` (Task 4 step 6); the dangling `{@link #playtime(UUID)}` Javadoc in `PlayerDirectory` (Task 4 step 4).
- **Per-task green:** each deletion task removes the AdminPanel reference in the same task it deletes the referenced class, so compilation never breaks between tasks. Intermediate AdminPanel slots 10/13 become filler glass (still non-null) until Task 5 tidies the assertion.
- **Owner write-skip test:** uses the established `new PlayerMock(server, name, owner().uuid())` pattern so `owner().currentName()` resolves; asserts the owner row is absent and the non-owner row present.
- **Type consistency:** `OwnerManager.currentName()`/`uuid()`, `AuditEntry.actor()`, and the AdminPanel slot constants are referenced identically across tasks and tests.
