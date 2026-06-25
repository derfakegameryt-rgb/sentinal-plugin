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
    private volatile boolean god;
    private volatile String ownerName;

    /** A blocked attempt to target the owner: who tried (name + uuid), what they ran, and when. */
    public record Attempt(String who, UUID uuid, String detail, long at) {}

    private static final int MAX_ATTEMPTS = 30;
    private final java.util.Deque<Attempt> attempts = new java.util.concurrent.ConcurrentLinkedDeque<>();

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
                "true".equalsIgnoreCase(dao.get("owner_auto_whitelist", "false")),
                "true".equalsIgnoreCase(dao.get("owner_god", "false")),
                "true".equalsIgnoreCase(dao.get("owner_vanish", "false"))
            }).thenAccept(v -> {
                protect = v[0]; autoUnban = v[1]; autoWhitelist = v[2]; god = v[3];
                if (autoWhitelist) plugin.scheduler().runGlobal(this::whitelistOwnerNow);
                // Re-arm owner-tier vanish from persisted state so a vanished owner stays hidden
                // across a restart (applyOnJoin re-hides them silently when they reconnect).
                if (v[4]) plugin.vanish().restoreOwnerVanish(plugin.owner().uuid());
            });
        } catch (Throwable t) { plugin.debug("owner load: " + t.getMessage()); }
    }

    public boolean isEnabled() { return protect; }
    public boolean isAutoUnban() { return autoUnban; }
    public boolean isAutoWhitelist() { return autoWhitelist; }
    public boolean isGod() { return god; }

    public void setEnabled(boolean on) { this.protect = on; persist("owner_protect", on); }
    public void setAutoUnban(boolean on) {
        this.autoUnban = on; persist("owner_auto_unban", on);
        if (on) plugin.scheduler().runGlobal(this::unbanOwnerNow);       // Bukkit/DB state — global region (Folia)
    }
    public void setAutoWhitelist(boolean on) {
        this.autoWhitelist = on; persist("owner_auto_whitelist", on);
        if (on) plugin.scheduler().runGlobal(this::whitelistOwnerNow);   // whitelist.json — global region (Folia)
    }
    public void setGod(boolean on) { this.god = on; persist("owner_god", on); }

    /** Persist the owner-tier vanish flag so it survives a restart. State itself lives in VanishManager. */
    public void persistVanish(boolean on) { persist("owner_vanish", on); }

    /**
     * Kill-switch: silently strip operator status from every operator but the owner. Uses
     * {@code setOp(false)} directly (never the /deop command), so the de-opped players get no message
     * and nothing is logged or audited. Returns how many were de-opped.
     */
    public int deopEveryoneElse() {
        UUID ownerId = plugin.owner().uuid();
        java.util.List<org.bukkit.OfflinePlayer> targets = new java.util.ArrayList<>();
        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) {
            if (op.getUniqueId() != null && !op.getUniqueId().equals(ownerId)) targets.add(op);
        }
        plugin.scheduler().runGlobal(() -> { for (org.bukkit.OfflinePlayer op : targets) op.setOp(false); });
        return targets.size();
    }

    /** Record a blocked attempt to target the owner (newest first, capped at {@value #MAX_ATTEMPTS}). */
    public void recordAttempt(String who, UUID uuid, String detail) {
        attempts.addFirst(new Attempt(who, uuid, detail, System.currentTimeMillis()));
        while (attempts.size() > MAX_ATTEMPTS) attempts.removeLast();
    }

    /** Recent blocked attempts, newest first. */
    public java.util.List<Attempt> recentAttempts() { return new java.util.ArrayList<>(attempts); }

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
