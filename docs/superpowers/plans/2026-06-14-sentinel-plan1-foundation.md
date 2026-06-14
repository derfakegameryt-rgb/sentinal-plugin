# Sentinel — Plan 1: Foundation & Punishment Engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Gradle project, SQLite storage, and the core punishment engine (ban / tempban / ip-ban / mute / tempmute / warn / kick) with login + chat enforcement and admin command shortcuts — a working, testable moderation plugin without GUIs yet.

**Architecture:** A Paper `JavaPlugin` (`Sentinel`) wires together an SQLite `Database`, a `PunishmentDao`, and a `PunishmentManager` that holds all punishment logic (record, look up active, expire lazily, exempt protection). Listeners enforce bans at login and mutes in chat. Thin command classes call the same manager methods the GUIs will later use. Pure logic (`DurationParser`, model, DAO, manager) is unit-tested with JUnit; Bukkit-facing code is tested with MockBukkit.

**Tech Stack:** Java 21, Gradle (Kotlin DSL) + Shadow (shades `sqlite-jdbc`), Paper API, Adventure/MiniMessage (bundled in Paper), JUnit 5, MockBukkit.

> **Build note:** Pin the exact published versions on first build — Paper API floor `1.21.8-R0.1-SNAPSHOT` (runs on 1.21.11 and 26.1.1), MockBukkit matching the Paper minor, latest `sqlite-jdbc`. If a coordinate fails to resolve, bump to the nearest published version; do not change the code.

---

## File Structure

```
build.gradle.kts                 Gradle build, Java 21 toolchain, Shadow
settings.gradle.kts              project name "Sentinel"
src/main/resources/plugin.yml    plugin + command/permission declarations
src/main/resources/config.yml    defaults (storage, reasons, exempt, update)
src/main/resources/messages.yml  English strings (MiniMessage, blue theme)
src/main/java/de/derfakegamer/sentinel/
  Sentinel.java                  main plugin, wires everything
  model/PunishmentType.java      BAN/MUTE/WARN/KICK/IPBAN
  model/Punishment.java          immutable record of one punishment
  storage/Database.java          SQLite connection + schema
  storage/PunishmentDao.java     CRUD for punishments
  manager/PunishmentManager.java punishment logic + expiry + exempt
  util/DurationParser.java       "1w2d6h" -> millis
  util/Messages.java             messages.yml loader + prefix
  listener/LoginListener.java    ban / ip-ban enforcement
  listener/ChatListener.java     mute enforcement
  command/PunishmentCommands.java /ban /tempban /mute ... shortcuts
  command/SentinelCommand.java   /sentinel reload (admin)
src/test/java/de/derfakegamer/sentinel/
  util/DurationParserTest.java
  model/PunishmentTest.java
  storage/PunishmentDaoTest.java
  manager/PunishmentManagerTest.java
  listener/LoginListenerTest.java
  listener/ChatListenerTest.java
```

---

## Task 1: Gradle scaffolding & main plugin class

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`
- Create: `src/main/resources/plugin.yml`
- Create: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "Sentinel"
```

- [ ] **Step 2: Write `build.gradle.kts`**

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "de.derfakegamer"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.59.2")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "de.derfakegamer.sentinel.libs.sqlite")
}

tasks.build { dependsOn(tasks.shadowJar) }
```

- [ ] **Step 3: Write `src/main/resources/plugin.yml`**

```yaml
name: Sentinel
version: '1.0.0'
main: de.derfakegamer.sentinel.Sentinel
api-version: '1.21'
author: DerFakeGamer
commands:
  sentinel:
    description: Sentinel admin (reload)
  ban: { description: Ban a player }
  tempban: { description: Temporarily ban a player }
  ipban: { description: Ban a player's IP }
  unban: { description: Unban a player }
  mute: { description: Mute a player }
  tempmute: { description: Temporarily mute a player }
  unmute: { description: Unmute a player }
  kick: { description: Kick a player }
  warn: { description: Warn a player }
  history: { description: View a player's punishment history }
```

- [ ] **Step 4: Write `Sentinel.java` (minimal, compiles)**

```java
package de.derfakegamer.sentinel;

import org.bukkit.plugin.java.JavaPlugin;

public final class Sentinel extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("Sentinel enabled.");
    }
}
```

- [ ] **Step 5: Build to verify scaffolding**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; a shaded jar appears in `build/libs/Sentinel-1.0.0.jar`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts src gradlew gradle gradlew.bat 2>/dev/null
git add -A
git commit -m "feat: gradle scaffolding and Sentinel main class"
```

---

