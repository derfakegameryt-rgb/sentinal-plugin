package de.derfakegamer.sentinel.storage;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/** Forward-only, idempotent, dialect-aware schema migrations. Runs after the baseline createSchema. */
public final class SchemaMigrator {
    private SchemaMigrator() {}

    private record Migration(int version, Function<Database, List<String>> statements) {}

    private static final List<Migration> MIGRATIONS = List.of(
        // V1: players.playtime — baseline CREATE already includes it on fresh installs; this brings
        // pre-playtime installs up to parity. Idempotent (duplicate-column swallowed).
        new Migration(1, db -> List.of(
            "ALTER TABLE players ADD COLUMN playtime INTEGER NOT NULL DEFAULT 0"))
    );

    public static int latestVersion() {
        int max = 0;
        for (Migration m : MIGRATIONS) max = Math.max(max, m.version());
        return max;
    }

    public static void migrate(Database db, Logger log) {
        SettingsDao settings = new SettingsDao(db);
        int current;
        try {
            current = Integer.parseInt(settings.get("schema_version", "0"));
        } catch (NumberFormatException e) {
            current = 0;
        }

        for (Migration m : MIGRATIONS) {
            if (m.version() <= current) continue;
            for (String sql : m.statements().apply(db)) {
                try (Statement st = db.connection().createStatement()) {
                    st.executeUpdate(sql);
                } catch (SQLException e) {
                    if (isDuplicate(e)) {
                        log.fine("Migration V" + m.version() + ": already applied (" + e.getMessage() + ")");
                    } else {
                        throw new RuntimeException("Schema migration V" + m.version() + " failed: " + sql, e);
                    }
                }
            }
            settings.set("schema_version", String.valueOf(m.version()));
            log.info("Applied schema migration V" + m.version());
        }
    }

    /** True for "column/index already exists" errors that make a migration safe to re-run. */
    private static boolean isDuplicate(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("duplicate column") || msg.contains("already exists");
    }
}
