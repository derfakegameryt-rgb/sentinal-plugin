package de.derfakegamer.sentinel.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.ProfileOverrideDao;

import java.util.regex.Pattern;

/**
 * Applies and persists staff-set display-name / skin overrides via the Paper PlayerProfile API.
 * No NMS: live changes resend the player with hide/show; persisted overrides are written into the
 * login profile by {@link #applyOnLogin}.
 */
public final class ProfileManager {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final Sentinel plugin;
    private final ProfileOverrideDao dao;

    // Display-name overrides cached at pre-login so the join/quit broadcast can use them
    // synchronously — a DB read in the join handler would block the main thread. Keyed by UUID.
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, String> joinNames =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The cached display-name override for {@code id}, or null if none (read on the main thread). */
    public String overrideJoinName(java.util.UUID id) { return joinNames.get(id); }

    /** Drops the cached override name for {@code id} (called when the player quits). */
    public void evictJoinName(java.util.UUID id) { joinNames.remove(id); }

    public ProfileManager(Sentinel plugin, ProfileOverrideDao dao) {
        this.plugin = plugin;
        this.dao = dao;
    }

    /** A display name valid for the above-head profile name: 1–16 of [A-Za-z0-9_], no colour. */
    public static boolean isValidName(String s) { return s != null && NAME.matcher(s).matches(); }

    /** The Mojang "textures" property of a (completed) profile, or null if absent. */
    static ProfileProperty texturesOf(PlayerProfile profile) {
        for (ProfileProperty p : profile.getProperties()) {
            if ("textures".equals(p.getName())) return p;
        }
        return null;
    }

    /**
     * Applies a name/skin override to the (online) player. Main thread only.
     * Names are display-only (tab + chat) — we never change the account/profile name, so the server
     * never caches an override name and vanilla never shows "(formerly known as …)" on rejoin. Only
     * the skin swaps a texture property on the profile (the real name is preserved).
     */
    private void applyLive(org.bukkit.entity.Player target, String name, String skinValue, String skinSig) {
        if (skinValue != null) {
            PlayerProfile profile = target.getPlayerProfile();
            profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
            profile.setProperty(new ProfileProperty("textures", skinValue, skinSig));
            target.setPlayerProfile(profile);
        }
        if (name != null) {
            target.playerListName(net.kyori.adventure.text.Component.text(name));
            target.displayName(net.kyori.adventure.text.Component.text(name));
        }
        if (skinValue != null) resend(target); // re-render the model for other players
    }

