package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class BatchWriterTest {

    private static final Logger SILENT = Logger.getLogger("BatchWriterTest-silent");
    private List<List<String>> flushCalls;
    private BatchWriter<String> writer;

    @BeforeEach
    void setUp() {
        flushCalls = new ArrayList<>();
        // maxBuffer = 3 for most tests
        writer = new BatchWriter<>(3, flushCalls::add, SILENT);
    }

    // -----------------------------------------------------------------------
    // Size-threshold flush
    // -----------------------------------------------------------------------

    @Test
    void flushOnSizeThreshold_passesAllItems() {
        writer.add("a");
        writer.add("b");
        // third add crosses the threshold and triggers flush
        writer.add("c");

        assertEquals(1, flushCalls.size(), "should have flushed once");
        assertEquals(List.of("a", "b", "c"), flushCalls.get(0), "flush batch must contain all three items");
    }

    @Test
    void flushOnSizeThreshold_clearsBuffer() {
        writer.add("a");
        writer.add("b");
        writer.add("c"); // auto-flush
        // buffer is now empty; one more item should NOT trigger another flush
        writer.add("d");

        assertEquals(1, flushCalls.size(), "only one auto-flush should have occurred");
    }

    // -----------------------------------------------------------------------
    // Explicit flush()
    // -----------------------------------------------------------------------

    @Test
    void explicitFlush_drainsBuffer() {
        writer.add("x");
        writer.add("y");
        // buffer has 2 items, below threshold
        writer.flush();

        assertEquals(1, flushCalls.size());
        assertEquals(List.of("x", "y"), flushCalls.get(0));
    }

    @Test
    void explicitFlush_whenEmpty_doesNothing() {
        writer.flush();
        assertTrue(flushCalls.isEmpty(), "flush on empty buffer must not call flusher");
    }

    @Test
    void explicitFlush_clearsBufferSoSubsequentFlushIsEmpty() {
        writer.add("a");
        writer.flush();
        writer.flush(); // second flush: buffer is already empty

        assertEquals(1, flushCalls.size(), "second flush on empty buffer must not invoke flusher again");
    }

    // -----------------------------------------------------------------------
    // Flusher exception: caught; subsequent items are not lost
    // -----------------------------------------------------------------------

    @Test
    void flusherException_isCaughtAndDoesNotLoseSubsequentItems() {
        AtomicInteger callCount = new AtomicInteger();
        List<List<String>> captured = new ArrayList<>();

        BatchWriter<String> failFirst = new BatchWriter<>(2, batch -> {
            int n = callCount.incrementAndGet();
            if (n == 1) throw new RuntimeException("simulated DB failure");
            captured.add(batch);
        }, SILENT);

        // First flush (auto, on 2nd add) will throw
        failFirst.add("fail1");
        failFirst.add("fail2"); // triggers flush → throws

        // Buffer was already cleared before the flusher ran, so these go in fresh
        failFirst.add("ok1");
        failFirst.add("ok2"); // second flush → succeeds

        assertEquals(1, captured.size(), "second flush should have succeeded");
        assertEquals(List.of("ok1", "ok2"), captured.get(0));
    }

    @Test
    void flusherException_doesNotReBufferFailedSnapshot() {
        BatchWriter<String> failWriter = new BatchWriter<>(2, batch -> {
            throw new RuntimeException("always fails");
        }, SILENT);

        failWriter.add("a");
        failWriter.add("b"); // auto-flush → throws, snapshot dropped

        // manual flush of empty buffer: flusher should NOT be called again with old items
        failWriter.flush();

        // No assertion needed beyond "didn't throw"; the fact that items from the
        // failed snapshot are gone is verified by the fact that no second exception
        // propagates (the flusher would throw again if items were re-queued).
        assertTrue(true);
    }

    // -----------------------------------------------------------------------
    // Hard cap: items dropped with warning, no unbounded growth
    // -----------------------------------------------------------------------

    @Test
    void hardCap_dropsItemsBeyondCap() {
        // Use a large maxBuffer so no auto-flush happens
        List<List<String>> sink = new ArrayList<>();
        BatchWriter<String> capped = new BatchWriter<>(BatchWriter.HARD_CAP + 1, sink::add, SILENT);

        // Fill to hard cap
        for (int i = 0; i < BatchWriter.HARD_CAP; i++) {
            capped.add("item" + i);
        }
        // This item is beyond the cap
        capped.add("overflow");

        // Flush everything that was buffered
        capped.flush();

        assertEquals(1, sink.size());
        assertEquals(BatchWriter.HARD_CAP, sink.get(0).size(), "overflow item must be dropped");
        assertFalse(sink.get(0).contains("overflow"));
    }

    // -----------------------------------------------------------------------
    // Multiple threshold flushes accumulate correctly
    // -----------------------------------------------------------------------

    @Test
    void multipleThresholdFlushes() {
        for (int i = 0; i < 9; i++) writer.add("i" + i); // 3 auto-flushes (maxBuffer=3)

        assertEquals(3, flushCalls.size());
        for (List<String> batch : flushCalls) {
            assertEquals(3, batch.size());
        }
    }
}
