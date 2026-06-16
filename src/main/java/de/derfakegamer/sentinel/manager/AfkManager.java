package de.derfakegamer.sentinel.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the last time each player did something, for AFK auto-kick. */
public final class AfkManager {
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    public void bump(UUID uuid) { lastActivity.put(uuid, System.currentTimeMillis()); }
    public void forget(UUID uuid) { lastActivity.remove(uuid); }

    /** Milliseconds since the player's last recorded activity at {@code now}, or 0 if never seen. */
    public long idleMs(UUID uuid, long now) {
        Long t = lastActivity.get(uuid);
        return t == null ? 0 : now - t;
    }
}
