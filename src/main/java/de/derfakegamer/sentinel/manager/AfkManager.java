package de.derfakegamer.sentinel.manager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last time each player did something and whether they are currently AFK.
 * Detection only — players are flagged AFK after an idle threshold; nobody is kicked.
 */
public final class AfkManager {
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    /** Records activity. Clears any AFK flag; returns {@code true} if the player WAS AFK (i.e. just came back). */
    public boolean bump(UUID uuid) {
        lastActivity.put(uuid, System.currentTimeMillis());
        return afk.remove(uuid);
    }

    public void forget(UUID uuid) { lastActivity.remove(uuid); afk.remove(uuid); }

    /** Flags the player AFK; returns {@code true} only if they weren't already flagged. */
    public boolean markAfk(UUID uuid) { return afk.add(uuid); }

    public boolean isAfk(UUID uuid) { return afk.contains(uuid); }

    /** Milliseconds since the player's last recorded activity at {@code now}, or 0 if never seen. */
    public long idleMs(UUID uuid, long now) {
        Long t = lastActivity.get(uuid);
        return t == null ? 0 : now - t;
    }
}
