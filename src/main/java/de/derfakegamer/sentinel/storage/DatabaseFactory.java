package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.Sentinel;

import java.io.File;
import java.sql.SQLException;

/** Builds the database backend. Sentinel uses an embedded SQLite database. */
public final class DatabaseFactory {
    private DatabaseFactory() {}

    public static Database open(Sentinel plugin) throws SQLException {
        return new SqliteDatabase(new File(plugin.getDataFolder(), "sentinel.db"));
    }
}
