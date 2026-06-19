package de.derfakegamer.sentinel.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Holds one pending chat-input callback per player, used by GUIs to collect durations/reasons. */
public final class ChatInputManager {
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public void await(UUID player, Consumer<String> callback) { pending.put(player, callback); }

    public boolean has(UUID player) { return pending.containsKey(player); }

    public void cancel(UUID player) { pending.remove(player); }

    /** Removes and returns the pending callback, or null if none. */
    public Consumer<String> consume(UUID player) { return pending.remove(player); }
}
