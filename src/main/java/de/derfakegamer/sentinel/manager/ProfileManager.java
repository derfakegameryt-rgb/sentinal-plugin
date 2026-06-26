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

    // ---- skin fetch retry (Mojang completion is a flaky network call) ----

    /** One profile-completion attempt; returns true if the profile completed with usable textures. */
    @FunctionalInterface
    interface ProfileCompleter { boolean complete(); }

    /** Pluggable sleep so tests run without real delays. */
    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    // One entry per attempt; the value is the backoff slept BEFORE that attempt (so attempt 1 is immediate).
    static final long[] SKIN_FETCH_BACKOFF_MS = {0L, 250L, 750L};

    /**
     * Completes a profile with up to {@link #SKIN_FETCH_BACKOFF_MS}.length attempts, sleeping the backoff
     * before each retry. Async-only (it blocks the calling thread on the sleep). A throwing or false attempt
     * is retried; returns true on the first success, false if every attempt fails or the thread is interrupted.
     */
    static boolean completeWithRetry(ProfileCompleter completer, Sleeper sleeper) {
        for (long backoff : SKIN_FETCH_BACKOFF_MS) {
            if (backoff > 0) {
                try { sleeper.sleep(backoff); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            try { if (completer.complete()) return true; }
            catch (Throwable ignored) { /* transient: fall through to the next attempt */ }
        }
        return false;
    }

    // Cosmetic tags ONLY (colour/gradient/rainbow/decorations/reset). Deliberately excludes <click>,
    // <hover>, <insertion> etc. so a staff-set name can never become an interactive component for every
    // player. Both validation and rendering use this same instance, so what passes is exactly what shows.
    private static final net.kyori.adventure.text.minimessage.MiniMessage NAME_MM =
        net.kyori.adventure.text.minimessage.MiniMessage.builder()
            .tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow(),
                net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset()))
            .build();

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

    /**
     * Valid iff the VISIBLE text (after stripping any MiniMessage colour/format tags) is 1–16 of
     * [A-Za-z0-9_]. Colour tags are allowed — e.g. {@code <red>King}, {@code <gradient:#f00:#00f>Hero}
     * — so names stay recognisable while supporting colour. The raw input is length-bounded and must
     * parse as MiniMessage.
     */
    public static boolean isValidName(String s) {
        if (s == null || s.isEmpty() || s.length() > 96) return false;
        try {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(NAME_MM.deserialize(s));
            return NAME.matcher(plain).matches();
        } catch (Throwable t) {
            return false; // malformed MiniMessage
        }
    }

    /** Renders an override name (cosmetic MiniMessage only); falls back to plain text on a parse error. */
    public static net.kyori.adventure.text.Component renderName(String name) {
        try {
            return NAME_MM.deserialize(name);
        } catch (Throwable t) {
            return net.kyori.adventure.text.Component.text(name);
        }
    }

    /** The Mojang "textures" property of a (completed) profile, or null if absent. */
    static ProfileProperty texturesOf(PlayerProfile profile) {
        for (ProfileProperty p : profile.getProperties()) {
            if ("textures".equals(p.getName())) return p;
        }
        return null;
    }

    /**
     * Applies a name/skin override to the (online) player. Main thread only.
     * The name is display-only here — tab + chat. The above-head name CANNOT be changed on the live
     * profile (it is bound to the GameProfile name set at login), so it is rendered separately by
     * {@link NametagManager} (a floating TextDisplay). The skin swaps the texture property and needs a
     * hide/show resend for other clients to re-render.
     */
    private void applyLive(org.bukkit.entity.Player target, String name, String skinValue, String skinSig) {
        if (skinValue != null) {
            PlayerProfile profile = target.getPlayerProfile();
            profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
            profile.setProperty(new ProfileProperty("textures", skinValue, skinSig));
            target.setPlayerProfile(profile);
        }
        if (name != null) {
            target.playerListName(renderName(name));
            target.displayName(renderName(name));
        }
        if (skinValue != null) resend(target); // re-render the skin for other players
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
            plugin.nametags().refresh(target); // float the new name above the head (overhead can't change live)
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
        // Immediate, network-FREE revert: clear the tab/chat overlay and drop the floating nametag (which
        // restores the vanilla above-head name). This must not depend on the Mojang skin fetch below.
        plugin.scheduler().runForEntity(target, () -> {
            target.playerListName(null);
            target.displayName(null);
            joinNames.remove(id);
            plugin.nametags().refresh(target); // override gone -> removes the floating name, vanilla returns
            plugin.audit().record(staff, "RESETPROFILE", realName, "");
        });
        // Best-effort: fetch and restore the real skin (network). The name is already correct if this
        // fails or returns nothing, so a failure just leaves the default skin until the next relog.
        // Guarded so a failed/uncompletable fetch never throws uncaught into the scheduler thread.
        plugin.scheduler().runAsync(() -> {
            try {
                PlayerProfile real = org.bukkit.Bukkit.createProfile(id, realName);
                if (!real.complete(true)) return;
                ProfileProperty tex = texturesOf(real);
                if (tex == null) return;
                plugin.scheduler().runGlobal(() -> {
                    org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                    if (t == null) return;
                    plugin.scheduler().runForEntity(t, () -> {
                        PlayerProfile profile = org.bukkit.Bukkit.createProfile(id, realName);
                        profile.setProperty(tex);
                        t.setPlayerProfile(profile);
                        resend(t);
                    });
                });
            } catch (Exception e) {
                plugin.getLogger().fine("skin restore on profile reset failed (name already reverted): " + e.getMessage());
            }
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

    /**
     * Re-applies a stored display-name override once the player is online (above the head + tab + chat).
     * Runs mid-session on join — NOT at login — because the login profile name is deliberately left as
     * the real account name (see {@link #applyOnLogin}); the rename is applied here via a resend instead.
     */
    public void applyNameOnJoin(org.bukkit.entity.Player player) {
        java.util.UUID id = player.getUniqueId();
        plugin.db().callbackFor(player, plugin.db().submit(() -> dao.find(id)), o -> {
            if (o == null || o.displayName() == null || !player.isOnline()) return;
            applyLive(player, o.displayName(), o.skinValue(), o.skinSignature());
            plugin.nametags().refresh(player); // restore the floating above-head name after a relog
        });
    }
}
