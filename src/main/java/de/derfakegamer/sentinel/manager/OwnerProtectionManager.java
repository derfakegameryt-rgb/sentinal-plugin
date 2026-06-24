package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.SettingsDao;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Owner self-protection toggles (command guard, auto-unban, auto-whitelist), persisted in settings. */
public final class OwnerProtectionManager {
    private final Sentinel plugin;
    private volatile boolean protect;
    private volatile boolean autoUnban;
    private volatile boolean autoWhitelist;
    private volatile String ownerName;

    public OwnerProtectionManager(Sentinel plugin) { this.plugin = plugin; }

    /** Convenience overload without a UUID. Prefer {@link #affectsOwner(String, String, String)}. */
    public static boolean affectsOwner(String commandLine, String ownerName) {
        return affectsOwner(commandLine, ownerName, null);
    }

    /**
     * True if the command line targets the owner: an argument token equal to {@code ownerName} or
     * {@code ownerUuid} (both case-insensitive), or ANY target selector other than {@code @s}.
     * Blocking every selector but {@code @s} (which is always the executor itself) covers
     * {@code @a/@e/@p/@r/@n} and any future selector; matching the UUID closes the
     * {@code /execute as <uuid> run ...} bypass that a bare name match would miss.
     */
    public static boolean affectsOwner(String commandLine, String ownerName, String ownerUuid) {
        if (commandLine == null) return false;
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String[] parts = line.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {            // skip parts[0] (the command label)
            String tok = parts[i];
            if (tok.isEmpty()) continue;
            if (ownerName != null && !ownerName.isBlank() && tok.equalsIgnoreCase(ownerName)) return true;
            if (ownerUuid != null && !ownerUuid.isBlank() && tok.equalsIgnoreCase(ownerUuid)) return true;
            String low = tok.toLowerCase();
            if (low.startsWith("@") && !low.startsWith("@s")) return true;   // any selector but self
        }
        return false;
    }

    public void load() {
        try {
            SettingsDao dao = new SettingsDao(plugin.db().database());
            plugin.db().submit(() -> new boolean[]{
                "true".equalsIgnoreCase(dao.get("owner_protect", "false")),
                "true".equalsIgnoreCase(dao.get("owner_auto_unban", "false")),
                "true".equalsIgnoreCase(dao.get("owner_auto_whitelist", "false"))
            }).thenAccept(v -> {
                protect = v[0]; autoUnban = v[1]; autoWhitelist = v[2];
                if (autoWhitelist) plugin.scheduler().runGlobal(this::whitelistOwnerNow);
            });
        } catch (Throwable t) { plugin.debug("owner load: " + t.getMessage()); }
    }

    public boolean isEnabled() { return protect; }
    public boolean isAutoUnban() { return autoUnban; }
    public boolean isAutoWhitelist() { return autoWhitelist; }

    public void setEnabled(boolean on) { this.protect = on; persist("owner_protect", on); }
    public void setAutoUnban(boolean on) { this.autoUnban = on; persist("owner_auto_unban", on); if (on) unbanOwnerNow(); }
    public void setAutoWhitelist(boolean on) { this.autoWhitelist = on; persist("owner_auto_whitelist", on); if (on) whitelistOwnerNow(); }

    /** Current owner name, cached after first resolution (avoids a lookup on the command hot path). */
    public String ownerName() {
        String n = ownerName;
        if (n != null) return n;
        UUID u = plugin.owner().uuid();
        Player online = Bukkit.getPlayer(u);
        n = (online != null) ? online.getName() : Bukkit.getOfflinePlayer(u).getName();
        if (n != null) ownerName = n;
        return n;
    }

    private void persist(String key, boolean on) {
        try {
            SettingsDao dao = new SettingsDao(plugin.db().database());
            plugin.db().submitWrite(() -> { dao.set(key, String.valueOf(on)); return null; });
        } catch (Throwable t) { plugin.debug("owner persist " + key + ": " + t.getMessage()); }
    }

    private void unbanOwnerNow() {
        try { plugin.punishments().unban(plugin.owner().uuid(), "AUTO", System.currentTimeMillis()); }
        catch (Throwable t) { plugin.debug("owner auto-unban: " + t.getMessage()); }
    }

    private void whitelistOwnerNow() {
        try { Bukkit.getOfflinePlayer(plugin.owner().uuid()).setWhitelisted(true); }
        catch (Throwable t) { plugin.debug("owner auto-whitelist: " + t.getMessage()); }
    }
}
