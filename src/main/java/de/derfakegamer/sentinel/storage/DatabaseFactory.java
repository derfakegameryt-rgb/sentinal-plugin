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
