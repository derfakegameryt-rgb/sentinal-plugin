# MySQL/MariaDB Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional MySQL/MariaDB backend selectable via `config.yml`, with SQLite remaining the zero-setup default, so a proxy network can share one database for network-wide bans.

**Architecture:** `Database` becomes an interface with `SqliteDatabase` and `MysqlDatabase` implementations, each owning a single long-lived JDBC connection used exclusively by the existing single-thread `DatabaseExecutor`. A `SqlDialect` abstraction owns the engine-specific SQL (full schema DDL, the two upserts, and the case-insensitive name collation). A factory picks the backend from config. The executor validates/reconnects the connection before each task (matters for MySQL idle timeouts; no-op for SQLite).

**Tech Stack:** Java 21, Paper API, JUnit 5, MockBukkit, SQLite (`org.xerial:sqlite-jdbc`), MariaDB JDBC (`org.mariadb.jdbc:mariadb-java-client`), Gradle shadow plugin.

## Global Constraints

- SQLite stays the default; a server with no `database` config must behave exactly as today.
- Only the `DatabaseExecutor` thread touches a `Connection` (unchanged). DAOs stay synchronous.
- No connection pool, no HikariCP — one long-lived connection per server.
- New runtime dependency limited to `org.mariadb.jdbc:mariadb-java-client`, shaded + relocated like sqlite-jdbc.
- MySQL connect failure at startup disables the plugin (no silent SQLite fallback).
- All existing tests run against SQLite and must stay green. No automated MySQL/Docker tests.
- Run `./gradlew test` after each task; commit only on green.

---

### Task 1: Add the MariaDB driver dependency

**Files:**
- Modify: `build.gradle.kts` (deps block ~line 22, `shadowJar` block ~line 34)

**Interfaces:**
- Produces: the `org.mariadb.jdbc` driver on the runtime classpath, relocated to `de.derfakegamer.sentinel.libs.mariadb`.

- [ ] **Step 1: Add the dependency**

In `build.gradle.kts`, after the `implementation("org.xerial:sqlite-jdbc:3.47.1.0")` line add:

```kotlin
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
```

And after the matching `testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")` line add:

```kotlin
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
```

- [ ] **Step 2: Relocate it in the shadow jar**

In the `tasks.shadowJar { ... }` block, after the existing
`relocate("org.sqlite", "de.derfakegamer.sentinel.libs.sqlite")` line add:

```kotlin
    relocate("org.mariadb.jdbc", "de.derfakegamer.sentinel.libs.mariadb")
```

- [ ] **Step 3: Verify it resolves and builds**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (dependency downloads, jar builds).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add shaded MariaDB JDBC driver for the MySQL backend"
```

---

### Task 2: `SqlDialect` abstraction

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/storage/SqlDialect.java`
- Test: `src/test/java/de/derfakegamer/sentinel/storage/SqlDialectTest.java`

