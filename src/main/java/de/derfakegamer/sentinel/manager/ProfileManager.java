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

    /** Builds the override profile and resends the (online) player. Main thread only. */
    private void applyLive(org.bukkit.entity.Player target, String name, String skinValue, String skinSig) {
        PlayerProfile profile = target.getPlayerProfile();
        if (name != null) profile.setName(name);
        if (skinValue != null) {
            profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
            profile.setProperty(new ProfileProperty("textures", skinValue, skinSig));
        }
        target.setPlayerProfile(profile);
        if (name != null) {
            target.playerListName(net.kyori.adventure.text.Component.text(name));
            target.displayName(net.kyori.adventure.text.Component.text(name));
        }
        resend(target);
    }

    /** Force other clients to re-track the target so the new name/skin renders. Main thread only. */
    private void resend(org.bukkit.entity.Player target) {
        for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!o.equals(target)) o.hidePlayer(plugin, target);
        }
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.entity.Player o : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (!o.equals(target)) o.showPlayer(plugin, target);
            }
        }, 2L);
    }

    public void setName(org.bukkit.entity.Player target, String name, String staff) {
        java.util.UUID id = target.getUniqueId();
        plugin.db().callback(plugin.db().submit(() -> dao.find(id)), existing -> {
            if (!target.isOnline()) return;
            String sv = existing != null ? existing.skinValue() : null;
            String ss = existing != null ? existing.skinSignature() : null;
            applyLive(target, name, sv, ss);
            long now = System.currentTimeMillis();
            plugin.db().execute(() -> dao.upsert(
                new de.derfakegamer.sentinel.model.ProfileOverride(id, name, sv, ss, staff, now)));
            plugin.audit().record(staff, "SETNAME", target.getName(), name);
        });
    }

    public void setSkin(org.bukkit.entity.Player target, String sourceName, String staff,
                        java.util.function.Consumer<Boolean> done) {
        java.util.UUID id = target.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile src = org.bukkit.Bukkit.createProfile(sourceName);
            boolean ok = src.complete(true);
            ProfileProperty tex = ok ? texturesOf(src) : null;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null || !t.isOnline() || tex == null) { done.accept(false); return; }
                plugin.db().callback(plugin.db().submit(() -> dao.find(id)), existing -> {
                    String nm = existing != null ? existing.displayName() : null;
                    applyLive(t, nm, tex.getValue(), tex.getSignature());
                    long now = System.currentTimeMillis();
                    plugin.db().execute(() -> dao.upsert(
                        new de.derfakegamer.sentinel.model.ProfileOverride(
                            id, nm, tex.getValue(), tex.getSignature(), staff, now)));
                    plugin.audit().record(staff, "SETSKIN", t.getName(), sourceName);
                    done.accept(true);
                });
            });
        });
    }

    public void reset(org.bukkit.entity.Player target, String staff) {
        java.util.UUID id = target.getUniqueId();
        String realName = target.getName();
        plugin.db().execute(() -> dao.delete(id));
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile real = org.bukkit.Bukkit.createProfile(id, realName);
            real.complete(true);
            ProfileProperty tex = texturesOf(real);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null) return;
                PlayerProfile profile = t.getPlayerProfile();
                profile.setName(realName);
                profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
                if (tex != null) profile.setProperty(tex);
                t.setPlayerProfile(profile);
                t.playerListName(null);
                t.displayName(null);
                resend(t);
                plugin.audit().record(staff, "RESETPROFILE", realName, "");
            });
        });
    }

    /** Writes a stored override into the login profile (async pre-login thread; blocking read OK). */
    public void applyOnLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        de.derfakegamer.sentinel.model.ProfileOverride o;
        try {
            o = plugin.db().submit(() -> dao.find(event.getUniqueId())).join();
        } catch (Exception e) {
            return; // never block a login on a profile lookup
        }
        if (o == null) return;
        PlayerProfile profile = event.getPlayerProfile();
        if (o.displayName() != null) profile.setName(o.displayName());
        if (o.skinValue() != null) {
            profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
            profile.setProperty(new ProfileProperty("textures", o.skinValue(), o.skinSignature()));
        }
        event.setPlayerProfile(profile);
    }
}