    /** Force other clients to re-track the target so the new name/skin renders. Main thread only. */
    private void resend(org.bukkit.entity.Player target) {
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!o.equals(target)) {
                org.bukkit.entity.Player viewer = o;
                plugin.scheduler().runForEntity(viewer, () -> viewer.hidePlayer(plugin, target));
            }
        }
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!o.equals(target)) {
                org.bukkit.entity.Player viewer = o;
                plugin.scheduler().runForEntityLater(viewer, () -> viewer.showPlayer(plugin, target), 2L);
            }
        }
    }

    public void setName(org.bukkit.entity.Player target, String name, String staff) {
        java.util.UUID id = target.getUniqueId();
        long now = System.currentTimeMillis();
        // find + upsert on the single writer thread so the existing skin override is preserved atomically.
        plugin.db().callbackFor(target, plugin.db().submitWrite(() -> {
            de.derfakegamer.sentinel.model.ProfileOverride existing = dao.find(id);
            String sv = existing != null ? existing.skinValue() : null;
            String ss = existing != null ? existing.skinSignature() : null;
            dao.upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, name, sv, ss, staff, now));
            return new String[]{sv, ss};
        }), skin -> {
            if (!target.isOnline()) return;
            applyLive(target, name, skin[0], skin[1]);
            joinNames.put(id, name); // keep the join/quit broadcast consistent with a mid-session rename
            plugin.audit().record(staff, "SETNAME", target.getName(), name);
        });
    }

    public void setSkin(org.bukkit.entity.Player target, String sourceName, String staff,
                        java.util.function.Consumer<Boolean> done) {
        java.util.UUID id = target.getUniqueId();
        plugin.scheduler().runAsync(() -> {
            PlayerProfile src = org.bukkit.Bukkit.createProfile(sourceName);
            boolean ok = src.complete(true);
            ProfileProperty tex = ok ? texturesOf(src) : null;
            plugin.scheduler().runGlobal(() -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null || !t.isOnline() || tex == null) { done.accept(false); return; }
                long now = System.currentTimeMillis();
                // find + upsert on the single writer thread so the existing name override is preserved atomically.
                plugin.db().callbackFor(t, plugin.db().submitWrite(() -> {
                    de.derfakegamer.sentinel.model.ProfileOverride existing = dao.find(id);
                    String nm = existing != null ? existing.displayName() : null;
                    dao.upsert(new de.derfakegamer.sentinel.model.ProfileOverride(
                        id, nm, tex.getValue(), tex.getSignature(), staff, now));
                    return nm;
                }), nm -> {
                    if (!t.isOnline()) { done.accept(false); return; }
                    applyLive(t, nm, tex.getValue(), tex.getSignature());
                    plugin.audit().record(staff, "SETSKIN", t.getName(), sourceName);
                    done.accept(true);
                });
            });
        });
    }

    public void reset(org.bukkit.entity.Player target, String staff) {
        java.util.UUID id = target.getUniqueId();
        String realName = target.getName(); // the account name was never changed, so this is real
        plugin.db().execute(() -> dao.delete(id));
        // Clear the tab/chat name overlay immediately — self-visible in the tab list right away.
        plugin.scheduler().runForEntity(target, () -> {
            target.playerListName(null);
            target.displayName(null);
            joinNames.remove(id);
            plugin.audit().record(staff, "RESETPROFILE", realName, "");
        });
        // Restore the real skin (textures only; the real name on the profile is untouched).
        plugin.scheduler().runAsync(() -> {
            PlayerProfile real = org.bukkit.Bukkit.createProfile(id, realName);
            real.complete(true);
            ProfileProperty tex = texturesOf(real);
            plugin.scheduler().runGlobal(() -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null) return;
                plugin.scheduler().runForEntity(t, () -> {
                    PlayerProfile profile = t.getPlayerProfile();
                    profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
                    if (tex != null) profile.setProperty(tex);
                    t.setPlayerProfile(profile);
                    resend(t);
                });
            });
        });
    }

    /**
     * Writes a stored SKIN override into the login profile (async pre-login thread; blocking read
     * OK). The name override is NOT applied here — changing the login profile name would make the
     * server cache it and trigger vanilla's "(formerly known as …)" message; the display name is
     * applied after the player is online via {@link #applyNameOnJoin}.
     */
    public void applyOnLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        de.derfakegamer.sentinel.model.ProfileOverride o;
        try {
            o = plugin.db().submitWrite(() -> dao.find(event.getUniqueId())).join();
        } catch (Exception e) {
            return; // never block a login on a profile lookup
        }
        // Cache the display-name override (if any) so the join/quit broadcast can use it synchronously.
        if (o != null && o.displayName() != null) joinNames.put(event.getUniqueId(), o.displayName());
        else joinNames.remove(event.getUniqueId());
        if (o == null || o.skinValue() == null) return;
        PlayerProfile profile = event.getPlayerProfile();
        profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
        profile.setProperty(new ProfileProperty("textures", o.skinValue(), o.skinSignature()));
        event.setPlayerProfile(profile);
    }

    /** Re-applies a stored display-name override to the tab/chat name once the player is online. */
    public void applyNameOnJoin(org.bukkit.entity.Player player) {
        java.util.UUID id = player.getUniqueId();
        plugin.db().callbackFor(player, plugin.db().submit(() -> dao.find(id)), o -> {
            if (o == null || o.displayName() == null || !player.isOnline()) return;
            player.playerListName(net.kyori.adventure.text.Component.text(o.displayName()));
            player.displayName(net.kyori.adventure.text.Component.text(o.displayName()));
        });
    }
}
