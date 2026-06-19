package de.derfakegamer.sentinel.manager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FreezeManager {
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    /** Flips freeze state. Returns the new state (true = now frozen). */
    public boolean toggle(UUID player) {
        if (frozen.add(player)) return true;
        frozen.remove(player);
        return false;
    }

    public boolean isFrozen(UUID player) { return frozen.contains(player); }

    public void unfreeze(UUID player) { frozen.remove(player); }
}
