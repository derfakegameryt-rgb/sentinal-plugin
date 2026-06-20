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