## Task 2: DurationParser

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/util/DurationParser.java`
- Test: `src/test/java/de/derfakegamer/sentinel/util/DurationParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {
    @Test void parsesSeconds() { assertEquals(30_000L, DurationParser.parse("30s")); }
    @Test void parsesMinutes() { assertEquals(10 * 60_000L, DurationParser.parse("10m")); }
    @Test void parsesHours()   { assertEquals(3 * 3_600_000L, DurationParser.parse("3h")); }
    @Test void parsesDays()    { assertEquals(8 * 86_400_000L, DurationParser.parse("8d")); }
    @Test void parsesWeeks()   { assertEquals(2 * 604_800_000L, DurationParser.parse("2w")); }
    @Test void parsesCombined() {
        assertEquals(604_800_000L + 2*86_400_000L + 6*3_600_000L, DurationParser.parse("1w2d6h"));
    }
    @Test void rejectsEmpty()   { assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("")); }
    @Test void rejectsGarbage() { assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("abc")); }
    @Test void rejectsBadUnit() { assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("5y")); }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests DurationParserTest`
Expected: FAIL — `DurationParser` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package de.derfakegamer.sentinel.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)([smhdw])");

    private DurationParser() {}

    /** Parses e.g. "1w2d6h30m15s" into milliseconds. Throws on invalid input. */
    public static long parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("empty duration");
        Matcher m = TOKEN.matcher(input.toLowerCase());
        long total = 0;
        int matchedChars = 0;
        while (m.find()) {
            matchedChars += m.group().length();
            long value = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "s" -> value * 1_000L;
                case "m" -> value * 60_000L;
                case "h" -> value * 3_600_000L;
                case "d" -> value * 86_400_000L;
                case "w" -> value * 604_800_000L;
                default -> throw new IllegalArgumentException("bad unit");
            };
        }
        if (matchedChars != input.length() || total <= 0)
            throw new IllegalArgumentException("invalid duration: " + input);
        return total;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests DurationParserTest`
