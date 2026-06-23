# Profile Override (display name + skin) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let staff set an online player's display name (chat/TAB/above-head) and skin (from any Mojang username) from the PlayerActions GUI, persisted in SQLite and re-applied on login.

**Architecture:** A new `profile_overrides` table + `ProfileOverrideDao` store the override. A `ProfileManager` applies it via the Paper `PlayerProfile` API (no NMS): live changes use `setPlayerProfile` + a hide/show resend; persisted overrides are written into the login profile in `LoginListener.onPreLogin` so the player sees their own override after relog. The GUI adds three buttons that collect input through the existing chat-input flow.

**Tech Stack:** Java 21, Paper API 1.21.11, SQLite (xerial), JUnit 5 + MockBukkit, Gradle.

## Global Constraints

- Paper API only — no NMS, no ProtocolLib, no new dependencies (keep the 3.9 MB jar).
- Display name is a single plain token: `^[A-Za-z0-9_]{1,16}$`, applied identically to chat, TAB, above-head. No colour/MiniMessage.
- Skin is stored as the Mojang `textures` property (value + signature) so re-apply needs no Mojang call.
- Database writes go through `plugin.db().execute(...)` / `submitWrite(...)`; reads through `plugin.db().submit(...)`. Bukkit/entity calls run on the main thread; Mojang `complete()` runs async.
- Follow existing code style (4-space indent, inline fully-qualified names are common, package-private statics for testable pure logic).
- New permission node `sentinel.profile` (default `op`).

---

### Task 1: `profile_overrides` schema

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/SqlDialect.java` (add a CREATE TABLE to the SQLITE `schemaStatements()` list)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/ProfileOverrideSchemaTest.java`

**Interfaces:**
- Produces: a `profile_overrides` table with columns `uuid TEXT PRIMARY KEY, display_name TEXT, skin_value TEXT, skin_signature TEXT, updated_by TEXT, updated_at INTEGER NOT NULL DEFAULT 0`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/storage/ProfileOverrideSchemaTest.java`:

```java
package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.*;

class ProfileOverrideSchemaTest {
    Database db;
    File tmp;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
    }

    @AfterEach
    void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test
    void profileOverridesTableExists() throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='profile_overrides'")) {
            assertTrue(rs.next(), "profile_overrides table should be created on startup");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*ProfileOverrideSchemaTest"`
Expected: FAIL (table not found → `rs.next()` is false).

- [ ] **Step 3: Add the table to the schema**

In `SqlDialect.java`, inside the `SQLITE.schemaStatements()` `List.of(...)`, add this entry right after the `notes` index line (`"CREATE INDEX IF NOT EXISTS idx_notes_target ON notes(target_uuid)",`):

```java
                """
                CREATE TABLE IF NOT EXISTS profile_overrides (
                  uuid TEXT PRIMARY KEY, display_name TEXT, skin_value TEXT, skin_signature TEXT,
                  updated_by TEXT, updated_at INTEGER NOT NULL DEFAULT 0)""",
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*ProfileOverrideSchemaTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/SqlDialect.java \
        src/test/java/de/derfakegamer/sentinel/storage/ProfileOverrideSchemaTest.java
git commit -m "feat: profile_overrides table"
```

---

