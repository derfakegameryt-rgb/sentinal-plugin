package de.derfakegamer.sentinel;

import de.derfakegamer.sentinel.command.SentinelCommand;
import de.derfakegamer.sentinel.manager.PunishmentManager;
import de.derfakegamer.sentinel.storage.Database;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import de.derfakegamer.sentinel.util.Messages;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Sentinel extends JavaPlugin {
    private Database database;
    private PunishmentManager punishmentManager;
    private Messages messages;
    private de.derfakegamer.sentinel.manager.ModerationService moderationService;
    private de.derfakegamer.sentinel.manager.ChatInputManager chatInputManager;
    private de.derfakegamer.sentinel.manager.ReportManager reportManager;
    private de.derfakegamer.sentinel.manager.StaffChatManager staffChatManager;
    private de.derfakegamer.sentinel.manager.FreezeManager freezeManager;
    private de.derfakegamer.sentinel.manager.VanishManager vanishManager;
    private de.derfakegamer.sentinel.updater.UpdateChecker updateChecker;
    private de.derfakegamer.sentinel.manager.PlayerDirectory playerDirectory;
    private de.derfakegamer.sentinel.manager.NoteManager noteManager;
    private de.derfakegamer.sentinel.manager.ChatModeration chatModeration;
    private de.derfakegamer.sentinel.manager.WarnEscalation warnEscalation;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        saveResource("messages.yml", false);
        mergeMessagesDefaults();
        this.messages = new Messages(loadMessages());
        try {
            this.database = new Database(new File(getDataFolder(), "sentinel.db"));
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.playerDirectory = new de.derfakegamer.sentinel.manager.PlayerDirectory(
            new de.derfakegamer.sentinel.storage.PlayerDao(database));
        this.noteManager = new de.derfakegamer.sentinel.manager.NoteManager(
            new de.derfakegamer.sentinel.storage.NoteDao(database));
        this.punishmentManager = new PunishmentManager(new PunishmentDao(database), loadExempt());
        this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
        this.chatInputManager = new de.derfakegamer.sentinel.manager.ChatInputManager();
        this.reportManager = new de.derfakegamer.sentinel.manager.ReportManager(this,
            new de.derfakegamer.sentinel.storage.ReportDao(database));
        this.staffChatManager = new de.derfakegamer.sentinel.manager.StaffChatManager(this);
        this.freezeManager = new de.derfakegamer.sentinel.manager.FreezeManager();
        this.vanishManager = new de.derfakegamer.sentinel.manager.VanishManager(this);
        this.chatModeration = new de.derfakegamer.sentinel.manager.ChatModeration(this);
        this.warnEscalation = new de.derfakegamer.sentinel.manager.WarnEscalation(this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.gui.GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.LoginListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.MoveListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.JoinQuitListener(this), this);
        SentinelCommand sentinelCmd = new de.derfakegamer.sentinel.command.SentinelCommand(this);
        getCommand("sentinel").setExecutor(sentinelCmd);
        getCommand("sn").setExecutor(sentinelCmd);
        getCommand("sentinel").setTabCompleter(sentinelCmd);
        getCommand("sn").setTabCompleter(sentinelCmd);
        de.derfakegamer.sentinel.command.PunishmentCommands pc =
            new de.derfakegamer.sentinel.command.PunishmentCommands(this);
        for (String c : new String[]{"ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn","history"}) {
            getCommand(c).setExecutor(pc);
            getCommand(c).setTabCompleter(pc);
        }
        getCommand("report").setExecutor(new de.derfakegamer.sentinel.command.ReportCommand(this));
        getCommand("sc").setExecutor(new de.derfakegamer.sentinel.command.StaffChatCommand(this));
        getCommand("clearchat").setExecutor(new de.derfakegamer.sentinel.command.ClearChatCommand(this));
        this.updateChecker = new de.derfakegamer.sentinel.updater.UpdateChecker(this);
        this.updateChecker.start();
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
    public de.derfakegamer.sentinel.manager.ModerationService moderation() { return moderationService; }
    public de.derfakegamer.sentinel.manager.ChatInputManager chatInput() { return chatInputManager; }
    public de.derfakegamer.sentinel.manager.ReportManager reports() { return reportManager; }
    public de.derfakegamer.sentinel.manager.StaffChatManager staffChat() { return staffChatManager; }
    public de.derfakegamer.sentinel.manager.FreezeManager freeze() { return freezeManager; }
    public de.derfakegamer.sentinel.manager.VanishManager vanish() { return vanishManager; }
    public de.derfakegamer.sentinel.updater.UpdateChecker updater() { return updateChecker; }
    public de.derfakegamer.sentinel.manager.PlayerDirectory players() { return playerDirectory; }
    public de.derfakegamer.sentinel.manager.NoteManager notes() { return noteManager; }
    public de.derfakegamer.sentinel.manager.ChatModeration chatModeration() { return chatModeration; }
    public de.derfakegamer.sentinel.manager.WarnEscalation escalation() { return warnEscalation; }

    public java.io.File pluginJar() { return getFile(); }

    public void reloadAll() {
        reloadConfig();
        this.messages.reload(loadMessages());
        this.punishmentManager = new PunishmentManager(new PunishmentDao(database), loadExempt());
        this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
        this.chatModeration = new de.derfakegamer.sentinel.manager.ChatModeration(this);
        this.warnEscalation = new de.derfakegamer.sentinel.manager.WarnEscalation(this);
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

    /**
     * Adds any message keys that exist in the bundled (jar) messages.yml but are
     * missing from the server's on-disk file, without overwriting the admin's own
     * values. This makes new keys from a plugin update appear automatically.
     */
    private void mergeMessagesDefaults() {
        File file = new File(getDataFolder(), "messages.yml");
        var onDisk = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        try (java.io.InputStream in = getResource("messages.yml")) {
            if (in == null) return;
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            onDisk.setDefaults(defaults);
            onDisk.options().copyDefaults(true);
            onDisk.save(file);
        } catch (java.io.IOException e) {
            getLogger().warning("Could not migrate messages.yml: " + e.getMessage());
        }
    }
}