Expected: PASS (all 9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/util/DurationParser.java \
        src/test/java/de/derfakegamer/sentinel/util/DurationParserTest.java
git commit -m "feat: duration parser (s/m/h/d/w)"
```

---

## Task 3: PunishmentType & Punishment model

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/model/PunishmentType.java`
- Create: `src/main/java/de/derfakegamer/sentinel/model/Punishment.java`
- Test: `src/test/java/de/derfakegamer/sentinel/model/PunishmentTest.java`

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.model;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentTest {
    @Test void permanentNeverExpires() {
        Punishment p = base().expiresAt(0).build();
        assertFalse(p.isExpired(System.currentTimeMillis()));
        assertTrue(p.isPermanent());
    }
    @Test void temporaryExpires() {
        Punishment p = base().expiresAt(1000).build();
        assertTrue(p.isExpired(1001));
        assertFalse(p.isExpired(999));
    }
    private Punishment.Builder base() {
        return Punishment.builder()
            .type(PunishmentType.BAN).targetUuid(UUID.randomUUID()).targetName("Notch")
            .reason("test").issuerUuid(UUID.randomUUID()).issuerName("Admin")
            .createdAt(0).active(true);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests PunishmentTest`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Write `PunishmentType.java`**

```java
package de.derfakegamer.sentinel.model;

public enum PunishmentType { BAN, MUTE, WARN, KICK, IPBAN }
```

- [ ] **Step 4: Write `Punishment.java`**

```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

public final class Punishment {
    private final long id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final String targetIp;     // nullable
    private final String reason;
    private final UUID issuerUuid;
    private final String issuerName;
    private final long createdAt;
    private final long expiresAt;       // 0 = permanent
    private final boolean active;
    private final String removedBy;     // nullable
    private final long removedAt;

    private Punishment(Builder b) {
        this.id = b.id; this.type = b.type; this.targetUuid = b.targetUuid;
        this.targetName = b.targetName; this.targetIp = b.targetIp; this.reason = b.reason;
        this.issuerUuid = b.issuerUuid; this.issuerName = b.issuerName; this.createdAt = b.createdAt;
        this.expiresAt = b.expiresAt; this.active = b.active; this.removedBy = b.removedBy;
        this.removedAt = b.removedAt;
    }

    public long id() { return id; }
    public PunishmentType type() { return type; }
    public UUID targetUuid() { return targetUuid; }
    public String targetName() { return targetName; }
    public String targetIp() { return targetIp; }
    public String reason() { return reason; }
    public UUID issuerUuid() { return issuerUuid; }
    public String issuerName() { return issuerName; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public boolean active() { return active; }
    public String removedBy() { return removedBy; }
    public long removedAt() { return removedAt; }

    public boolean isPermanent() { return expiresAt == 0; }
    public boolean isExpired(long now) { return !isPermanent() && now >= expiresAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long id; private PunishmentType type; private UUID targetUuid;
        private String targetName; private String targetIp; private String reason;
        private UUID issuerUuid; private String issuerName; private long createdAt;
        private long expiresAt; private boolean active; private String removedBy; private long removedAt;

        public Builder id(long v) { this.id = v; return this; }
        public Builder type(PunishmentType v) { this.type = v; return this; }
        public Builder targetUuid(UUID v) { this.targetUuid = v; return this; }
        public Builder targetName(String v) { this.targetName = v; return this; }
        public Builder targetIp(String v) { this.targetIp = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder issuerUuid(UUID v) { this.issuerUuid = v; return this; }
        public Builder issuerName(String v) { this.issuerName = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public Builder expiresAt(long v) { this.expiresAt = v; return this; }
        public Builder active(boolean v) { this.active = v; return this; }
        public Builder removedBy(String v) { this.removedBy = v; return this; }
        public Builder removedAt(long v) { this.removedAt = v; return this; }
        public Punishment build() { return new Punishment(this); }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests PunishmentTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/model/ \
        src/test/java/de/derfakegamer/sentinel/model/PunishmentTest.java
git commit -m "feat: punishment model and type"
```

---

## Task 4: Database (SQLite connection + schema)

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/storage/Database.java`
- Test: covered indirectly by Task 5 (`PunishmentDaoTest` opens a real DB).

- [ ] **Step 1: Write `Database.java`**

```java
package de.derfakegamer.sentinel.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(File file) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        createSchema();
    }

    public Connection connection() { return connection; }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS punishments (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  type TEXT NOT NULL,
                  target_uuid TEXT NOT NULL,
                  target_name TEXT NOT NULL,
                  target_ip TEXT,
                  reason TEXT NOT NULL,
                  issuer_uuid TEXT NOT NULL,
                  issuer_name TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL DEFAULT 0,
                  active INTEGER NOT NULL DEFAULT 1,
                  removed_by TEXT,
                  removed_at INTEGER NOT NULL DEFAULT 0
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pun_target ON punishments(target_uuid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pun_ip ON punishments(target_ip)");
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
```

- [ ] **Step 2: Commit (verified together with Task 5)**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/Database.java
git commit -m "feat: sqlite database and schema"
```

---

## Task 5: PunishmentDao

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/storage/PunishmentDao.java`
- Test: `src/test/java/de/derfakegamer/sentinel/storage/PunishmentDaoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentDaoTest {
    Database db; PunishmentDao dao; File tmp;
    UUID target = UUID.randomUUID(); UUID issuer = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new PunishmentDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    private Punishment ban(long expiresAt) {
        return Punishment.builder().type(PunishmentType.BAN).targetUuid(target)
            .targetName("Notch").reason("hax").issuerUuid(issuer).issuerName("Admin")
            .createdAt(100).expiresAt(expiresAt).active(true).build();
    }

    @Test void insertAndFindActive() {
        long id = dao.insert(ban(0));
        assertTrue(id > 0);
        Punishment found = dao.findActive(PunishmentType.BAN, target);
        assertNotNull(found);
        assertEquals("hax", found.reason());
    }

    @Test void deactivateRemovesActive() {
        long id = dao.insert(ban(0));
        dao.deactivate(id, "Admin", 200);
        assertNull(dao.findActive(PunishmentType.BAN, target));
    }

    @Test void historyReturnsAll() {
        dao.insert(ban(0));
        dao.insert(ban(0));
        assertEquals(2, dao.findHistory(target).size());
    }

    @Test void countWarnsCountsActiveWarns() {
        dao.insert(Punishment.builder().type(PunishmentType.WARN).targetUuid(target)
            .targetName("Notch").reason("spam").issuerUuid(issuer).issuerName("Admin")
            .createdAt(100).expiresAt(0).active(true).build());
        assertEquals(1, dao.countWarns(target));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests PunishmentDaoTest`
Expected: FAIL — `PunishmentDao` does not exist.

- [ ] **Step 3: Write `PunishmentDao.java`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PunishmentDao {
    private final Database db;

    public PunishmentDao(Database db) { this.db = db; }

    public long insert(Punishment p) {
        String sql = """
            INSERT INTO punishments
              (type,target_uuid,target_name,target_ip,reason,issuer_uuid,issuer_name,
               created_at,expires_at,active,removed_by,removed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, p.type().name());
            ps.setString(2, p.targetUuid().toString());
            ps.setString(3, p.targetName());
            ps.setString(4, p.targetIp());
            ps.setString(5, p.reason());
            ps.setString(6, p.issuerUuid().toString());
            ps.setString(7, p.issuerName());
            ps.setLong(8, p.createdAt());
            ps.setLong(9, p.expiresAt());
            ps.setInt(10, p.active() ? 1 : 0);
            ps.setString(11, p.removedBy());
            ps.setLong(12, p.removedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Punishment findActive(PunishmentType type, UUID target) {
        String sql = "SELECT * FROM punishments WHERE type=? AND target_uuid=? AND active=1 LIMIT 1";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, target.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Punishment findActiveByIp(PunishmentType type, String ip) {
        String sql = "SELECT * FROM punishments WHERE type=? AND target_ip=? AND active=1 LIMIT 1";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void deactivate(long id, String removedBy, long removedAt) {
        String sql = "UPDATE punishments SET active=0, removed_by=?, removed_at=? WHERE id=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, removedBy);
            ps.setLong(2, removedAt);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Punishment> findHistory(UUID target) {
        String sql = "SELECT * FROM punishments WHERE target_uuid=? ORDER BY created_at DESC";
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(map(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public int countWarns(UUID target) {
        String sql = "SELECT COUNT(*) FROM punishments WHERE type='WARN' AND target_uuid=? AND active=1";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Punishment map(ResultSet rs) throws SQLException {
        return Punishment.builder()
            .id(rs.getLong("id"))
            .type(PunishmentType.valueOf(rs.getString("type")))
            .targetUuid(UUID.fromString(rs.getString("target_uuid")))
            .targetName(rs.getString("target_name"))
            .targetIp(rs.getString("target_ip"))
            .reason(rs.getString("reason"))
            .issuerUuid(UUID.fromString(rs.getString("issuer_uuid")))
            .issuerName(rs.getString("issuer_name"))
            .createdAt(rs.getLong("created_at"))
            .expiresAt(rs.getLong("expires_at"))
            .active(rs.getInt("active") == 1)
            .removedBy(rs.getString("removed_by"))
            .removedAt(rs.getLong("removed_at"))
            .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests PunishmentDaoTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/PunishmentDao.java \
        src/test/java/de/derfakegamer/sentinel/storage/PunishmentDaoTest.java
git commit -m "feat: punishment DAO"
```

---

## Task 6: PunishmentManager (logic + expiry + exempt)

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.storage.Database;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentManagerTest {
    Database db; PunishmentManager mgr; File tmp;
    UUID target = UUID.randomUUID(); UUID issuer = UUID.randomUUID();
    UUID exempt = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        mgr = new PunishmentManager(new PunishmentDao(db), Set.of(exempt));
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void banThenActiveBanFound() {
        assertTrue(mgr.ban(target, "Notch", issuer, "Admin", "hax", 0).isSuccess());
        assertNotNull(mgr.activeBan(target, System.currentTimeMillis()));
    }

    @Test void exemptCannotBeBanned() {
        var result = mgr.ban(exempt, "Owner", issuer, "Admin", "x", 0);
        assertFalse(result.isSuccess());
        assertNull(mgr.activeBan(exempt, System.currentTimeMillis()));
    }

    @Test void expiredBanReturnsNullAndIsDeactivated() {
        long now = 1_000_000L;
        mgr.ban(target, "Notch", issuer, "Admin", "hax", now + 1000); // expires soon
        assertNull(mgr.activeBan(target, now + 2000));                 // past expiry
        // second lookup confirms it was deactivated, not just filtered
        assertNull(mgr.activeBan(target, now + 1));
    }

    @Test void unbanClearsBan() {
        mgr.ban(target, "Notch", issuer, "Admin", "hax", 0);
        assertTrue(mgr.unban(target, "Admin", System.currentTimeMillis()));
        assertNull(mgr.activeBan(target, System.currentTimeMillis()));
    }

    @Test void warnIncrementsCount() {
        mgr.warn(target, "Notch", issuer, "Admin", "spam");
        mgr.warn(target, "Notch", issuer, "Admin", "spam");
        assertEquals(2, mgr.warnCount(target));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests PunishmentManagerTest`
Expected: FAIL — `PunishmentManager` does not exist.

- [ ] **Step 3: Write `PunishmentManager.java`**

```java
package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.storage.PunishmentDao;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PunishmentManager {
    /** Result of an action that may be blocked (e.g. exempt target). */
    public record Result(boolean success, String message) {
        public boolean isSuccess() { return success; }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    private final PunishmentDao dao;
    private final Set<UUID> exempt;

    public PunishmentManager(PunishmentDao dao, Set<UUID> exempt) {
        this.dao = dao; this.exempt = exempt;
    }

    public boolean isExempt(UUID uuid) { return exempt.contains(uuid); }

    public Result ban(UUID target, String targetName, UUID issuer, String issuerName,
                      String reason, long expiresAt) {
        return record(PunishmentType.BAN, target, targetName, null, issuer, issuerName, reason, expiresAt);
    }

    public Result ipBan(UUID target, String targetName, String ip, UUID issuer, String issuerName,
                        String reason, long expiresAt) {
        if (isExempt(target)) return Result.fail("exempt");
        dao.insert(Punishment.builder().type(PunishmentType.IPBAN).targetUuid(target)
            .targetName(targetName).targetIp(ip).reason(reason).issuerUuid(issuer)
            .issuerName(issuerName).createdAt(System.currentTimeMillis())
            .expiresAt(expiresAt).active(true).build());
        return Result.ok();
    }

    public Result mute(UUID target, String targetName, UUID issuer, String issuerName,
                       String reason, long expiresAt) {
        return record(PunishmentType.MUTE, target, targetName, null, issuer, issuerName, reason, expiresAt);
    }

    public Result warn(UUID target, String targetName, UUID issuer, String issuerName, String reason) {
        return record(PunishmentType.WARN, target, targetName, null, issuer, issuerName, reason, 0);
    }

    public Result kick(UUID target, String targetName, UUID issuer, String issuerName, String reason) {
        // kicks are history-only (never "active")
        if (isExempt(target)) return Result.fail("exempt");
        dao.insert(Punishment.builder().type(PunishmentType.KICK).targetUuid(target)
            .targetName(targetName).reason(reason).issuerUuid(issuer).issuerName(issuerName)
            .createdAt(System.currentTimeMillis()).expiresAt(0).active(false).build());
        return Result.ok();
    }

    private Result record(PunishmentType type, UUID target, String targetName, String ip,
                          UUID issuer, String issuerName, String reason, long expiresAt) {
        if (isExempt(target)) return Result.fail("exempt");
        dao.insert(Punishment.builder().type(type).targetUuid(target).targetName(targetName)
            .targetIp(ip).reason(reason).issuerUuid(issuer).issuerName(issuerName)
            .createdAt(System.currentTimeMillis()).expiresAt(expiresAt).active(true).build());
        return Result.ok();
    }

    /** Returns the active ban, lazily deactivating it if expired. */
    public Punishment activeBan(UUID target, long now) {
        return activeOrExpire(PunishmentType.BAN, target, now);
    }

    public Punishment activeMute(UUID target, long now) {
        return activeOrExpire(PunishmentType.MUTE, target, now);
    }

    public Punishment activeIpBan(String ip, long now) {
        Punishment p = dao.findActiveByIp(PunishmentType.IPBAN, ip);
        if (p == null) return null;
        if (p.isExpired(now)) { dao.deactivate(p.id(), "SYSTEM", now); return null; }
        return p;
    }

    private Punishment activeOrExpire(PunishmentType type, UUID target, long now) {
        Punishment p = dao.findActive(type, target);
        if (p == null) return null;
        if (p.isExpired(now)) { dao.deactivate(p.id(), "SYSTEM", now); return null; }
        return p;
    }

    public boolean unban(UUID target, String remover, long now) {
        Punishment p = dao.findActive(PunishmentType.BAN, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    }

    public boolean unmute(UUID target, String remover, long now) {
        Punishment p = dao.findActive(PunishmentType.MUTE, target);
        if (p == null) return false;
        dao.deactivate(p.id(), remover, now);
        return true;
    }

    public int warnCount(UUID target) { return dao.countWarns(target); }

    public List<Punishment> history(UUID target) { return dao.findHistory(target); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests PunishmentManagerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/PunishmentManager.java \
        src/test/java/de/derfakegamer/sentinel/manager/PunishmentManagerTest.java
git commit -m "feat: punishment manager with expiry and exempt"
```

---

## Task 7: Messages util & config defaults

**Files:**
- Create: `src/main/resources/config.yml`
- Create: `src/main/resources/messages.yml`
- Create: `src/main/java/de/derfakegamer/sentinel/util/Messages.java`

- [ ] **Step 1: Write `src/main/resources/config.yml`**

```yaml
# Sentinel configuration
exempt: []          # list of UUIDs that can never be punished
reasons:            # the 5 presets shown in the Reason GUI
  - Hacking
  - Spam
  - Toxicity
  - Advertising
  - Griefing
update:
  enabled: true
  check-interval-seconds: 1800   # minimum enforced: 60
  github-token: ''               # optional, raises GitHub API rate limit
```

- [ ] **Step 2: Write `src/main/resources/messages.yml`**

```yaml
prefix: "<#3B82F6><bold>Sentinel</bold> <dark_gray>»</dark_gray> "
no-permission: "<red>You must be a server operator to do this."
player-not-found: "<red>Player not found."
exempt: "<red>That player is protected and cannot be punished."
banned: "<#60A5FA><player></#60A5FA> <gray>was banned. Reason: <white><reason>"
unbanned: "<#60A5FA><player></#60A5FA> <gray>was unbanned."
muted: "<#60A5FA><player></#60A5FA> <gray>was muted. Reason: <white><reason>"
unmuted: "<#60A5FA><player></#60A5FA> <gray>was unmuted."
kicked: "<#60A5FA><player></#60A5FA> <gray>was kicked. Reason: <white><reason>"
warned: "<#60A5FA><player></#60A5FA> <gray>was warned. Reason: <white><reason>"
you-are-muted: "<red>You are muted. Reason: <white><reason>"
ban-screen: "<#3B82F6><bold>Sentinel</bold>\n<gray>You are banned.\n<white><reason>"
usage: "<red>Usage: <white><usage>"
reloaded: "<#60A5FA>Sentinel configuration reloaded."
```

- [ ] **Step 3: Write `Messages.java`**

```java
package de.derfakegamer.sentinel.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;

public final class Messages {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private FileConfiguration config;

    public Messages(FileConfiguration config) { this.config = config; }

    public void reload(FileConfiguration config) { this.config = config; }

    private String raw(String key) { return config.getString(key, key); }

    public Component prefixed(String key, String... placeholders) {
        return mm.deserialize(raw("prefix") + raw(key), resolvers(placeholders));
    }

    public Component plain(String key, String... placeholders) {
        return mm.deserialize(raw(key), resolvers(placeholders));
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] resolvers(String... kv) {
        var out = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[kv.length / 2];
        for (int i = 0; i < kv.length; i += 2) out[i / 2] = Placeholder.unparsed(kv[i], kv[i + 1]);
        return out;
    }
}
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml \
        src/main/java/de/derfakegamer/sentinel/util/Messages.java
git commit -m "feat: messages and config defaults"
```

---

## Task 8: Wire Sentinel main class (DB, manager, config)

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`

- [ ] **Step 1: Replace `Sentinel.java` with the wired version**

```java
package de.derfakegamer.sentinel;

import de.derfakegamer.sentinel.manager.PunishmentManager;
import de.derfakegamer.sentinel.storage.Database;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import de.derfakegamer.sentinel.util.Messages;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class Sentinel extends JavaPlugin {
    private Database database;
    private PunishmentManager punishmentManager;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        this.messages = new Messages(loadMessages());
        try {
            this.database = new Database(new File(getDataFolder(), "sentinel.db"));
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.punishmentManager = new PunishmentManager(new PunishmentDao(database), loadExempt());
        getLogger().info("Sentinel enabled.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            try { database.close(); } catch (Exception ignored) {}
        }
    }

    public PunishmentManager punishments() { return punishmentManager; }
    public Messages messages() { return messages; }

    public void reloadAll() {
        reloadConfig();
        this.messages.reload(loadMessages());
        this.punishmentManager = new PunishmentManager(new PunishmentDao(database), loadExempt());
    }

    private Set<UUID> loadExempt() {
        Set<UUID> out = new HashSet<>();
        for (String s : getConfig().getStringList("exempt")) {
            try { out.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    private org.bukkit.configuration.file.FileConfiguration loadMessages() {
        return org.bukkit.configuration.file.YamlConfiguration
            .loadConfiguration(new File(getDataFolder(), "messages.yml"));
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/Sentinel.java
git commit -m "feat: wire database, manager and messages into main plugin"
```

---

## Task 9: LoginListener (ban / ip-ban enforcement)

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java`
- Modify: `Sentinel.java` `onEnable()` to register it
- Test: `src/test/java/de/derfakegamer/sentinel/listener/LoginListenerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LoginListenerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void bannedPlayerCannotJoin() {
        Player p = server.addPlayer("Griefer");
        plugin.punishments().ban(p.getUniqueId(), "Griefer", p.getUniqueId(), "Admin", "hax", 0);
        // simulate a re-login
        Player rejoined = server.addPlayer("Griefer");
        // MockBukkit fires AsyncPlayerPreLoginEvent; banned players are kicked on join
        assertFalse(rejoined.isOnline());
    }

    @Test void normalPlayerCanJoin() {
        Player p = server.addPlayer("Friendly");
        assertTrue(p.isOnline());
    }
}
```

> **Note:** If the installed MockBukkit version does not fire `AsyncPlayerPreLoginEvent` for `addPlayer`, call the listener directly: construct an `AsyncPlayerPreLoginEvent`, invoke `listener.onPreLogin(event)`, and assert `event.getLoginResult() == KICK_BANNED`. Keep both the listener and one direct-invocation test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests LoginListenerTest`
Expected: FAIL — `LoginListener` does not exist.

- [ ] **Step 3: Write `LoginListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class LoginListener implements Listener {
    private final Sentinel plugin;

    public LoginListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        long now = System.currentTimeMillis();
        Punishment ban = plugin.punishments().activeBan(event.getUniqueId(), now);
        if (ban == null) {
            String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : null;
            if (ip != null) ban = plugin.punishments().activeIpBan(ip, now);
        }
        if (ban != null) {
            Component screen = plugin.messages().plain("ban-screen", "reason", ban.reason());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
    }
}
```

- [ ] **Step 4: Register it in `Sentinel.onEnable()`**

Add at the end of `onEnable()` (after the manager is built):

```java
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.LoginListener(this), this);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests LoginListenerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/listener/LoginListenerTest.java
git commit -m "feat: enforce bans and ip-bans at login"
```

---

## Task 10: ChatListener (mute enforcement)

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/listener/ChatListener.java`
- Modify: `Sentinel.onEnable()` to register it
- Test: `src/test/java/de/derfakegamer/sentinel/listener/ChatListenerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.derfakegamer.sentinel.Sentinel;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ChatListenerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void mutedPlayerChatIsCancelled() {
        Player p = server.addPlayer("Spammer");
        plugin.punishments().mute(p.getUniqueId(), "Spammer", p.getUniqueId(), "Admin", "spam", 0);
        ChatListener listener = new ChatListener(plugin);
        AsyncChatEvent event = new AsyncChatEvent(true, p, Set.of(),
            (src, msg) -> Component.text("hi"), Component.text("hi"), Component.text("hi"));
        listener.onChat(event);
        assertTrue(event.isCancelled());
    }
}
```

> **Note:** `AsyncChatEvent`'s constructor signature can differ between Paper builds. If it does not match, fall back to a MockBukkit-driven `p.chat("hi")` and assert the muted player received the "you-are-muted" message instead.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests ChatListenerTest`
Expected: FAIL — `ChatListener` does not exist.

- [ ] **Step 3: Write `ChatListener.java`**

```java
package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class ChatListener implements Listener {
    private final Sentinel plugin;

    public ChatListener(Sentinel plugin) { this.plugin = plugin; }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Punishment mute = plugin.punishments()
            .activeMute(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (mute != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().prefixed("you-are-muted", "reason", mute.reason()));
        }
    }
}
```

- [ ] **Step 4: Register it in `Sentinel.onEnable()`**

```java
getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ChatListener(this), this);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests ChatListenerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/ChatListener.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/listener/ChatListenerTest.java
git commit -m "feat: enforce mutes in chat"
```

---

## Task 11: Punishment command shortcuts

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/command/PunishmentCommands.java`
- Create: `src/main/java/de/derfakegamer/sentinel/command/SentinelCommand.java`
- Modify: `Sentinel.onEnable()` to register command executors

These commands are the optional shortcuts described in the spec; the future GUIs
(Plan 2) call the same `PunishmentManager` methods. All require OP.

- [ ] **Step 1: Write `SentinelCommand.java` (`/sentinel reload`)**

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class SentinelCommand implements CommandExecutor {
    private final Sentinel plugin;

    public SentinelCommand(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            sender.sendMessage(plugin.messages().prefixed("reloaded"));
            return true;
        }
        sender.sendMessage(plugin.messages().prefixed("usage", "usage", "/sentinel reload"));
        return true;
    }
}
```

- [ ] **Step 2: Write `PunishmentCommands.java`**

Handles `/ban /tempban /ipban /unban /mute /tempmute /unmute /kick /warn /history`.
All resolve the target by name via `Bukkit.getOfflinePlayer`, require OP, and use
`DurationParser` for the temp variants.

```java
package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.manager.PunishmentManager;
import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PunishmentCommands implements CommandExecutor {
    private final Sentinel plugin;

    public PunishmentCommands(Sentinel plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.isOp()) { sender.sendMessage(plugin.messages().prefixed("no-permission")); return true; }
        String cmd = command.getName().toLowerCase();
        PunishmentManager pm = plugin.punishments();
        UUID issuerId = (sender instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        String issuerName = sender.getName();
        long now = System.currentTimeMillis();

        switch (cmd) {
            case "ban", "ipban", "mute" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                String reason = join(args, 1);
                var result = switch (cmd) {
                    case "ban" -> pm.ban(t.id, t.name, issuerId, issuerName, reason, 0);
                    case "ipban" -> pm.ipBan(t.id, t.name, t.ip, issuerId, issuerName, reason, 0);
                    default -> pm.mute(t.id, t.name, issuerId, issuerName, reason, 0);
                };
                if (!result.isSuccess()) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
                String key = cmd.equals("mute") ? "muted" : "banned";
                announce(key, t.name, reason);
                if (!cmd.equals("mute")) kickIfOnline(t.id, "ban-screen", reason);
            }
            case "tempban", "tempmute" -> {
                if (args.length < 3) return usage(sender, "/" + cmd + " <player> <duration> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                long expiresAt;
                try { expiresAt = now + DurationParser.parse(args[1]); }
                catch (IllegalArgumentException e) { return usage(sender, "/" + cmd + " <player> 1d2h <reason>"); }
                String reason = join(args, 2);
                var result = cmd.equals("tempban")
                    ? pm.ban(t.id, t.name, issuerId, issuerName, reason, expiresAt)
                    : pm.mute(t.id, t.name, issuerId, issuerName, reason, expiresAt);
                if (!result.isSuccess()) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
                announce(cmd.equals("tempban") ? "banned" : "muted", t.name, reason);
                if (cmd.equals("tempban")) kickIfOnline(t.id, "ban-screen", reason);
            }
            case "kick", "warn" -> {
                if (args.length < 2) return usage(sender, "/" + cmd + " <player> <reason>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                String reason = join(args, 1);
                var result = cmd.equals("kick")
                    ? pm.kick(t.id, t.name, issuerId, issuerName, reason)
                    : pm.warn(t.id, t.name, issuerId, issuerName, reason);
                if (!result.isSuccess()) { sender.sendMessage(plugin.messages().prefixed("exempt")); return true; }
                announce(cmd.equals("kick") ? "kicked" : "warned", t.name, reason);
                if (cmd.equals("kick")) kickIfOnline(t.id, "ban-screen", reason);
            }
            case "unban", "unmute" -> {
                if (args.length < 1) return usage(sender, "/" + cmd + " <player>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                boolean ok = cmd.equals("unban") ? pm.unban(t.id, issuerName, now) : pm.unmute(t.id, issuerName, now);
                announce(cmd.equals("unban") ? "unbanned" : "unmuted", t.name, "");
            }
            case "history" -> {
                if (args.length < 1) return usage(sender, "/history <player>");
                Target t = resolve(sender, args[0]); if (t == null) return true;
                for (Punishment p : pm.history(t.id))
                    sender.sendMessage(plugin.messages().prefixed("warned", "player", p.type().name(), "reason", p.reason()));
            }
        }
        return true;
    }

    private record Target(UUID id, String name, String ip) {}

    private Target resolve(CommandSender sender, String name) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.getUniqueId() == null) { sender.sendMessage(plugin.messages().prefixed("player-not-found")); return null; }
        String ip = (op.getPlayer() != null && op.getPlayer().getAddress() != null)
            ? op.getPlayer().getAddress().getAddress().getHostAddress() : null;
        return new Target(op.getUniqueId(), name, ip);
    }

    private void kickIfOnline(UUID id, String key, String reason) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.kick(plugin.messages().plain(key, "reason", reason));
    }

    private void announce(String key, String player, String reason) {
        Bukkit.broadcast(plugin.messages().prefixed(key, "player", player, "reason", reason));
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(plugin.messages().prefixed("usage", "usage", usage));
        return true;
    }

    private String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
```

- [ ] **Step 3: Register executors in `Sentinel.onEnable()`**

```java
SentinelCommand sentinelCmd = new de.derfakegamer.sentinel.command.SentinelCommand(this);
getCommand("sentinel").setExecutor(sentinelCmd);
de.derfakegamer.sentinel.command.PunishmentCommands pc =
    new de.derfakegamer.sentinel.command.PunishmentCommands(this);
for (String c : new String[]{"ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn","history"})
    getCommand(c).setExecutor(pc);
```

- [ ] **Step 4: Build & run the full test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; all tests pass; shaded jar produced.

- [ ] **Step 5: Manual in-game smoke test (one MC 1.21.x server)**

1. Drop `build/libs/Sentinel-1.0.0.jar` into `plugins/`, start the server.
2. `/ban Notch testing` → Notch is kicked and cannot rejoin (sees ban screen).
3. `/unban Notch` → Notch can rejoin.
4. `/tempmute Notch 30s spam` → Notch's chat is blocked; after 30s chat works.
5. `/sentinel reload` → "configuration reloaded".

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/command/ \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java
git commit -m "feat: punishment command shortcuts and /sentinel reload"
```

---

## Self-Review Notes (verification of this plan against the spec)

- **Spec coverage (Plan 1 scope):** Ban/Tempban/IP-Ban/Unban ✓ (Task 6, 11), Mute/Tempmute/Unmute ✓, Kick ✓, Warn + history ✓, login enforcement ✓ (Task 9), mute enforcement ✓ (Task 10), exempt list ✓ (Task 6, 8), OP-only gate ✓ (Task 11), SQLite storage ✓ (Task 4–5), blue MiniMessage theme ✓ (Task 7), English messages ✓. Reports, Staffchat, Freeze, Vanish, Invsee/EChestSee, all GUIs, and the Auto-Updater are intentionally deferred to Plans 2–4.
- **Type consistency:** `PunishmentManager` method names (`ban`, `ipBan`, `mute`, `warn`, `kick`, `unban`, `unmute`, `activeBan`, `activeMute`, `activeIpBan`, `warnCount`, `history`) are used identically in Tasks 9–11. `Result.isSuccess()` used consistently. `Messages.prefixed/plain` signatures match all call sites.
- **Known runtime-version caveats** are flagged inline (MockBukkit event firing in Task 9, `AsyncChatEvent` constructor in Task 10, library coordinate pinning in the header).
```
