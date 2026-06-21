package de.derfakegamer.sentinel.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Thread-safe buffer that accumulates items and flushes them in batches.
 *
 * <p>Items are flushed when:
 * <ul>
 *   <li>the buffer reaches {@code maxBuffer} items (size threshold), or</li>
 *   <li>{@link #flush()} is called explicitly (scheduled interval, before reads, on disable).</li>
 * </ul>
 *
 * <p>The provided {@code flusher} receives a snapshot list and is expected to perform one
 * batched DB write. If the flusher throws, the error is logged and the snapshot is dropped
 * (not re-queued) — subsequent {@link #add} calls continue normally.
 *
 * <p>A hard cap ({@link #HARD_CAP}) prevents unbounded growth: items added beyond the cap
 * are dropped with a warning log rather than queued.
 *
 * @param <T> the type of item being buffered
 */
public final class BatchWriter<T> {

    /** Items beyond this count are dropped with a warning rather than buffered. */
    public static final int HARD_CAP = 10_000;

    private final int maxBuffer;
    private final Consumer<List<T>> flusher;
    private final Logger logger;

    // Guarded by 'this'
    private final List<T> buffer = new ArrayList<>();

    /**
     * @param maxBuffer size threshold at which {@link #add} auto-flushes
     * @param flusher   called with the snapshot list on each flush; must be thread-safe
     *                  with respect to the DB layer (typically runs on the DB executor)
     * @param logger    used to log flusher errors and hard-cap warnings
     */
    public BatchWriter(int maxBuffer, Consumer<List<T>> flusher, Logger logger) {
        if (maxBuffer <= 0) throw new IllegalArgumentException("maxBuffer must be > 0");
        this.maxBuffer = maxBuffer;
        this.flusher = flusher;
        this.logger = logger;
    }

    /**
     * Adds an item to the buffer. If the buffer reaches {@code maxBuffer} after the add,
     * {@link #flush()} is called immediately (inline, on the calling thread).
     * Items are dropped (with a warning) if the buffer is already at {@link #HARD_CAP}.
     */
    public synchronized void add(T item) {
        if (buffer.size() >= HARD_CAP) {
            logger.warning("[BatchWriter] Hard cap reached (" + HARD_CAP + "); dropping item: " + item);
            return;
        }
        buffer.add(item);
        if (buffer.size() >= maxBuffer) {
            flush();
        }
    }

    /** Returns the number of items currently buffered (snapshot; may change immediately after). */
    public synchronized int pendingCount() { return buffer.size(); }

    /**
     * Snapshots the current buffer, clears it, then calls the flusher with the snapshot.
     * Any flusher exception is caught, logged, and the snapshot is discarded — the buffer
     * has already been cleared so no items are lost going forward.
     * Does nothing if the buffer is empty.
     */
    public synchronized void flush() {
        if (buffer.isEmpty()) return;
        List<T> snapshot = new ArrayList<>(buffer);
        buffer.clear();
        try {
            flusher.accept(snapshot);
        } catch (Exception e) {
            logger.severe("[BatchWriter] Flusher threw an exception; " + snapshot.size()
                    + " item(s) dropped: " + e.getMessage());
        }
    }
}
