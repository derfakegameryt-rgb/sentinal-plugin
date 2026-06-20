package de.derfakegamer.sentinel.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** A small thread-safe time-expiring cache. Null values are treated as misses (not cached). */
public final class TtlCache<K, V> {
    private record Entry<V>(V value, long expiresAt) {}
    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;

    public TtlCache(long ttlMillis) { this(ttlMillis, System::currentTimeMillis); }
    public TtlCache(long ttlMillis, LongSupplier clock) { this.ttlMillis = ttlMillis; this.clock = clock; }

    public V get(K key, Function<K, V> loader) {
        long now = clock.getAsLong();
        Entry<V> e = map.get(key);
        if (e != null && e.expiresAt() > now) return e.value();
        V v = loader.apply(key);
        if (v != null) map.put(key, new Entry<>(v, now + ttlMillis));
        else map.remove(key);
        return v;
    }
    public void put(K key, V value) {
        if (value == null) { map.remove(key); return; }
        map.put(key, new Entry<>(value, clock.getAsLong() + ttlMillis));
    }
    public void invalidate(K key) { map.remove(key); }
    public void clear() { map.clear(); }
}
