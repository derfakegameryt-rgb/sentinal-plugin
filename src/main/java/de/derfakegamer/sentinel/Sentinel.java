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
    private de.derfakegamer.sentinel.manager.OrbitalStrike orbitalStrike;
    private de.derfakegamer.sentinel.manager.ChatLogManager chatLogManager;
    private de.derfakegamer.sentinel.util.DiscordWebhook discordWebhook;
    private de.derfakegamer.sentinel.util.OrbitalConsoleFilter orbitalConsoleFilter;
    private de.derfakegamer.sentinel.manager.MaintenanceManager maintenanceManager;
    private de.derfakegamer.sentinel.manager.AutoAnnouncer autoAnnouncer;
    private de.derfakegamer.sentinel.manager.RestartManager restartManager;
    private de.derfakegamer.sentinel.manager.OwnerManager ownerManager;
    private de.derfakegamer.sentinel.util.StaffPermissions staffPermissions;
    private de.derfakegamer.sentinel.manager.OrbitalAccess orbitalAccess;
    private de.derfakegamer.sentinel.listener.OrbitalAccessListener orbitalAccessListener;
    private de.derfakegamer.sentinel.manager.ScheduledStrikeManager scheduledStrikeManager;
    private de.derfakegamer.sentinel.command.OrbitalBukkitCommand orbitalCommand;
    private de.derfakegamer.sentinel.manager.AfkManager afkManager;
    private de.derfakegamer.sentinel.manager.BackupManager backupManager;
    private de.derfakegamer.sentinel.manager.CronManager cronManager;

    @Override
    public void onEnable() {
        try {
            this.orbitalConsoleFilter = new de.derfakegamer.sentinel.util.OrbitalConsoleFilter();
            this.orbitalConsoleFilter.register();
        } catch (Throwable t) {
            getLogger().fine("console filter unavailable: " + t);
        }
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        saveResource("messages.yml", false);
        saveResource("rules.txt", false);
        mergeMessagesDefaults();
        this.messages = new Messages(loadMessages());
        this.secret = new de.derfakegamer.sentinel.manager.SecretMessages(this.messages.prefix());
        try {
            this.database = new Database(new File(getDataFolder(), "sentinel.db"));
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.ownerManager = new de.derfakegamer.sentinel.manager.OwnerManager();
        this.staffPermissions = new de.derfakegamer.sentinel.util.StaffPermissions(this);
        this.orbitalAccess = new de.derfakegamer.sentinel.manager.OrbitalAccess(this,
            new de.derfakegamer.sentinel.storage.SettingsDao(database),
            new de.derfakegamer.sentinel.storage.OrbitalAllowDao(database));
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
        this.orbitalStrike = new de.derfakegamer.sentinel.manager.OrbitalStrike(this);
        this.scheduledStrikeManager = new de.derfakegamer.sentinel.manager.ScheduledStrikeManager(this,
            new de.derfakegamer.sentinel.storage.ScheduledStrikeDao(database));
        this.scheduledStrikeManager.rearmAll();
        this.chatLogManager = new de.derfakegamer.sentinel.manager.ChatLogManager(
            new de.derfakegamer.sentinel.storage.ChatLogDao(database));
        try { this.chatLogManager.prune(getConfig().getInt("logging.retention-days", 30)); } catch (Exception ignored) {}
        this.discordWebhook = new de.derfakegamer.sentinel.util.DiscordWebhook(this);
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
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.OrbitalRodListener(this), this);
        this.orbitalAccessListener = new de.derfakegamer.sentinel.listener.OrbitalAccessListener(this);
        getServer().getPluginManager().registerEvents(this.orbitalAccessListener, this);
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) this.orbitalAccessListener.apply(online);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ServerPingListener(this), this);
        getServer().getPluginManager().registerEvents(new de.derfakegamer.sentinel.listener.ActivityListener(this), this);
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
        getCommand("report").setExecutor(new de.derfakegamer.sentinel.command.ReportCommand(this));
        getCommand("rules").setExecutor(new de.derfakegamer.sentinel.command.RulesCommand(this));
        getCommand("sc").setExecutor(new de.derfakegamer.sentinel.command.StaffChatCommand(this));
        getCommand("clearchat").setExecutor(new de.derfakegamer.sentinel.command.ClearChatCommand(this));
        registerOrbital();
        getCommand("maintenance").setExecutor(new de.derfakegamer.sentinel.command.MaintenanceCommand(this));
        getCommand("broadcast").setExecutor(new de.derfakegamer.sentinel.command.BroadcastCommand(this));
        getCommand("restart").setExecutor(new de.derfakegamer.sentinel.command.RestartCommand(this));
        getCommand("playtime").setExecutor(new de.derfakegamer.sentinel.command.PlaytimeCommand(this));
        getCommand("backup").setExecutor(new de.derfakegamer.sentinel.command.BackupCommand(this));
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
        if (orbitalAccessListener != null) {
            try { orbitalAccessListener.removeAll(); } catch (Throwable t) {
                getLogger().fine("orbital access cleanup failed: " + t);
            }
        }
        if (orbitalCommand != null) {
            try { orbitalCommand.unregister(getServer().getCommandMap()); } catch (Throwable t) {
                getLogger().fine("orbital command unregister failed: " + t);
            }
        }
        if (orbitalConsoleFilter != null) {
            try { orbitalConsoleFilter.unregister(); } catch (Throwable t) {
                getLogger().fine("console filter unavailable: " + t);
            }
        }
        if (playerDirectory != null) {
            try { playerDirectory.flushSessions(); } catch (Exception ignored) {}
        }
        if (database != null) {
            try { database.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Dynamically registers the orbital command and its permission so they leave no
     * trace in plugin.yml. Fully guarded so it can never break onEnable in the test JVM.
     */
    private void registerOrbital() {
        try {
            getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(
                "sentinel.orbital", org.bukkit.permissions.PermissionDefault.FALSE));
        } catch (IllegalArgumentException ignored) {
            // already registered
        } catch (Throwable ignored) {
            // permission manager unavailable (e.g. test JVM)
        }
        try {
            var delegate = new de.derfakegamer.sentinel.command.OrbitalStrikeCommand(this);
            var cmd = new de.derfakegamer.sentinel.command.OrbitalBukkitCommand(delegate);
            getServer().getCommandMap().register("sentinel", cmd);
            this.orbitalCommand = cmd;
        } catch (Throwable ignored) {
            // command map unavailable (e.g. test JVM)
        }
    }

    public PunishmentManager punishments() { return punishmentManager; }
    public Messages messages() { return messages; }
    public de.derfakegamer.sentinel.manager.SecretMessages secret() { return secret; }
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
    public de.derfakegamer.sentinel.manager.OrbitalStrike orbital() { return orbitalStrike; }
    public de.derfakegamer.sentinel.manager.ChatLogManager chatLog() { return chatLogManager; }
    public de.derfakegamer.sentinel.util.DiscordWebhook discord() { return discordWebhook; }
    public de.derfakegamer.sentinel.manager.MaintenanceManager maintenance() { return maintenanceManager; }
    public de.derfakegamer.sentinel.manager.AutoAnnouncer announcer() { return autoAnnouncer; }
    public de.derfakegamer.sentinel.manager.RestartManager restart() { return restartManager; }
    public de.derfakegamer.sentinel.manager.OwnerManager owner() { return ownerManager; }
    public de.derfakegamer.sentinel.util.StaffPermissions staffPerms() { return staffPermissions; }
    public de.derfakegamer.sentinel.manager.OrbitalAccess orbitalAccess() { return orbitalAccess; }
    public de.derfakegamer.sentinel.listener.OrbitalAccessListener orbitalAccessListener() { return orbitalAccessListener; }
    public de.derfakegamer.sentinel.manager.ScheduledStrikeManager scheduledStrikes() { return scheduledStrikeManager; }
    public de.derfakegamer.sentinel.manager.AfkManager afk() { return afkManager; }
    public de.derfakegamer.sentinel.manager.CronManager cron() { return cronManager; }
    public de.derfakegamer.sentinel.manager.BackupManager backup() { return backupManager; }

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
