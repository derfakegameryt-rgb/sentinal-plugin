package de.derfakegamer.sentinel.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory per-(player, action) cooldowns. Server-local anti-spam; never throws. */
public final class CooldownManager {
    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();
    private static String k(UUID id, String key) { return id + ":" + key; }

    /** Records {@code now} and allows when off cooldown (or {@code cooldownMillis <= 0}); else leaves state and returns false. */
    public boolean tryUse(UUID id, String key, long cooldownMillis, long now) {
        if (cooldownMillis <= 0) return true;
        String kk = k(id, key);
        Long prev = last.get(kk);
        if (prev != null && now - prev < cooldownMillis) return false;
        last.put(kk, now);
        return true;
    }

    public long remainingMillis(UUID id, String key, long cooldownMillis, long now) {
        if (cooldownMillis <= 0) return 0;
        Long prev = last.get(k(id, key));
        if (prev == null) return 0;
        return Math.max(0, cooldownMillis - (now - prev));
    }
}
