# MySQL/MariaDB Backend Support — Design

**Date:** 2026-06-20
**Status:** Approved (design)

## Problem

Sentinel stores all data in a bundled SQLite file. That is perfect for a single server but
cannot back a **proxy network** (BungeeCord/Velocity) where several backend servers must
share one database so a ban on server A applies everywhere. We want an optional MySQL/MariaDB
backend while keeping SQLite the zero-setup default.

## Goal

- Add an optional MySQL/MariaDB backend, selectable via `config.yml`.
- SQLite remains the default; nobody who doesn't need MySQL has to set it up.
- Network-wide bans work when multiple servers point at one MySQL database.
- No change to the threading model, managers, or DAO behavior beyond the small dialect
  differences.

Non-goals: connection pooling, pub/sub cache invalidation (there are no cross-server caches),
automated MySQL integration tests via Docker/Testcontainers.

## Architecture

### `Database` becomes an interface

`storage/Database` becomes an interface with the current surface plus a dialect accessor:

```
interface Database extends AutoCloseable {
    Connection connection();   // the live connection (may be reconnected)
    SqlDialect dialect();
    void close();
}
```

Two implementations:
- `SqliteDatabase` — current behavior: one file connection, `PRAGMA busy_timeout=3000`,
  `journal_mode=WAL`, schema built with the SQLite dialect.
- `MysqlDatabase` — one connection to MySQL/MariaDB built from config, schema built with the
  MySQL dialect.

A factory (`Database.open(Sentinel plugin)` or a `DatabaseFactory`) reads `database.type` and
constructs the right implementation.

### Connection model — unchanged single connection, with reconnect

Keep exactly one long-lived connection per server, owned exclusively by the existing
single-thread `DatabaseExecutor`. No pool, no HikariCP. The moderation workload is low and
already serialized onto one thread, so one connection suffices and the executor threading,
managers, and DAO code stay unchanged.

New: guard against dropped connections (MySQL idle timeouts). Before running a task — or on a
caught `SQLException` — the executor (or the `Database`) calls `connection.isValid(1)` and, if
invalid, reopens the connection once before proceeding. Harmless for SQLite (always valid).

### Network-wide bans

No special mechanism needed. Each server has its own connection to the shared MySQL database;
login and GUI checks read the database every time, so a write on one server is visible to the
others on their next read. Orbital removal eliminated the only in-memory cross-server cache;
the `exempt` set is config-derived and identical on every server.

## The dialect abstraction

A small `SqlDialect` (interface with `SQLITE` and `MYSQL` implementations, or an enum)
captures ONLY the differences:

1. **Auto-increment primary key column:**
   - SQLite: `INTEGER PRIMARY KEY AUTOINCREMENT`
   - MySQL: `BIGINT AUTO_INCREMENT PRIMARY KEY`
2. **Schema setup:** SQLite emits `PRAGMA busy_timeout` / `journal_mode=WAL`; MySQL emits
   none. The `CREATE TABLE` schema (same tables, columns, indexes) is built through the
   dialect so only the PK column type differs.
3. **Upsert** (two call sites):
   - SQLite: `INSERT ... ON CONFLICT(<key>) DO UPDATE SET col=excluded.col`
   - MySQL: `INSERT ... ON DUPLICATE KEY UPDATE col=VALUES(col)`
   - Affects `PlayerDao.upsert` and `SettingsDao.set`.
4. **Case-insensitive name lookup:**
   - SQLite: `... COLLATE NOCASE` (on the `idx_players_name` index DDL and `PlayerDao.byName`)
   - MySQL: none — the default `utf8mb4_general_ci` collation is already case-insensitive.

Everything else stays standard SQL that runs on both engines. `Statement.RETURN_GENERATED_KEYS`
+ `getGeneratedKeys()` is JDBC-standard and already used; no change.

DAOs obtain these fragments from the `Database`/`SqlDialect` rather than hardcoding SQLite SQL.

### Driver

Add the MariaDB JDBC driver (`org.mariadb.jdbc:mariadb-java-client`) — works with both MySQL
and MariaDB, light license — shaded into the jar like `sqlite-jdbc`. Loaded only when
`database.type: mysql`.

## Configuration

New block in `config.yml`:

```yaml
database:
  type: sqlite            # sqlite | mysql
  mysql:
    host: localhost
    port: 3306
    database: sentinel
    user: sentinel
    password: ""
    properties: "useUnicode=true&characterEncoding=utf8mb4"   # optional JDBC params
```

SQLite remains the default and continues to use `sentinel.db` in the plugin folder.

`ConfigValidator` additions: when `database.type` is `mysql`, require non-blank
host/database/user and a port in 1–65535; an unknown `type` logs a warning and falls back to
SQLite.

## Error handling

- **Startup connect:** if MySQL connection fails in `onEnable`, log a clear `severe` message
  and disable the plugin (same as today's "failed to open database"). NO silent fallback to
  SQLite — that would silently write to the wrong database.
- **Runtime:** `isValid()` check + a single reconnect attempt before a task; if that also
  fails, the `SQLException` is logged by the executor as today and the login ban-check remains
  fail-open.

## Testing

- `SqlDialectTest` — verifies the generated SQL fragments per dialect (PK type, upsert syntax,
  collation clause, and that the built schema contains every expected table). Pure
  string/logic tests; no database server.
- Existing DAO/manager tests continue to run against **SQLite** (unchanged, green) and cover
  the standard path.
- `ConfigValidator` tests for the new `database.mysql` block (missing fields, bad port,
  unknown type).
- MySQL integration is **not** automated (would force a Docker/Testcontainers dependency).
  Instead: a documented manual smoke-test checklist against a real MySQL/MariaDB instance
  (start server with `database.type: mysql`, join, ban from a second account, reconnect to
  confirm the kick, open the History/Reports GUIs, verify rows in MySQL, restart and confirm
  persistence).

## Risks

- The dialect split is small and localized, but the upsert and schema DDL must be exactly
  right for MySQL. Mitigation: `SqlDialectTest` pins the generated SQL; manual smoke test
  exercises the real driver.
- A dead-but-not-detected connection could fail a single operation before reconnect logic
  triggers; mitigation: validate before each task and reconnect on `SQLException`.
- `SettingsDao` may be unused after orbital removal; this design keeps it working on both
  dialects regardless (out of scope to delete here).
