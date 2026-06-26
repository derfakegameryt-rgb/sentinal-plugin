package de.derfakegamer.sentinel.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.ProfileOverrideDao;

import java.util.regex.Pattern;

/**
 * Applies and persists staff-set display-name / skin overrides via the Paper PlayerProfile API.
 * No NMS: live changes resend the player with hide/show. The login profile is never mutated (that
 * breaks the secure-profile handshake / pollutes the account name); persisted overrides are
 * re-applied after the player is online by {@link #applyOverrideOnJoin}.
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

    // ---- texture cache (avoid re-hitting Mojang for repeated sets / survive a momentary outage) ----

    record CachedTexture(String value, String signature, long fetchedAt) {}

    static final long SKIN_CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes
    // Above this many entries, a put first purges everything stale — bounds the map without a background
    // task. The keyspace is skin-source names staff ever copy, so this cap is generous headroom.
    static final int SKIN_CACHE_MAX = 256;

    private final java.util.concurrent.ConcurrentHashMap<String, CachedTexture> skinCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    static boolean isFresh(long fetchedAt, long now) { return now - fetchedAt < SKIN_CACHE_TTL_MS; }

    /** The cached textures for {@code key} if still fresh, else null. A stale entry is evicted on access. */
    CachedTexture cacheGet(String key, long now) {
        CachedTexture c = skinCache.get(key);
        if (c == null) return null;
        if (isFresh(c.fetchedAt(), now)) return c;
        skinCache.remove(key, c); // evict-on-read so stale entries don't linger
        return null;
    }

    void cachePut(String key, String value, String signature, long now) {
        // Bound growth: when the map gets large, drop every stale entry before inserting.
        if (skinCache.size() >= SKIN_CACHE_MAX) {
            skinCache.entrySet().removeIf(e -> !isFresh(e.getValue().fetchedAt(), now));
        }
        skinCache.put(key, new CachedTexture(value, signature, now));
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

    // Skin overrides {value, signature} cached at pre-login so the skin can be applied straight away on
    // join — without a second DB round-trip — which minimises the brief real-skin pop-in. Keyed by UUID.
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, String[]> loginSkins =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The cached display-name override for {@code id}, or null if none (read on the main thread). */
    public String overrideJoinName(java.util.UUID id) { return joinNames.get(id); }

    /** Drops the cached override (name + skin) for {@code id} (called when the player quits). */
    public void evictJoinName(java.util.UUID id) { joinNames.remove(id); loginSkins.remove(id); }

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
            String key = sourceName.toLowerCase(java.util.Locale.ROOT);
            long now = System.currentTimeMillis();
            String value;
            String signature;
            CachedTexture cached = cacheGet(key, now);
            if (cached != null) {
                value = cached.value();
                signature = cached.signature();
            } else {
                PlayerProfile src = org.bukkit.Bukkit.createProfile(sourceName);
                boolean ok = completeWithRetry(() -> src.complete(true), Thread::sleep);
                ProfileProperty tex = ok ? texturesOf(src) : null;
                if (tex == null) { plugin.scheduler().runGlobal(() -> done.accept(false)); return; }
                value = tex.getValue();
                signature = tex.getSignature();
                cachePut(key, value, signature, now);
            }
            final String fv = value;
            final String fs = signature;
            plugin.scheduler().runGlobal(() -> {
                org.bukkit.entity.Player t = org.bukkit.Bukkit.getPlayer(id);
                if (t == null || !t.isOnline()) { done.accept(false); return; }
                long ts = System.currentTimeMillis();
                // find + upsert on the single writer thread so the existing name override is preserved atomically.
                plugin.db().callbackFor(t, plugin.db().submitWrite(() -> {
                    de.derfakegamer.sentinel.model.ProfileOverride existing = dao.find(id);
                    String nm = existing != null ? existing.displayName() : null;
                    dao.upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, nm, fv, fs, staff, ts));
                    return nm;
                }), nm -> {
                    if (!t.isOnline()) { done.accept(false); return; }
                    applyLive(t, nm, fv, fs);
                    loginSkins.put(id, new String[]{fv, fs}); // keep the join-read cache in sync with a mid-session skin set
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
            loginSkins.remove(id); // drop the cached skin too, so a stale entry can't be re-applied
            plugin.nametags().refresh(target); // override gone -> removes the floating name, vanilla returns
            plugin.audit().record(staff, "RESETPROFILE", realName, "");
        });
        // Best-effort: fetch and restore the real skin (network). The name is already correct if this
        // fails or returns nothing, so a failure just leaves the default skin until the next relog.
        // Guarded so a failed/uncompletable fetch never throws uncaught into the scheduler thread.
        plugin.scheduler().runAsync(() -> {
            try {
                PlayerProfile real = org.bukkit.Bukkit.createProfile(id, realName);
                if (!completeWithRetry(() -> real.complete(true), Thread::sleep)) return;
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
     * Re-applies the cached display-name override to the live tab + chat name (reconciliation). Uses the
     * cached name (no DB hit) and runs on the entity thread. No-op when there is no override or the player
     * is offline. The above-head floating name is handled separately by {@link NametagManager#refresh}.
     */
    public void reassertNameDisplay(org.bukkit.entity.Player player) {
        String name = joinNames.get(player.getUniqueId());
        if (name == null) return;
        net.kyori.adventure.text.Component rendered = renderName(name);
        plugin.scheduler().runForEntity(player, () -> {
            if (!player.isOnline()) return;
            player.playerListName(rendered);
            player.displayName(rendered);
        });
    }

    /**
     * Pre-login hook: caches the stored display-name override so the join/quit broadcast can use it
     * synchronously. It deliberately does NOT touch the login profile.
     *
     * <p>Neither the name nor the SKIN is injected into the login profile here. The name is left alone
     * because renaming the login profile makes the server cache it and triggers vanilla's
     * "(formerly known as …)" message. The skin is left alone because replacing the {@code textures}
     * property of the (signed) login profile breaks the joining player's own secure-profile handshake —
     * the symptom is "took too long to log in" on every login that has a skin override. Both are applied
     * AFTER the player is online via {@link #applyOverrideOnJoin} (at the cost of a brief skin pop-in).
     */
    // NOTE: LoginListener now calls lookupOverride() + cacheLogin() directly (so the lookup runs alongside
    // the ban checks). This convenience wrapper — lookup-then-cache in one blocking call — is retained as a
    // stable seam for the pre-login tests; keep it in step with that pair.
    public void applyOnLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        java.util.UUID id = event.getUniqueId();
        try {
            cacheLogin(id, lookupOverride(id).join());
        } catch (Exception e) {
            // never block a login on a profile lookup; leave the caches untouched (join falls back to a read)
        }
    }

    /**
     * The stored override lookup as a future, so the login handler can run it alongside the ban checks.
     * A pure read (no write), so it goes through {@code submit} → the reader pool, letting it run
     * concurrently with the writer-bound ban checks on a backend with concurrent reads (MySQL).
     */
    public java.util.concurrent.CompletableFuture<de.derfakegamer.sentinel.model.ProfileOverride> lookupOverride(
            java.util.UUID id) {
        return plugin.db().submit(() -> dao.find(id));
    }

    /**
     * Caches a pre-login override lookup so the join handler can apply it without a second DB round-trip:
     * the display name (for the join/quit broadcast) and the skin {value, signature} (for an immediate
     * skin apply on join). A null/empty field clears its cache entry.
     */
    public void cacheLogin(java.util.UUID id, de.derfakegamer.sentinel.model.ProfileOverride o) {
        if (o != null && o.displayName() != null) joinNames.put(id, o.displayName());
        else joinNames.remove(id);
        if (o != null && o.skinValue() != null) loginSkins.put(id, new String[]{o.skinValue(), o.skinSignature()});
        else loginSkins.remove(id);
    }

    /**
     * Re-applies a stored override (name AND skin) once the player is online. Runs on join — NOT at
     * login — because mutating the login profile is unsafe: a name change pollutes the account name
     * ("(formerly known as …)") and a skin change breaks the secure-profile handshake ("took too long
     * to log in"). The skin is applied here via {@link #applyLive} (texture swap + resend).
     *
     * <p>Fast path: the override was cached at pre-login ({@link #cacheLogin}), so it is applied straight
     * away with no DB round-trip — which keeps the real-skin pop-in to a minimum. Only when the cache has
     * nothing (e.g. the pre-login lookup failed) does it fall back to an authoritative DB read.
     */
    public void applyOverrideOnJoin(org.bukkit.entity.Player player) {
        java.util.UUID id = player.getUniqueId();
        String name = joinNames.get(id);
        String[] skin = loginSkins.get(id);
        if (name != null || skin != null) {
            applyLive(player, name, skin != null ? skin[0] : null, skin != null ? skin[1] : null);
            if (name != null) plugin.nametags().refresh(player); // restore floating name after relog
            return;
        }
        // Cache miss (pre-login lookup did not complete) — read authoritatively as a fallback.
        plugin.db().callbackFor(player, plugin.db().submit(() -> dao.find(id)), o -> {
            if (o == null || !player.isOnline()) return;
            if (o.displayName() == null && o.skinValue() == null) return;
            applyLive(player, o.displayName(), o.skinValue(), o.skinSignature());
            if (o.displayName() != null) plugin.nametags().refresh(player);
        });
    }
}
