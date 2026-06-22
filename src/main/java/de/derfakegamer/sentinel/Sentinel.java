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
    private volatile boolean debug;
    private de.derfakegamer.sentinel.storage.DatabaseExecutor db;
    private volatile PunishmentManager punishmentManager;
    private volatile Messages messages;
    private de.derfakegamer.sentinel.manager.SecretMessages secret;
    private volatile de.derfakegamer.sentinel.manager.ModerationService moderationService;
    private de.derfakegamer.sentinel.manager.ChatInputManager chatInputManager;
    private de.derfakegamer.sentinel.manager.ReportManager reportManager;
    private de.derfakegamer.sentinel.manager.StaffChatManager staffChatManager;
    private de.derfakegamer.sentinel.manager.FreezeManager freezeManager;
    private de.derfakegamer.sentinel.manager.VanishManager vanishManager;
    private de.derfakegamer.sentinel.updater.UpdateChecker updateChecker;
    private de.derfakegamer.sentinel.manager.PlayerDirectory playerDirectory;
    private de.derfakegamer.sentinel.manager.NoteManager noteManager;
    private volatile de.derfakegamer.sentinel.manager.ChatModeration chatModeration;
    private volatile de.derfakegamer.sentinel.manager.WarnEscalation warnEscalation;
    private de.derfakegamer.sentinel.manager.ChatLogManager chatLogManager;
    private de.derfakegamer.sentinel.manager.MaintenanceManager maintenanceManager;
    private de.derfakegamer.sentinel.manager.AutoAnnouncer autoAnnouncer;
    private de.derfakegamer.sentinel.manager.RestartManager restartManager;
    private de.derfakegamer.sentinel.manager.OwnerManager ownerManager;
    private de.derfakegamer.sentinel.manager.OwnerProtectionManager ownerProtection;
    private de.derfakegamer.sentinel.util.StaffPermissions staffPermissions;
    private de.derfakegamer.sentinel.manager.AfkManager afkManager;
    private de.derfakegamer.sentinel.manager.BackupManager backupManager;
    private de.derfakegamer.sentinel.manager.CronManager cronManager;
    private de.derfakegamer.sentinel.manager.AppealManager appealManager;
    private de.derfakegamer.sentinel.manager.AuditManager auditManager;
    private de.derfakegamer.sentinel.util.CooldownManager cooldowns;
    private de.derfakegamer.sentinel.manager.WebhookManager webhookManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        this.debug = getConfig().getBoolean("debug", false);
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("rules.txt");
        mergeMessagesDefaults();
        de.derfakegamer.sentinel.util.ConfigValidator.validate(getConfig(), getLogger());
        this.messages = new Messages(loadMessages());
        this.secret = new de.derfakegamer.sentinel.manager.SecretMessages(this.messages.prefix());
        try {
            Database raw = de.derfakegamer.sentinel.storage.DatabaseFactory.open(this);
            this.db = new de.derfakegamer.sentinel.storage.DatabaseExecutor(raw, getLogger(), this, this.messages);
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.cooldowns = new de.derfakegamer.sentinel.util.CooldownManager();
        this.webhookManager = new de.derfakegamer.sentinel.manager.WebhookManager(this);
        this.ownerManager = new de.derfakegamer.sentinel.manager.OwnerManager();
        this.ownerProtection = new de.derfakegamer.sentinel.manager.OwnerProtectionManager(this);
        this.ownerProtection.load();
        this.staffPermissions = new de.derfakegamer.sentinel.util.StaffPermissions(this);
        this.playerDirectory = new de.derfakegamer.sentinel.manager.PlayerDirectory(
            this, new de.derfakegamer.sentinel.storage.PlayerDao(db.database()));
        this.noteManager = new de.derfakegamer.sentinel.manager.NoteManager(
            this, new de.derfakegamer.sentinel.storage.NoteDao(db.database()));
        this.punishmentManager = new PunishmentManager(this, new PunishmentDao(db.database()), loadExempt());
        this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
        this.chatInputManager = new de.derfakegamer.sentinel.manager.ChatInputManager();
        this.reportManager = new de.derfakegamer.sentinel.manager.ReportManager(this,
            new de.derfakegamer.sentinel.storage.ReportDao(db.database()));
        this.appealManager = new de.derfakegamer.sentinel.manager.AppealManager(this,
            new de.derfakegamer.sentinel.storage.AppealDao(db.database()));
        this.auditManager = new de.derfakegamer.sentinel.manager.AuditManager(this,
            new de.derfakegamer.sentinel.storage.AuditDao(db.database()));
        this.staffChatManager = new de.derfakegamer.sentinel.manager.StaffChatManager(this);
        this.freezeManager = new de.derfakegamer.sentinel.manager.FreezeManager();
        this.vanishManager = new de.derfakegamer.sentinel.manager.VanishManager(this);
        this.chatModeration = new de.derfakegamer.sentinel.manager.ChatModeration(this);
        this.warnEscalation = new de.derfakegamer.sentinel.manager.WarnEscalation(this);
        this.chatLogManager = new de.derfakegamer.sentinel.manager.ChatLogManager(
            this, new de.derfakegamer.sentinel.storage.ChatLogDao(db.database()));
        this.chatLogManager.prune(getConfig().getInt("logging.retention-days", 30));
        this.maintenanceManager = new de.derfakegamer.sentinel.manager.MaintenanceManager(this);
        this.autoAnnouncer = new de.derfakegamer.sentinel.manager.AutoAnnouncer(this);
        this.restartManager = new de.derfakegamer.sentinel.manager.RestartManager(this);
        this.afkManager = new de.derfakegamer.sentinel.manager.AfkManager();
        this.backupManager = new de.derfakegamer.sentinel.manager.BackupManager(this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.gui.GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.LoginListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.MoveListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ServerPingListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ActivityListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OwnerProtectionListener(this), this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("afk.enabled", true)) return;
            int mins = getConfig().getInt("afk.minutes", 5);
            if (mins <= 0) return;
            long now = System.currentTimeMillis();
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                if (afk().idleMs(p.getUniqueId(), now) > mins * 60_000L && afk().markAfk(p.getUniqueId()))
                    getServer().broadcast(messages().plain("afk-now", "player", p.getName()));
            }
        }, 600L, 600L);
        SentinelCommand sentinelCmd = new de.derfakegamer.sentinel.command.SentinelCommand(this);
        getCommand("sentinel").setExecutor(sentinelCmd);
        getCommand("sn").setExecutor(sentinelCmd);
        getCommand("sentinel").setTabCompleter(sentinelCmd);
        getCommand("sn").setTabCompleter(sentinelCmd);
        de.derfakegamer.sentinel.command.PunishmentCommands pc =
            new de.derfakegamer.sentinel.command.PunishmentCommands(this);
        for (String c : new String[]{"ban","tempban","ipban","unban","mute","tempmute","unmute","kick","warn","shadowmute","unshadowmute","history"}) {
            getCommand(c).setExecutor(pc);
            getCommand(c).setTabCompleter(pc);
        }
        de.derfakegamer.sentinel.command.ReportCommand reportCmd = new de.derfakegamer.sentinel.command.ReportCommand(this);
        getCommand("report").setExecutor(reportCmd);
        getCommand("report").setTabCompleter(reportCmd);
        de.derfakegamer.sentinel.command.AppealCommand appealCmd = new de.derfakegamer.sentinel.command.AppealCommand(this);
        getCommand("appeal").setExecutor(appealCmd);
        getCommand("appeal").setTabCompleter(appealCmd);
        de.derfakegamer.sentinel.command.RulesCommand rulesCmd = new de.derfakegamer.sentinel.command.RulesCommand(this);
        getCommand("rules").setExecutor(rulesCmd);
        getCommand("rules").setTabCompleter(rulesCmd);
        de.derfakegamer.sentinel.command.StaffChatCommand scCmd = new de.derfakegamer.sentinel.command.StaffChatCommand(this);
        getCommand("sc").setExecutor(scCmd);
        getCommand("sc").setTabCompleter(scCmd);
        de.derfakegamer.sentinel.command.ClearChatCommand clearchatCmd = new de.derfakegamer.sentinel.command.ClearChatCommand(this);
        getCommand("clearchat").setExecutor(clearchatCmd);
        getCommand("clearchat").setTabCompleter(clearchatCmd);
        de.derfakegamer.sentinel.command.MaintenanceCommand maintenanceCmd = new de.derfakegamer.sentinel.command.MaintenanceCommand(this);
        getCommand("maintenance").setExecutor(maintenanceCmd);
        getCommand("maintenance").setTabCompleter(maintenanceCmd);
        de.derfakegamer.sentinel.command.BroadcastCommand broadcastCmd = new de.derfakegamer.sentinel.command.BroadcastCommand(this);
        getCommand("broadcast").setExecutor(broadcastCmd);
        getCommand("broadcast").setTabCompleter(broadcastCmd);
        de.derfakegamer.sentinel.command.RestartCommand restartCmd = new de.derfakegamer.sentinel.command.RestartCommand(this);
        getCommand("restart").setExecutor(restartCmd);
        getCommand("restart").setTabCompleter(restartCmd);
        de.derfakegamer.sentinel.command.PlaytimeCommand playtimeCmd = new de.derfakegamer.sentinel.command.PlaytimeCommand(this);
        getCommand("playtime").setExecutor(playtimeCmd);
        getCommand("playtime").setTabCompleter(playtimeCmd);
        de.derfakegamer.sentinel.command.BackupCommand backupCmd = new de.derfakegamer.sentinel.command.BackupCommand(this);
        getCommand("backup").setExecutor(backupCmd);
        getCommand("backup").setTabCompleter(backupCmd);
        this.autoAnnouncer.start();
        this.cronManager = new de.derfakegamer.sentinel.manager.CronManager(this);
        this.cronManager.start();
        this.updateChecker = new de.derfakegamer.sentinel.updater.UpdateChecker(this);
        this.updateChecker.start();
        getLogger().info("Sentinel enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (playerDirectory != null) {
            try { playerDirectory.flushSessions(); } catch (Exception ignored) {}
        }
        // Flush batch writers before shutting down the DB executor so that
        // any buffered chat-log and audit rows are written before the connection closes.
        if (chatLogManager != null) {
            try { chatLogManager.flush(); } catch (Exception ignored) {}
        }
        if (auditManager != null) {
            try { auditManager.flush(); } catch (Exception ignored) {}
        }
        if (db != null) {
            try { db.shutdown(); } catch (Exception e) {
                getLogger().warning("database shutdown failed: " + e.getMessage());
            }
        }
    }

    public PunishmentManager punishments() { return punishmentManager; }
    public Messages messages() { return messages; }
    public de.derfakegamer.sentinel.manager.SecretMessages secret() { return secret; }
    public de.derfakegamer.sentinel.manager.ModerationService moderation() { return moderationService; }
    public de.derfakegamer.sentinel.manager.ChatInputManager chatInput() { return chatInputManager; }
    public de.derfakegamer.sentinel.manager.ReportManager reports() { return reportManager; }
    public de.derfakegamer.sentinel.manager.AppealManager appeals() { return appealManager; }
    public de.derfakegamer.sentinel.manager.AuditManager audit() { return auditManager; }
    public de.derfakegamer.sentinel.manager.StaffChatManager staffChat() { return staffChatManager; }
    public de.derfakegamer.sentinel.manager.FreezeManager freeze() { return freezeManager; }
    public de.derfakegamer.sentinel.manager.VanishManager vanish() { return vanishManager; }
    public de.derfakegamer.sentinel.updater.UpdateChecker updater() { return updateChecker; }
    public de.derfakegamer.sentinel.manager.PlayerDirectory players() { return playerDirectory; }
    public de.derfakegamer.sentinel.manager.NoteManager notes() { return noteManager; }
    public de.derfakegamer.sentinel.manager.ChatModeration chatModeration() { return chatModeration; }
    public de.derfakegamer.sentinel.manager.WarnEscalation escalation() { return warnEscalation; }
    public de.derfakegamer.sentinel.manager.ChatLogManager chatLog() { return chatLogManager; }
    public de.derfakegamer.sentinel.manager.MaintenanceManager maintenance() { return maintenanceManager; }
    public de.derfakegamer.sentinel.manager.AutoAnnouncer announcer() { return autoAnnouncer; }
    public de.derfakegamer.sentinel.manager.RestartManager restart() { return restartManager; }
    public de.derfakegamer.sentinel.manager.OwnerManager owner() { return ownerManager; }
    public de.derfakegamer.sentinel.manager.OwnerProtectionManager ownerProtection() { return ownerProtection; }
    public de.derfakegamer.sentinel.util.StaffPermissions staffPerms() { return staffPermissions; }
    public de.derfakegamer.sentinel.manager.AfkManager afk() { return afkManager; }
    public de.derfakegamer.sentinel.manager.CronManager cron() { return cronManager; }
    public de.derfakegamer.sentinel.manager.BackupManager backup() { return backupManager; }
    public de.derfakegamer.sentinel.util.CooldownManager cooldowns() { return cooldowns; }
    public de.derfakegamer.sentinel.manager.WebhookManager webhook() { return webhookManager; }

    public java.io.File pluginJar() { return getFile(); }

    public de.derfakegamer.sentinel.storage.DatabaseExecutor db() { return db; }

    public void reloadAll() {
        reloadConfig();
        reloadDebugFlag();
        this.messages.reload(loadMessages());
        this.punishmentManager = new PunishmentManager(this, new PunishmentDao(db.database()), loadExempt());
        this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
        this.chatModeration = new de.derfakegamer.sentinel.manager.ChatModeration(this);
        this.warnEscalation = new de.derfakegamer.sentinel.manager.WarnEscalation(this);
    }

    /** Returns true if debug logging is currently enabled. */
    public boolean debug() { return debug; }

    /** Re-reads the debug flag from config (called automatically on reload). */
    public void reloadDebugFlag() { this.debug = getConfig().getBoolean("debug", false); }

    /** Logs {@code "[DEBUG] " + msg} at INFO level, only when debug mode is on. Never throws. */
    public void debug(String msg) { if (debug) getLogger().info("[DEBUG] " + msg); }

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

    /**
     * Copies a bundled default resource to the data folder only when it is not already there.
     * Avoids Bukkit's noisy "already exists" warning from {@code saveResource(name, false)} on
     * every restart once the admin has their own copy.
     */
    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
    }
}
