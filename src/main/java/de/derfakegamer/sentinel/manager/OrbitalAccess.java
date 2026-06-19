package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.OrbitalAllowDao;
import de.derfakegamer.sentinel.storage.SettingsDao;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrbitalAccess {
    private static final String CODE_KEY = "orbital.code";
    private static final String DEFAULT_CODE = "2584";

    private final Sentinel plugin;
    private final SettingsDao settings;
    private final OrbitalAllowDao allow;

    // In-memory cache — populated once at construction (startup, main thread).
    // Reads are served from cache; writes update the cache and persist async.
    private final Map<UUID, String> allowed = new ConcurrentHashMap<>();
    private volatile String code;

    public OrbitalAccess(Sentinel plugin, SettingsDao settings, OrbitalAllowDao allow) {
        this.plugin = plugin;
        this.settings = settings;
        this.allow = allow;
        // One-time synchronous load at onEnable — acceptable at startup.
        this.code = settings.get(CODE_KEY, DEFAULT_CODE);
        this.allowed.putAll(allow.all());
    }

    public String code() { return code; }

    public void setCode(String c) {
        this.code = c;
        plugin.db().execute(() -> settings.set(CODE_KEY, c));
    }

    public boolean isAllowed(Player player) {
        return plugin.owner().isOwner(player) || allowed.containsKey(player.getUniqueId());
    }

    public boolean isAllowed(UUID uuid) { return allowed.containsKey(uuid); }

    public void add(UUID uuid, String name) {
        allowed.put(uuid, name);
        plugin.db().execute(() -> allow.add(uuid, name));
    }

    public void remove(UUID uuid) {
        allowed.remove(uuid);
        plugin.db().execute(() -> allow.remove(uuid));
    }

    public Map<UUID, String> list() { return new HashMap<>(allowed); }
}
