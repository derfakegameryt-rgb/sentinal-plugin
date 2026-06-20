package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseExecutorTest {
    Database db;
    DatabaseExecutor exec;

    @BeforeEach
    void setUp() throws Exception {
        File f = Files.createTempFile("sentinel-exec", ".db").toFile();
        f.deleteOnExit();
        db = new SqliteDatabase(f);
        // plugin == null is fine for tests that never call callback()
        exec = new DatabaseExecutor(db, Logger.getLogger("test"), null);
    }

    @AfterEach
    void tearDown() { exec.shutdown(); }

    @Test
    void submitRunsWorkAndReturnsValue() throws Exception {
        assertEquals(42, exec.submit(() -> 42).get(2, TimeUnit.SECONDS));
    }

    @Test
    void allWorkRunsOnTheSameSingleThread() throws Exception {
        var names = ConcurrentHashMap.newKeySet();
        var futures = new java.util.ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < 50; i++)
            futures.add(exec.submit(() -> Thread.currentThread().getName()));
        for (var fu : futures) names.add(fu.get(2, TimeUnit.SECONDS));
        assertEquals(1, names.size(), "all DB work must run on one thread");
    }

    @Test
    void submitFailureCompletesExceptionallyAndDoesNotKillThread() {
        CompletableFuture<Object> bad = exec.submit(() -> { throw new java.sql.SQLException("boom"); });
        assertThrows(ExecutionException.class, () -> bad.get(2, TimeUnit.SECONDS));
        // thread still alive: a subsequent submit still works
        assertDoesNotThrow(() -> exec.submit(() -> 1).get(2, TimeUnit.SECONDS));
    }

    @Test
    void executeFailureIsSwallowed() throws Exception {
        exec.execute(() -> { throw new RuntimeException("boom"); });
        // queue still processes later work
        assertEquals(7, exec.submit(() -> 7).get(2, TimeUnit.SECONDS));
    }

    @Test
    void shutdownDrainsQueuedWriteBeforeClosing() throws Exception {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 20; i++) exec.execute(counter::incrementAndGet);
        exec.shutdown();
        assertEquals(20, counter.get());
    }

    @Test void ensureValidIsCalledBeforeWork() throws Exception {
        java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
        Database counting = new Database() {
            public java.sql.Connection connection() { return db.connection(); }
            public SqlDialect dialect() { return SqlDialect.SQLITE; }
            public void ensureValid() { validations.incrementAndGet(); }
            public void close() { }
        };
        DatabaseExecutor ex = new DatabaseExecutor(counting, java.util.logging.Logger.getLogger("t"), null);
        ex.submit(() -> 1).get(2, java.util.concurrent.TimeUnit.SECONDS);
        ex.execute(() -> {});
        ex.submit(() -> 2).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(validations.get() >= 3, "ensureValid must run before tasks");
        ex.shutdown();
    }
}
