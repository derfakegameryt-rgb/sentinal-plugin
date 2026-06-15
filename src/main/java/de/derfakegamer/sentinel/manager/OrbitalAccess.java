package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.OrbitalAllowDao;
import de.derfakegamer.sentinel.storage.SettingsDao;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public final class OrbitalAccess {
    private static final String CODE_KEY = "orbital.code";
    private static final String DEFAULT_CODE = "2584";

    private final Sentinel plugin;
    private final SettingsDao settings;
    private final OrbitalAllowDao allow;

    public OrbitalAccess(Sentinel plugin, SettingsDao settings, OrbitalAllowDao allow) {
        this.plugin = plugin; this.settings = settings; this.allow = allow;
    }

    public String code() { return settings.get(CODE_KEY, DEFAULT_CODE); }
    public void setCode(String code) { settings.set(CODE_KEY, code); }

    public boolean isAllowed(Player player) {
        return plugin.owner().isOwner(player) || allow.contains(player.getUniqueId());
    }

    public boolean isAllowed(UUID uuid) { return allow.contains(uuid); }

    public void add(UUID uuid, String name) { allow.add(uuid, name); }
    public void remove(UUID uuid) { allow.remove(uuid); }
    public Map<UUID, String> list() { return allow.all(); }
}
