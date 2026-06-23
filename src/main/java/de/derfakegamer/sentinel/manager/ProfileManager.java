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
}