### Task 2: `ProfileOverride` model + `ProfileOverrideDao`

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/model/ProfileOverride.java`
- Create: `src/main/java/de/derfakegamer/sentinel/storage/ProfileOverrideDao.java`
- Test: `src/test/java/de/derfakegamer/sentinel/storage/ProfileOverrideDaoTest.java`

**Interfaces:**
- Produces: `record ProfileOverride(UUID uuid, String displayName, String skinValue, String skinSignature, String updatedBy, long updatedAt)`.
- Produces: `ProfileOverrideDao(Database db)` with `void upsert(ProfileOverride)`, `ProfileOverride find(UUID)` (null if absent), `void delete(UUID)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/storage/ProfileOverrideDaoTest.java`:

```java
package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.model.ProfileOverride;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ProfileOverrideDaoTest {
    Database db;
    ProfileOverrideDao dao;
    File tmp;
    UUID id = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(tmp);
        dao = new ProfileOverrideDao(db);
    }

    @AfterEach
    void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test
    void findMissingReturnsNull() { assertNull(dao.find(id)); }

    @Test
    void upsertThenFind() {
        dao.upsert(new ProfileOverride(id, "Cooler", "VAL", "SIG", "Admin", 123L));
        ProfileOverride o = dao.find(id);
        assertNotNull(o);
        assertEquals("Cooler", o.displayName());
        assertEquals("VAL", o.skinValue());
        assertEquals("SIG", o.skinSignature());
        assertEquals("Admin", o.updatedBy());
        assertEquals(123L, o.updatedAt());
    }

    @Test
    void upsertReplacesByUuid() {
        dao.upsert(new ProfileOverride(id, "First", null, null, "A", 1L));
        dao.upsert(new ProfileOverride(id, "Second", "V", "S", "B", 2L));
        ProfileOverride o = dao.find(id);
        assertEquals("Second", o.displayName());
        assertEquals("V", o.skinValue());
    }

    @Test
    void nullableFieldsRoundTrip() {
        dao.upsert(new ProfileOverride(id, null, "V", "S", "A", 1L));
        ProfileOverride o = dao.find(id);
        assertNull(o.displayName());
        assertEquals("V", o.skinValue());
    }

    @Test
    void deleteRemoves() {
        dao.upsert(new ProfileOverride(id, "X", null, null, "A", 1L));
        dao.delete(id);
        assertNull(dao.find(id));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*ProfileOverrideDaoTest"`
Expected: FAIL (compile error — `ProfileOverride` / `ProfileOverrideDao` do not exist).

- [ ] **Step 3: Create the model**

Create `src/main/java/de/derfakegamer/sentinel/model/ProfileOverride.java`:

```java
package de.derfakegamer.sentinel.model;

import java.util.UUID;

/** A staff-set display-name and/or skin override for a player; either field may be null. */
public record ProfileOverride(UUID uuid, String displayName, String skinValue,
                              String skinSignature, String updatedBy, long updatedAt) {}
```

- [ ] **Step 4: Create the DAO**

Create `src/main/java/de/derfakegamer/sentinel/storage/ProfileOverrideDao.java`:

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.ProfileOverride;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class ProfileOverrideDao {
    private final Database db;

    public ProfileOverrideDao(Database db) { this.db = db; }

    public void upsert(ProfileOverride o) {
        String sql = """
            INSERT INTO profile_overrides (uuid,display_name,skin_value,skin_signature,updated_by,updated_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET display_name=excluded.display_name,
                skin_value=excluded.skin_value, skin_signature=excluded.skin_signature,
                updated_by=excluded.updated_by, updated_at=excluded.updated_at""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, o.uuid().toString());
            ps.setString(2, o.displayName());
            ps.setString(3, o.skinValue());
            ps.setString(4, o.skinSignature());
            ps.setString(5, o.updatedBy());
            ps.setLong(6, o.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public ProfileOverride find(UUID uuid) {
        String sql = "SELECT * FROM profile_overrides WHERE uuid=?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ProfileOverride(uuid, rs.getString("display_name"), rs.getString("skin_value"),
                    rs.getString("skin_signature"), rs.getString("updated_by"), rs.getLong("updated_at"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void delete(UUID uuid) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM profile_overrides WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*ProfileOverrideDaoTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/model/ProfileOverride.java \
        src/main/java/de/derfakegamer/sentinel/storage/ProfileOverrideDao.java \
        src/test/java/de/derfakegamer/sentinel/storage/ProfileOverrideDaoTest.java
git commit -m "feat: ProfileOverride model + DAO"
```

---

### Task 3: `ProfileManager` — pure helpers + scaffold

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java`

**Interfaces:**
- Consumes: `ProfileOverrideDao` (Task 2), `Sentinel`.
- Produces: `ProfileManager(Sentinel plugin, ProfileOverrideDao dao)`; `static boolean isValidName(String)`; `static com.destroystokyo.paper.profile.ProfileProperty texturesOf(com.destroystokyo.paper.profile.PlayerProfile)` (the `textures` property, or null).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java`:

```java
package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProfileManagerTest {

    @Test
    void validNamesAccepted() {
        assertTrue(ProfileManager.isValidName("Notch"));
        assertTrue(ProfileManager.isValidName("a_B9"));
        assertTrue(ProfileManager.isValidName("ABCDEFGHIJKLMNOP")); // 16 chars
    }

    @Test
    void invalidNamesRejected() {
        assertFalse(ProfileManager.isValidName(null));
        assertFalse(ProfileManager.isValidName(""));
        assertFalse(ProfileManager.isValidName("has space"));
        assertFalse(ProfileManager.isValidName("toolongname123456")); // 17 chars
        assertFalse(ProfileManager.isValidName("col<red>or"));
        assertFalse(ProfileManager.isValidName("dash-name"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*ProfileManagerTest"`
Expected: FAIL (compile error — `ProfileManager` does not exist).

- [ ] **Step 3: Create the class with pure helpers + scaffold**

Create `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`:

```java
package de.derfakegamer.sentinel.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.ProfileOverrideDao;

import java.util.regex.Pattern;

/**
 * Applies and persists staff-set display-name / skin overrides via the Paper PlayerProfile API.
 * No NMS: live changes resend the player with hide/show; persisted overrides are written into the
 * login profile by {@link #applyOnLogin}.
 */
public final class ProfileManager {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final Sentinel plugin;
    private final ProfileOverrideDao dao;

    public ProfileManager(Sentinel plugin, ProfileOverrideDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** A display name valid for the above-head profile name: 1–16 of [A-Za-z0-9_], no colour. */
    public static boolean isValidName(String s) { return s != null && NAME.matcher(s).matches(); }

    /** The Mojang "textures" property of a (completed) profile, or null if absent. */
    static ProfileProperty texturesOf(PlayerProfile profile) {
        for (ProfileProperty p : profile.getProperties()) {
            if ("textures".equals(p.getName())) return p;
        }
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*ProfileManagerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java \
        src/test/java/de/derfakegamer/sentinel/manager/ProfileManagerTest.java
git commit -m "feat: ProfileManager scaffold + name validation"
```

---

### Task 4: `ProfileManager` apply/setName/setSkin/reset/applyOnLogin + wire into `Sentinel`

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java`
- Test: `src/test/java/de/derfakegamer/sentinel/manager/ProfileApplyOnLoginTest.java`

**Interfaces:**
- Consumes: `Sentinel#db()` (`submit`, `execute`, `callback`), `Sentinel#audit()`, `ProfileOverrideDao`.
- Produces on `ProfileManager`:
  - `void setName(org.bukkit.entity.Player target, String name, String staff)`
  - `void setSkin(org.bukkit.entity.Player target, String sourceName, String staff, java.util.function.Consumer<Boolean> done)`
  - `void reset(org.bukkit.entity.Player target, String staff)`
  - `void applyOnLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event)`
- Produces on `Sentinel`: `ProfileManager profile()` accessor; field initialised in `onEnable`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/manager/ProfileApplyOnLoginTest.java`:

```java
package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.derfakegamer.sentinel.Sentinel;
import java.net.InetAddress;
import java.util.UUID;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ProfileApplyOnLoginTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
    }

    @AfterEach
    void teardown() { MockBukkit.unmock(); }

    @Test
    void appliesStoredDisplayNameToLoginProfile() throws Exception {
        UUID id = UUID.randomUUID();
        // store an override directly through the manager's DAO path
        plugin.db().execute(() -> new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database())
            .upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, "Renamed", null, null, "Admin", 1L)));
        Thread.sleep(200); // let the async write land

        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        plugin.profile().applyOnLogin(event);

        assertEquals("Renamed", event.getPlayerProfile().getName());
    }

    @Test
    void noOverrideLeavesProfileUntouched() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        plugin.profile().applyOnLogin(event);

        assertEquals("RealName", event.getPlayerProfile().getName());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*ProfileApplyOnLoginTest"`
Expected: FAIL (compile error — `plugin.profile()` / `applyOnLogin` do not exist).

- [ ] **Step 3: Add the apply methods to `ProfileManager`**

Append these methods inside `ProfileManager` (after `texturesOf`):

```java
    /** Builds the override profile and resends the (online) player. Main thread only. */
    private void applyLive(org.bukkit.entity.Player target, String name, String skinValue, String skinSig) {
        PlayerProfile profile = target.getPlayerProfile();
        if (name != null) profile.setName(name);
        if (skinValue != null) {
            profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
            profile.setProperty(new ProfileProperty("textures", skinValue, skinSig));
        }
        target.setPlayerProfile(profile);
        if (name != null) {
            target.playerListName(net.kyori.adventure.text.Component.text(name));
            target.displayName(net.kyori.adventure.text.Component.text(name));
        }
        resend(target);
    }

    /** Force other clients to re-track the target so the new name/skin renders. Main thread only. */
    private void resend(org.bukkit.entity.Player target) {
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!o.equals(target)) o.hidePlayer(plugin, target);
        }
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (!o.equals(target)) o.showPlayer(plugin, target);
            }
        }, 2L);
    }

    public void setName(org.bukkit.entity.Player target, String name, String staff) {
        java.util.UUID id = target.getUniqueId();
        plugin.db().callback(plugin.db().submit(() -> dao.find(id)), existing -> {
            String sv = existing != null ? existing.skinValue() : null;
            String ss = existing != null ? existing.skinSignature() : null;
            applyLive(target, name, sv, ss);
            long now = System.currentTimeMillis();
            plugin.db().execute(() -> dao.upsert(
                new de.derfakegamer.sentinel.model.ProfileOverride(id, name, sv, ss, staff, now)));
            plugin.audit().record(staff, "SETNAME", target.getName(), name);
        });
    }

    public void setSkin(org.bukkit.entity.Player target, String sourceName, String staff,
                        java.util.function.Consumer<Boolean> done) {
        java.util.UUID id = target.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile src = org.bukkit.Bukkit.createProfile(sourceName);
            boolean ok = src.complete(true);
            ProfileProperty tex = ok ? texturesOf(src) : null;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null || !t.isOnline() || tex == null) { done.accept(false); return; }
                plugin.db().callback(plugin.db().submit(() -> dao.find(id)), existing -> {
                    String nm = existing != null ? existing.displayName() : null;
                    applyLive(t, nm, tex.getValue(), tex.getSignature());
                    long now = System.currentTimeMillis();
                    plugin.db().execute(() -> dao.upsert(
                        new de.derfakegamer.sentinel.model.ProfileOverride(
                            id, nm, tex.getValue(), tex.getSignature(), staff, now)));
                    plugin.audit().record(staff, "SETSKIN", t.getName(), sourceName);
                    done.accept(true);
                });
            });
        });
    }

    public void reset(org.bukkit.entity.Player target, String staff) {
        java.util.UUID id = target.getUniqueId();
        String realName = target.getName();
        plugin.db().execute(() -> dao.delete(id));
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile real = org.bukkit.Bukkit.createProfile(id, realName);
            real.complete(true);
            ProfileProperty tex = texturesOf(real);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null) return;
                PlayerProfile profile = t.getPlayerProfile();
                profile.setName(realName);
                profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
                if (tex != null) profile.setProperty(tex);
                t.setPlayerProfile(profile);
                t.playerListName(null);
                t.displayName(null);
                resend(t);
                plugin.audit().record(staff, "RESETPROFILE", realName, "");
            });
        });
    }

    /** Writes a stored override into the login profile (async pre-login thread; blocking read OK). */
    public void applyOnLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        de.derfakegamer.sentinel.model.ProfileOverride o;
        try {
            o = plugin.db().submit(() -> dao.find(event.getUniqueId())).join();
        } catch (Exception e) {
            return; // never block a login on a profile lookup
        }
        if (o == null) return;
        PlayerProfile profile = event.getPlayerProfile();
        if (o.displayName() != null) profile.setName(o.displayName());
        if (o.skinValue() != null) {
            profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
            profile.setProperty(new ProfileProperty("textures", o.skinValue(), o.skinSignature()));
        }
    }
```

- [ ] **Step 4: Wire the manager into `Sentinel`**

In `Sentinel.java`:

Add a field near `webhookManager`:

```java
    private de.derfakegamer.sentinel.manager.ProfileManager profileManager;
```

In `onEnable`, right after the `noteManager` is created (the block that does `new ... NoteManager(...)`), add:

```java
        this.profileManager = new de.derfakegamer.sentinel.manager.ProfileManager(
            this, new de.derfakegamer.sentinel.storage.ProfileOverrideDao(db.database()));
```

Add an accessor near `notes()`:

```java
    public de.derfakegamer.sentinel.manager.ProfileManager profile() { return profileManager; }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*ProfileApplyOnLoginTest"`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/manager/ProfileManager.java \
        src/main/java/de/derfakegamer/sentinel/Sentinel.java \
        src/test/java/de/derfakegamer/sentinel/manager/ProfileApplyOnLoginTest.java
git commit -m "feat: ProfileManager apply/setName/setSkin/reset/applyOnLogin + wiring"
```

---

### Task 5: Apply overrides on login + permission node

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java`
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/java/de/derfakegamer/sentinel/listener/ProfileLoginListenerTest.java`

**Interfaces:**
- Consumes: `Sentinel#profile().applyOnLogin(event)` (Task 4).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/listener/ProfileLoginListenerTest.java`:

```java
package de.derfakegamer.sentinel.listener;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.derfakegamer.sentinel.Sentinel;
import java.net.InetAddress;
import java.util.UUID;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ProfileLoginListenerTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }

    @AfterEach
    void teardown() { MockBukkit.unmock(); }

    @Test
    void loginListenerAppliesStoredOverride() throws Exception {
        UUID id = UUID.randomUUID();
        plugin.db().execute(() -> new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database())
            .upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, "Nicked", null, null, "Admin", 1L)));
        Thread.sleep(200);

        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        new LoginListener(plugin).onPreLogin(event);

        assertEquals("Nicked", event.getPlayerProfile().getName());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*ProfileLoginListenerTest"`
Expected: FAIL (name is still "RealName" — listener does not apply overrides yet).

- [ ] **Step 3: Call applyOnLogin from the listener**

In `LoginListener.onPreLogin`, add this line immediately after the `plugin.players().record(...)` call:

```java
        plugin.profile().applyOnLogin(event);
```

- [ ] **Step 4: Add the permission node**

In `src/main/resources/plugin.yml`, under `permissions:`, add after the `sentinel.warn` block:

```yaml
  sentinel.profile:
    description: Change a player's display name and skin
    default: op
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests "*ProfileLoginListenerTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/listener/LoginListener.java \
        src/main/resources/plugin.yml \
        src/test/java/de/derfakegamer/sentinel/listener/ProfileLoginListenerTest.java
git commit -m "feat: apply profile overrides on login + sentinel.profile permission"
```

---

### Task 6: GUI buttons + messages + README

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/messages_de.yml`
- Modify: `README.md`
- Test: `src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsProfileButtonsTest.java`

**Interfaces:**
- Consumes: `Sentinel#profile()`, `Sentinel#chatInput()`, `Sentinel#staffPerms().canUse(player, "sentinel.profile")`, message keys below.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsProfileButtonsTest.java`:

```java
package de.derfakegamer.sentinel.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlayerActionsProfileButtonsTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }

    @AfterEach
    void teardown() { MockBukkit.unmock(); }

    @Test
    void onlineTargetShowsProfileButtons() {
        PlayerMock target = server.addPlayer("Target");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, "1.2.3.4");
        // slots 28/29/30 are set-name, set-skin, reset-profile
        assertEquals(Material.NAME_TAG, gui.getInventory().getItem(28).getType());
        assertEquals(Material.PLAYER_HEAD, gui.getInventory().getItem(29).getType());
        assertEquals(Material.WATER_BUCKET, gui.getInventory().getItem(30).getType());
    }

    @Test
    void offlineTargetHidesProfileButtons() {
        org.bukkit.OfflinePlayer target = server.getOfflinePlayer("Ghost");
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target, false, false, false, 0, null);
        assertNull(gui.getInventory().getItem(28));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests "*PlayerActionsProfileButtonsTest"`
Expected: FAIL (slots 28–30 are empty/filler, not the expected materials).

- [ ] **Step 3: Add the buttons to the GUI constructor**

In `PlayerActionsGui.java`, add slot constants next to the others (the `IPBAN, FREEGE...` line region):

```java
    private static final int SETNAME = 28, SETSKIN = 29, RESETPROFILE = 30;
```

Inside the constructor, within the existing `if (target.isOnline()) { ... }` block (after the `ECHEST` item is set), add:

```java
            inventory.setItem(SETNAME, Items.button(Material.NAME_TAG,
                plugin.messages().plain("gui.actions.setname"),
                plugin.messages().list("gui.actions.setname-lore")));
            inventory.setItem(SETSKIN, Items.button(Material.PLAYER_HEAD,
                plugin.messages().plain("gui.actions.setskin"),
                plugin.messages().list("gui.actions.setskin-lore")));
            inventory.setItem(RESETPROFILE, Items.button(Material.WATER_BUCKET,
                plugin.messages().plain("gui.actions.resetprofile"),
                plugin.messages().list("gui.actions.resetprofile-lore")));
```

- [ ] **Step 4: Add the click handlers**

In `PlayerActionsGui.onClick`, add these `case` branches inside the `switch (event.getRawSlot())` (next to `FREEZE`):

```java
            case SETNAME -> {
                if (!plugin.staffPerms().canUse(mod, "sentinel.profile")) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                Player online = target.getPlayer();
                if (online == null) { mod.sendMessage(plugin.messages().prefixed("profile-target-offline")); return; }
                mod.closeInventory();
                mod.sendMessage(plugin.messages().prefixed("profile-enter-name"));
                plugin.chatInput().await(mod.getUniqueId(), input -> {
                    Player t = target.getPlayer();
                    if (t == null) { mod.sendMessage(plugin.messages().prefixed("profile-target-offline")); return; }
                    if (!de.derfakegamer.sentinel.manager.ProfileManager.isValidName(input)) { mod.sendMessage(plugin.messages().prefixed("profile-bad-name")); return; }
                    plugin.profile().setName(t, input, mod.getName());
                    mod.sendMessage(plugin.messages().prefixed("profile-name-set", "player", name(), "name", input));
                });
            }
            case SETSKIN -> {
                if (!plugin.staffPerms().canUse(mod, "sentinel.profile")) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                Player online = target.getPlayer();
                if (online == null) { mod.sendMessage(plugin.messages().prefixed("profile-target-offline")); return; }
                mod.closeInventory();
                mod.sendMessage(plugin.messages().prefixed("profile-enter-skin"));
                plugin.chatInput().await(mod.getUniqueId(), input -> {
                    Player t = target.getPlayer();
                    if (t == null) { mod.sendMessage(plugin.messages().prefixed("profile-target-offline")); return; }
                    plugin.profile().setSkin(t, input, mod.getName(), ok ->
                        mod.sendMessage(plugin.messages().prefixed(
                            ok ? "profile-skin-set" : "profile-skin-not-found", "player", name(), "name", input)));
                });
            }
            case RESETPROFILE -> {
                if (!plugin.staffPerms().canUse(mod, "sentinel.profile")) { mod.sendMessage(plugin.messages().prefixed("no-permission")); return; }
                Player online = target.getPlayer();
                if (online == null) { mod.sendMessage(plugin.messages().prefixed("profile-target-offline")); return; }
                plugin.profile().reset(online, mod.getName());
                mod.sendMessage(plugin.messages().prefixed("profile-reset", "player", name()));
                mod.closeInventory();
            }
```

Note: replace `plugin.manager_isValidName(input)` with `de.derfakegamer.sentinel.manager.ProfileManager.isValidName(input)` (there is no such helper on `Sentinel`; call the static directly).

- [ ] **Step 5: Add message keys (English)**

In `src/main/resources/messages.yml`, add after the `import-failed:` line:

```yaml
profile-enter-name: "<#60A5FA>Type the new name in chat (max 16, letters/digits/underscore), or type <white>cancel<#60A5FA>."
profile-enter-skin: "<#60A5FA>Type the Mojang username whose skin to copy, or type <white>cancel<#60A5FA>."
profile-name-set: "<#60A5FA><player></#60A5FA> <gray>is now shown as <white><name></white>."
profile-skin-set: "<#60A5FA><player></#60A5FA> <gray>now wears <white><name></white>'s skin."
profile-skin-not-found: "<red>Could not fetch a skin for <white><name></white>."
profile-bad-name: "<red>Invalid name — max 16 characters, only letters, digits and underscore."
profile-reset: "<#60A5FA><player></#60A5FA><gray>'s name and skin were reset."
profile-target-offline: "<red>The player must be online for that."
```

Then add these under the `gui:` → `actions:` section (next to `echest-lore`):

```yaml
    setname: "<aqua>Set name"
    setname-lore:
      - "<gray>Change chat / TAB / above-head name"
    setskin: "<aqua>Set skin"
    setskin-lore:
      - "<gray>Copy a Mojang account's skin"
    resetprofile: "<aqua>Reset profile"
    resetprofile-lore:
      - "<gray>Restore real name and skin"
```

- [ ] **Step 6: Add message keys (German)**

In `src/main/resources/messages_de.yml`, add after the `import-failed:` line:

```yaml
profile-enter-name: "<#60A5FA>Tippe den neuen Namen in den Chat (max. 16, Buchstaben/Ziffern/Unterstrich), oder tippe <white>cancel<#60A5FA>."
profile-enter-skin: "<#60A5FA>Tippe den Mojang-Namen, dessen Skin kopiert werden soll, oder tippe <white>cancel<#60A5FA>."
profile-name-set: "<#60A5FA><player></#60A5FA> <gray>wird jetzt als <white><name></white> angezeigt."
profile-skin-set: "<#60A5FA><player></#60A5FA> <gray>trägt jetzt den Skin von <white><name></white>."
profile-skin-not-found: "<red>Konnte keinen Skin für <white><name></white> laden."
profile-bad-name: "<red>Ungültiger Name — max. 16 Zeichen, nur Buchstaben, Ziffern und Unterstrich."
profile-reset: "<#60A5FA><player></#60A5FA><gray>s Name und Skin wurden zurückgesetzt."
profile-target-offline: "<red>Der Spieler muss dafür online sein."
```

(The nested `gui.actions.*` labels are intentionally left to fall back to English.)

- [ ] **Step 7: Run the GUI + translation tests**

Run: `./gradlew test --no-daemon --tests "*PlayerActionsProfileButtonsTest" --tests "*MessagesLanguageTest"`
Expected: PASS. (`MessagesLanguageTest` confirms every German key exists in English and all top-level player-facing keys are translated.)

- [ ] **Step 8: Update the README**

In `README.md`, under the `**Staff tooling**` feature list, add a bullet:

```markdown
- **Profile override** — set an online player's display name (chat/TAB/above-head) and skin
  (copied from any Mojang username) from the player-actions GUI; persisted and re-applied on join
```

And add the permission row to the Permissions table (after the `sentinel.warn` row):

```markdown
| `sentinel.profile` | Change display name / skin |
```

- [ ] **Step 9: Full build**

Run: `./gradlew build --no-daemon`
Expected: BUILD SUCCESSFUL (all tests + spotlessCheck pass, jar built).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/gui/PlayerActionsGui.java \
        src/main/resources/messages.yml src/main/resources/messages_de.yml \
        README.md \
        src/test/java/de/derfakegamer/sentinel/gui/PlayerActionsProfileButtonsTest.java
git commit -m "feat: profile name/skin buttons in the player-actions GUI"
```

---

## Self-Review notes

- **Spec coverage:** §1 table → Task 1; §2 DAO/model → Task 2; §3 manager (validation, helpers, apply, setName/setSkin/reset, applyOnLogin) → Tasks 3–4; §4 GUI buttons → Task 6; §5 permission + messages → Tasks 5–6; §6 login application → Task 5; §7 edge cases → covered in Task 4 method bodies (online re-checks, invalid name, skin-not-found) and Task 6 handlers; §8 testing → schema/DAO/validation/applyOnLogin/GUI tests; §9 files → all touched.
- **Manual verification (post-merge, real server):** live skin/above-head rendering for other players is immediate; the target sees their own skin after a relog. Verify a relog re-applies via the login profile.
- **Type consistency:** `texturesOf` returns `ProfileProperty`; `applyLive(target, name, skinValue, skinSig)` signature used consistently; `setSkin(..., Consumer<Boolean> done)` matches the GUI callback; `ProfileOverride(uuid, displayName, skinValue, skinSignature, updatedBy, updatedAt)` used identically in DAO, manager, and tests.