**Interfaces:**
- Produces:
  - `interface SqlDialect` with:
    - `java.util.List<String> schemaStatements()` — all CREATE TABLE / CREATE INDEX statements in this dialect.
    - `String playersUpsert()` — full upsert SQL for the `players` table.
    - `String settingsUpsert()` — full upsert SQL for the `settings` table.
    - `String nameWhereCollate()` — suffix appended after `WHERE name=?` for case-insensitive match (`" COLLATE NOCASE"` for SQLite, `""` for MySQL).
  - `SqlDialect.SQLITE` and `SqlDialect.MYSQL` singleton instances.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SqlDialectTest {
    private static String schema(SqlDialect d) { return String.join("\n", d.schemaStatements()); }

    private static final String[] TABLES =
        {"punishments", "reports", "appeals", "players", "notes", "chatlog", "settings"};

    @Test void sqliteSchemaHasAllTablesAndSqliteTypes() {
        String s = schema(SqlDialect.SQLITE);
        for (String t : TABLES) assertTrue(s.contains("CREATE TABLE IF NOT EXISTS " + t), "missing " + t);
        assertTrue(s.contains("AUTOINCREMENT"));
        assertTrue(s.contains("COLLATE NOCASE"));
        assertFalse(s.contains("AUTO_INCREMENT"));
    }

    @Test void mysqlSchemaHasAllTablesAndMysqlTypes() {
        String s = schema(SqlDialect.MYSQL);
        for (String t : TABLES) assertTrue(s.contains("CREATE TABLE IF NOT EXISTS " + t), "missing " + t);
        assertTrue(s.contains("AUTO_INCREMENT"));
        assertTrue(s.contains("VARCHAR"));                 // keyed/indexed strings are VARCHAR, not TEXT
        assertFalse(s.contains("AUTOINCREMENT"));          // the SQLite keyword must not appear
        assertFalse(s.toUpperCase().contains("PRAGMA"));
        assertTrue(s.contains("`key`") && s.contains("`value`")); // reserved words are backticked
    }

    @Test void upsertsAreDialectCorrect() {
        assertTrue(SqlDialect.SQLITE.playersUpsert().contains("ON CONFLICT(uuid) DO UPDATE"));
        assertTrue(SqlDialect.MYSQL.playersUpsert().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(SqlDialect.SQLITE.settingsUpsert().contains("ON CONFLICT(key) DO UPDATE"));
        assertTrue(SqlDialect.MYSQL.settingsUpsert().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(SqlDialect.MYSQL.settingsUpsert().contains("`value`=VALUES(`value`)"));
    }

    @Test void nameCollateOnlyForSqlite() {
        assertEquals(" COLLATE NOCASE", SqlDialect.SQLITE.nameWhereCollate());
        assertEquals("", SqlDialect.MYSQL.nameWhereCollate());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SqlDialectTest'`
Expected: FAIL — `SqlDialect` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

```java
package de.derfakegamer.sentinel.storage;

import java.util.List;

/** Engine-specific SQL. Everything else in the DAOs is standard SQL that runs on both engines. */
public interface SqlDialect {
    List<String> schemaStatements();
    String playersUpsert();
    String settingsUpsert();
    String nameWhereCollate();

    SqlDialect SQLITE = new SqlDialect() {
        @Override public List<String> schemaStatements() {
            return List.of(
                """
                CREATE TABLE IF NOT EXISTS punishments (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  type TEXT NOT NULL, target_uuid TEXT NOT NULL, target_name TEXT NOT NULL,
                  target_ip TEXT, reason TEXT NOT NULL, issuer_uuid TEXT NOT NULL, issuer_name TEXT NOT NULL,
                  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0,
                  active INTEGER NOT NULL DEFAULT 1, removed_by TEXT, removed_at INTEGER NOT NULL DEFAULT 0)""",
                "CREATE INDEX IF NOT EXISTS idx_pun_target ON punishments(target_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_pun_ip ON punishments(target_ip)",
                """
                CREATE TABLE IF NOT EXISTS reports (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  reporter_uuid TEXT NOT NULL, reporter_name TEXT NOT NULL,
                  target_uuid TEXT NOT NULL, target_name TEXT NOT NULL, reason TEXT NOT NULL,
                  created_at INTEGER NOT NULL, handled INTEGER NOT NULL DEFAULT 0, handled_by TEXT)""",
                "CREATE INDEX IF NOT EXISTS idx_report_open ON reports(handled)",
                """
                CREATE TABLE IF NOT EXISTS appeals (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  punishment_id INTEGER, target_uuid TEXT NOT NULL, target_name TEXT NOT NULL,
                  type TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN',
                  created_at INTEGER NOT NULL, handled_by TEXT, handled_at INTEGER NOT NULL DEFAULT 0)""",
                "CREATE INDEX IF NOT EXISTS idx_appeal_open ON appeals(status)",
                "CREATE INDEX IF NOT EXISTS idx_appeal_target ON appeals(target_uuid)",
                """
                CREATE TABLE IF NOT EXISTS players (
                  uuid TEXT PRIMARY KEY, name TEXT NOT NULL, last_ip TEXT,
                  first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                  playtime INTEGER NOT NULL DEFAULT 0)""",
                "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)",
                "CREATE INDEX IF NOT EXISTS idx_players_ip ON players(last_ip)",
                """
                CREATE TABLE IF NOT EXISTS notes (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  target_uuid TEXT NOT NULL, author TEXT NOT NULL, text TEXT NOT NULL,
                  created_at INTEGER NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS idx_notes_target ON notes(target_uuid)",
                """
                CREATE TABLE IF NOT EXISTS chatlog (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uuid TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL,
                  created_at INTEGER NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS idx_chatlog_uuid ON chatlog(uuid)",
                "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        @Override public String playersUpsert() {
            return """
                INSERT INTO players (uuid,name,last_ip,first_seen,last_seen) VALUES (?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_ip=excluded.last_ip,
                    last_seen=excluded.last_seen""";
        }
        @Override public String settingsUpsert() {
            return "INSERT INTO settings (key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        }
        @Override public String nameWhereCollate() { return " COLLATE NOCASE"; }
    };

    SqlDialect MYSQL = new SqlDialect() {
        @Override public List<String> schemaStatements() {
            return List.of(
                """
                CREATE TABLE IF NOT EXISTS punishments (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  type VARCHAR(32) NOT NULL, target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(64) NOT NULL,
                  target_ip VARCHAR(64), reason TEXT NOT NULL, issuer_uuid VARCHAR(36) NOT NULL, issuer_name VARCHAR(64) NOT NULL,
                  created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL DEFAULT 0,
                  active TINYINT NOT NULL DEFAULT 1, removed_by VARCHAR(64), removed_at BIGINT NOT NULL DEFAULT 0)""",
                "CREATE INDEX idx_pun_target ON punishments(target_uuid)",
                "CREATE INDEX idx_pun_ip ON punishments(target_ip)",
                """
                CREATE TABLE IF NOT EXISTS reports (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  reporter_uuid VARCHAR(36) NOT NULL, reporter_name VARCHAR(64) NOT NULL,
                  target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(64) NOT NULL, reason TEXT NOT NULL,
                  created_at BIGINT NOT NULL, handled TINYINT NOT NULL DEFAULT 0, handled_by VARCHAR(64))""",
                "CREATE INDEX idx_report_open ON reports(handled)",
                """
                CREATE TABLE IF NOT EXISTS appeals (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  punishment_id BIGINT, target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(64) NOT NULL,
                  type VARCHAR(32) NOT NULL, text TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
                  created_at BIGINT NOT NULL, handled_by VARCHAR(64), handled_at BIGINT NOT NULL DEFAULT 0)""",
                "CREATE INDEX idx_appeal_open ON appeals(status)",
                "CREATE INDEX idx_appeal_target ON appeals(target_uuid)",
                """
                CREATE TABLE IF NOT EXISTS players (
                  uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, last_ip VARCHAR(64),
                  first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL,
                  playtime BIGINT NOT NULL DEFAULT 0)""",
                "CREATE INDEX idx_players_name ON players(name)",
                "CREATE INDEX idx_players_ip ON players(last_ip)",
                """
                CREATE TABLE IF NOT EXISTS notes (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  target_uuid VARCHAR(36) NOT NULL, author VARCHAR(64) NOT NULL, text TEXT NOT NULL,
                  created_at BIGINT NOT NULL)""",
                "CREATE INDEX idx_notes_target ON notes(target_uuid)",
                """
                CREATE TABLE IF NOT EXISTS chatlog (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  uuid VARCHAR(36) NOT NULL, name VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL, text TEXT NOT NULL,
                  created_at BIGINT NOT NULL)""",
                "CREATE INDEX idx_chatlog_uuid ON chatlog(uuid)",
                "CREATE TABLE IF NOT EXISTS settings (`key` VARCHAR(255) PRIMARY KEY, `value` TEXT NOT NULL)");
        }
        @Override public String playersUpsert() {
            return """
                INSERT INTO players (uuid,name,last_ip,first_seen,last_seen) VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE name=VALUES(name), last_ip=VALUES(last_ip), last_seen=VALUES(last_seen)""";
        }
        @Override public String settingsUpsert() {
            return "INSERT INTO settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)";
        }
        @Override public String nameWhereCollate() { return ""; }
    };
}
```

Note: MySQL `CREATE INDEX` has no `IF NOT EXISTS`; that is fine because the tables are created with `IF NOT EXISTS` and indexes are created with them on first run. Re-running on an existing MySQL schema would error on the index — handled in Task 5's `MysqlDatabase` by ignoring "duplicate key name" errors (mirroring the SQLite `IF NOT EXISTS` semantics).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SqlDialectTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/derfakegamer/sentinel/storage/SqlDialect.java \
        src/test/java/de/derfakegamer/sentinel/storage/SqlDialectTest.java
git commit -m "feat: add SqlDialect abstraction (SQLite + MySQL SQL)"
```

---

### Task 3: Turn `Database` into an interface backed by `SqliteDatabase`

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/Database.java` (becomes the interface)
- Create: `src/main/java/de/derfakegamer/sentinel/storage/SqliteDatabase.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java:56` (construct `SqliteDatabase`)
- Modify (test constructions): the 10 DAO test files that call `new Database(tmp)` → `new SqliteDatabase(tmp)`:
  `ChatLogDaoTest`, `PunishmentDaoTest`, `PunishmentDaoActiveListTest`, `PlaytimeDaoTest`,
  `DatabaseExecutorTest`, `PlayerDaoTest`, `NoteDaoTest`, `AppealDaoTest`, `ReportDaoTest`, `SettingsDaoTest`

**Interfaces:**
- Consumes: `SqlDialect` (Task 2).
- Produces:
  - `interface Database extends AutoCloseable` with `Connection connection()`, `SqlDialect dialect()`, `void ensureValid()`, `void close()`.
  - `class SqliteDatabase implements Database` with constructor `SqliteDatabase(java.io.File file) throws SQLException`.

- [ ] **Step 1: Replace `Database.java` with the interface**

Replace the entire contents of `Database.java` with:

```java
package de.derfakegamer.sentinel.storage;

import java.sql.Connection;

/** A database backend. The single live connection is used only by the DatabaseExecutor thread. */
public interface Database extends AutoCloseable {
    Connection connection();
    SqlDialect dialect();
    /** Ensure the connection is alive; reconnect if it has dropped (no-op for healthy connections). */
    void ensureValid();
    @Override void close();
}
```

- [ ] **Step 2: Create `SqliteDatabase`**

```java
package de.derfakegamer.sentinel.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteDatabase implements Database {
    private final Connection connection;

    public SqliteDatabase(File file) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 3000;");
            st.execute("PRAGMA journal_mode = WAL;");
        }
        createSchema();
    }

    @Override public Connection connection() { return connection; }
    @Override public SqlDialect dialect() { return SqlDialect.SQLITE; }
    @Override public void ensureValid() { /* local file connection: always valid */ }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : SqlDialect.SQLITE.schemaStatements()) st.executeUpdate(sql);
        }
        // Back-compat: older DBs created before the playtime column existed.
        try (Statement alter = connection.createStatement()) {
            alter.executeUpdate("ALTER TABLE players ADD COLUMN playtime INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ignored) { /* column already exists */ }
    }

    @Override public void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
```

Note: the SQLite schema now includes `playtime` in the `players` CREATE TABLE (see Task 2),
so the `ALTER TABLE` only matters for pre-existing databases; the catch keeps it idempotent.

- [ ] **Step 3: Update `Sentinel.java`**

At line ~56 replace `Database raw = new Database(new File(getDataFolder(), "sentinel.db"));` with:

```java
            Database raw = new SqliteDatabase(new File(getDataFolder(), "sentinel.db"));
```

(Import is same package path; use the fully-qualified `de.derfakegamer.sentinel.storage.SqliteDatabase` if `Sentinel` doesn't already import the storage package — it constructs via `new SqliteDatabase(...)`, add the import.)

- [ ] **Step 4: Update the 10 DAO test constructions**

In each of the 10 listed test files, change `new Database(tmp)` (or `new Database(f)`) to
`new SqliteDatabase(tmp)` (respectively `new SqliteDatabase(f)`). The variable stays typed as
`Database` (the interface). Example for `DatabaseExecutorTest.java:18`:

```java
        db = new SqliteDatabase(f);
```

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS — behavior identical; SQLite path unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: make Database an interface backed by SqliteDatabase"
```

---

### Task 4: Route the dialect-specific DAO statements through `dialect()`

**Files:**
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/PlayerDao.java` (`upsert`, `byName`)
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/SettingsDao.java` (`set`)

**Interfaces:**
- Consumes: `Database.dialect()` → `SqlDialect.playersUpsert()`, `settingsUpsert()`, `nameWhereCollate()`.

- [ ] **Step 1: Update `PlayerDao.upsert`**

Replace the hardcoded `String sql = """ ... """;` in `upsert` with:

```java
            String sql = db.dialect().playersUpsert();
```

(Leave the parameter binding and execute unchanged.)

- [ ] **Step 2: Update `PlayerDao.byName`**

Replace the prepared statement SQL in `byName` with:

```java
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT * FROM players WHERE name=?" + db.dialect().nameWhereCollate() + " LIMIT 1")) {
```

- [ ] **Step 3: Update `SettingsDao.set`**

Replace the hardcoded upsert `String sql = "...";` in `set` with:

```java
            String sql = db.dialect().settingsUpsert();
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS — for SQLite, `dialect()` returns the same SQL as before, so behavior is unchanged. Existing `PlayerDaoTest`/`SettingsDaoTest` cover upsert + byName.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: DAOs get dialect-specific SQL from Database.dialect()"
```

---

### Task 5: `MysqlDatabase`, factory selection, and reconnect

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/storage/MysqlDatabase.java`
- Create: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseFactory.java`
- Modify: `src/main/java/de/derfakegamer/sentinel/Sentinel.java` (use the factory)
- Modify: `src/main/java/de/derfakegamer/sentinel/storage/DatabaseExecutor.java` (call `ensureValid()` before each task)
- Test: `src/test/java/de/derfakegamer/sentinel/storage/DatabaseExecutorTest.java` (assert ensureValid is invoked)

**Interfaces:**
- Consumes: `Database`, `SqlDialect.MYSQL`, config values.
- Produces:
  - `class MysqlDatabase implements Database` with constructor
    `MysqlDatabase(String host, int port, String database, String user, String password, String properties) throws SQLException`.
  - `class DatabaseFactory` with static `Database open(de.derfakegamer.sentinel.Sentinel plugin) throws SQLException`.

- [ ] **Step 1: Create `MysqlDatabase`**

```java
package de.derfakegamer.sentinel.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class MysqlDatabase implements Database {
    private final String url;
    private final String user;
    private final String password;
    private volatile Connection connection;

    public MysqlDatabase(String host, int port, String database, String user,
                         String password, String properties) throws SQLException {
        String props = (properties == null || properties.isBlank()) ? "" : "?" + properties;
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + props;
        this.user = user;
        this.password = password;
        this.connection = DriverManager.getConnection(url, user, password);
        createSchema();
    }

    @Override public Connection connection() { return connection; }
    @Override public SqlDialect dialect() { return SqlDialect.MYSQL; }

    @Override public void ensureValid() {
        try {
            if (connection != null && connection.isValid(2)) return;
        } catch (SQLException ignored) { /* fall through to reconnect */ }
        try {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
            connection = DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new RuntimeException("Sentinel: MySQL reconnect failed", e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : SqlDialect.MYSQL.schemaStatements()) {
                try {
                    st.executeUpdate(sql);
                } catch (SQLException e) {
                    // MySQL CREATE INDEX has no IF NOT EXISTS; ignore "duplicate key name" on re-run.
                    String m = String.valueOf(e.getMessage()).toLowerCase();
                    if (!m.contains("duplicate key name") && !m.contains("already exists")) throw e;
                }
            }
        }
    }

    @Override public void close() {
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
    }
}
```

- [ ] **Step 2: Create `DatabaseFactory`**

```java
package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.SQLException;

/** Builds the configured Database backend. SQLite is the default. */
public final class DatabaseFactory {
    private DatabaseFactory() {}

    public static Database open(Sentinel plugin) throws SQLException {
        FileConfiguration cfg = plugin.getConfig();
        String type = cfg.getString("database.type", "sqlite").trim().toLowerCase();
        if (type.equals("mysql")) {
            String host = cfg.getString("database.mysql.host", "localhost");
            int port = cfg.getInt("database.mysql.port", 3306);
            String database = cfg.getString("database.mysql.database", "sentinel");
            String user = cfg.getString("database.mysql.user", "sentinel");
            String password = cfg.getString("database.mysql.password", "");
            String properties = cfg.getString("database.mysql.properties", "");
            plugin.getLogger().info("Sentinel: using MySQL backend at " + host + ":" + port + "/" + database);
            return new MysqlDatabase(host, port, database, user, password, properties);
        }
        if (!type.equals("sqlite"))
            plugin.getLogger().warning("Sentinel: unknown database.type '" + type + "', using sqlite");
        return new SqliteDatabase(new File(plugin.getDataFolder(), "sentinel.db"));
    }
}
```

- [ ] **Step 3: Use the factory in `Sentinel.java`**

Replace the `Database raw = new SqliteDatabase(...)` line (from Task 3) with:

```java
            Database raw = de.derfakegamer.sentinel.storage.DatabaseFactory.open(this);
```

The surrounding `try/catch (Exception e)` that logs "Failed to open database" and disables the
plugin already covers a MySQL connect failure — no silent fallback. Leave that block as-is.

- [ ] **Step 4: Call `ensureValid()` before each executor task — write the failing test first**

Add to `DatabaseExecutorTest.java`:

```java
    @Test void ensureValidIsCalledBeforeWork() throws Exception {
        java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
        Database counting = new Database() {
            public java.sql.Connection connection() { return db.connection(); }
            public SqlDialect dialect() { return SqlDialect.SQLITE; }
            public void ensureValid() { validations.incrementAndGet(); }
            public void close() { }
        };
        DatabaseExecutor ex = new DatabaseExecutor(counting, java.util.logging.Logger.getLogger("t"), null);
        ex.submit(() -> 1).get(2, java.util.concurrent.TimeUnit.SECONDS);
        ex.execute(() -> {});
        ex.submit(() -> 2).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(validations.get() >= 2, "ensureValid must run before tasks");
        ex.shutdown();
    }
```

Run: `./gradlew test --tests '*DatabaseExecutorTest'`
Expected: FAIL — `ensureValid` is never called yet.

- [ ] **Step 5: Implement `ensureValid()` in the executor**

In `DatabaseExecutor`, in BOTH `submit(...)` and `execute(...)`, call `database.ensureValid()`
as the first line inside the `exec.execute(() -> { ... })` lambda's `try` block, before running
the user work. For `submit`:

```java
        exec.execute(() -> {
            try {
                database.ensureValid();
                f.complete(work.call());
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB read failed", t);
                f.completeExceptionally(t);
            }
        });
```

For `execute`:

```java
        exec.execute(() -> {
            try {
                database.ensureValid();
                work.run();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "DB write failed", t);
            }
        });
```

- [ ] **Step 6: Run tests**

Run: `./gradlew test --tests '*DatabaseExecutorTest'` then `./gradlew test`
Expected: PASS (full suite green; SQLite `ensureValid` is a no-op).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: MysqlDatabase + DatabaseFactory + connection revalidation"
```

---

### Task 6: Config block, validation, and docs

**Files:**
- Modify: `src/main/resources/config.yml` (add `database` block)
- Modify: `src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java` (validate `database`)
- Test: `src/test/java/de/derfakegamer/sentinel/util/ConfigValidatorTest.java`
- Modify: `README.md` (document the MySQL backend + manual smoke test)

**Interfaces:**
- Consumes: `ConfigValidator.validate(FileConfiguration, Logger)` (existing).

- [ ] **Step 1: Add the config block**

Insert into `config.yml` (e.g. before the `maintenance:` block):

```yaml
database:
  type: sqlite            # sqlite | mysql
  mysql:                  # only used when type is mysql
    host: localhost
    port: 3306
    database: sentinel
    user: sentinel
    password: ""
    properties: "useUnicode=true&characterEncoding=utf8mb4"   # optional JDBC params
```

- [ ] **Step 2: Write the failing validator tests**

Add to `ConfigValidatorTest.java`:

```java
    @Test void mysqlTypeRequiresHostDatabaseUser() {
        String yaml = "database:\n  type: mysql\n  mysql:\n    host: ''\n    port: 3306\n    database: ''\n    user: ''\n";
        assertTrue(warnings(yaml).stream().anyMatch(w -> w.contains("database.mysql")));
    }

    @Test void mysqlBadPortWarns() {
        String yaml = "database:\n  type: mysql\n  mysql:\n    host: h\n    port: 70000\n    database: d\n    user: u\n";
        assertTrue(warnings(yaml).stream().anyMatch(w -> w.contains("port")));
    }

    @Test void unknownDatabaseTypeWarns() {
        assertTrue(warnings("database:\n  type: postgres\n").stream().anyMatch(w -> w.contains("database.type")));
    }

    @Test void sqliteTypeProducesNoDatabaseWarning() {
        assertTrue(warnings("database:\n  type: sqlite\n").stream().noneMatch(w -> w.contains("database")));
    }
```

(Use the existing test's `warnings(String yaml)` helper that loads a `YamlConfiguration` from the
string, runs `ConfigValidator.validate`, and returns the collected warning messages. If that
helper is private/named differently, reuse whatever the existing tests use.)

Run: `./gradlew test --tests '*ConfigValidatorTest'`
Expected: FAIL — no database validation yet.

- [ ] **Step 3: Add database validation**

In `ConfigValidator.validate(...)`, add a check block:

```java
        String dbType = cfg.getString("database.type", "sqlite").trim().toLowerCase();
        if (!dbType.equals("sqlite") && !dbType.equals("mysql")) {
            log.warning("Sentinel config: database.type '" + dbType + "' is unknown — using sqlite. Use 'sqlite' or 'mysql'.");
        } else if (dbType.equals("mysql")) {
            if (cfg.getString("database.mysql.host", "").isBlank()
                || cfg.getString("database.mysql.database", "").isBlank()
                || cfg.getString("database.mysql.user", "").isBlank())
                log.warning("Sentinel config: database.mysql requires non-empty host, database, and user.");
            int port = cfg.getInt("database.mysql.port", 3306);
            if (port < 1 || port > 65535)
                log.warning("Sentinel config: database.mysql.port " + port + " is out of range (1-65535).");
        }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*ConfigValidatorTest'` then `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Document in README**

Add a "Database" section to `README.md` after Configuration:

```markdown
## Database

Sentinel uses **SQLite** by default — no setup required. To share one database across a
proxy network (so bans apply on every server), switch to **MySQL/MariaDB** in `config.yml`:

\```yaml
database:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: sentinel
    user: sentinel
    password: "secret"
\```

Each server pointing at the same MySQL database shares all punishments, reports, appeals,
notes, and chat logs. A MySQL connection failure at startup disables the plugin (it never
silently falls back to SQLite).

**Manual MySQL smoke test:** with `type: mysql`, start the server and confirm the tables are
created; ban a player from a second account and reconnect to confirm the kick; open the
History and Reports GUIs; verify rows appear in MySQL; restart and confirm data persists.
On a network, ban on one server and confirm the ban is enforced when joining another.
```

(Use real triple backticks in the README, not the escaped ones shown above.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: database config block, validation, and docs for MySQL backend"
```

---

## Self-Review

- **Spec coverage:** `Database` interface + `SqliteDatabase`/`MysqlDatabase` (Tasks 3, 5);
  single connection + reconnect via `ensureValid` (Task 5); `SqlDialect` for PK/schema/upsert/collate
  (Task 2, 4); MariaDB driver shaded (Task 1); config block + validation + no silent fallback
  (Tasks 5, 6); SQLite default and existing tests green (Tasks 3, 4); `SqlDialectTest` +
  ConfigValidator tests, manual MySQL smoke test documented (Tasks 2, 6). All spec sections map to tasks.
- **Placeholder scan:** every code step shows concrete code; no TBD/TODO; test bodies are real.
- **Type consistency:** `Database` methods (`connection()`, `dialect()`, `ensureValid()`, `close()`)
  are used identically in `SqliteDatabase`, `MysqlDatabase`, the executor, and the test double.
  `SqlDialect` method names (`schemaStatements`, `playersUpsert`, `settingsUpsert`, `nameWhereCollate`)
  match between Task 2 definition and Tasks 3/4 use.
- **Note (refinement vs spec):** the spec said "only the PK column type differs" in the schema;
  in practice MySQL also needs `VARCHAR` for keyed/indexed string columns and backticked
  `key`/`value`, so the dialect owns the FULL schema DDL per engine (Task 2). This is consistent
  with the spec's "schema is built through the dialect" and is the correct, faithful implementation.
