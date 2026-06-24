package de.derfakegamer.sentinel.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/** Forward-only, idempotent, dialect-aware schema migrations. Runs after the baseline createSchema. */
public final class SchemaMigrator {
    private SchemaMigrator() {}

    record Migration(int version, Function<Database, List<String>> statements) {}

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
        migrate(db, log, MIGRATIONS);
    }

    /** Test seam: runs the given migrations (the public overload passes the real {@link #MIGRATIONS}). */
    static void migrate(Database db, Logger log, List<Migration> migrations) {
        SettingsDao settings = new SettingsDao(db);
        int current;
        try {
            current = Integer.parseInt(settings.get("schema_version", "0"));
        } catch (NumberFormatException e) {
            current = 0;
        }
        for (Migration m : migrations) {
            if (m.version() <= current) continue;
            applyMigration(db, settings, m, log);
        }
    }

    /**
     * Applies one migration atomically: all of its statements AND the schema_version bump commit
     * together, or the whole migration rolls back. SQLite supports transactional DDL, so a statement
     * that fails partway can never leave a half-migrated schema (which the next startup would re-run
     * against). A duplicate-column/already-exists error is still swallowed for idempotency.
     */
    private static void applyMigration(Database db, SettingsDao settings, Migration m, Logger log) {
        Connection c = db.connection();
        boolean prevAutoCommit;
        try {
            prevAutoCommit = c.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException("Schema migration V" + m.version() + " failed: cannot read autocommit", e);
        }
        try {
            c.setAutoCommit(false);
            for (String sql : m.statements().apply(db)) {
                try (Statement st = c.createStatement()) {
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
            c.commit();
            log.info("Applied schema migration V" + m.version());
        } catch (RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new RuntimeException("Schema migration V" + m.version() + " failed to commit", e);
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
        }
    }

    private static void rollbackQuietly(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) {}
    }

    /** True for "column/index already exists" errors that make a migration safe to re-run. */
    private static boolean isDuplicate(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("duplicate column") || msg.contains("already exists");
    }
}
