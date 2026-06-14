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
