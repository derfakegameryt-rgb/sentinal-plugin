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
                "CREATE INDEX IF NOT EXISTS idx_pun_active ON punishments(target_uuid, type, active)",
                "CREATE INDEX IF NOT EXISTS idx_pun_type_active ON punishments(type, active)",
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
                "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                """
                CREATE TABLE IF NOT EXISTS audit (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT, details TEXT,
                  created_at INTEGER NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS idx_audit_created ON audit(created_at)",
                "CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit(actor)");
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
}
