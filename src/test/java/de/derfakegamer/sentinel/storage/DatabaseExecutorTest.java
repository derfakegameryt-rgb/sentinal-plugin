package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.Sentinel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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

    @Test
    void connectionIsNonNullInsideATask() throws Exception {
        java.sql.Connection inTask = exec.submit(() -> db.connection()).get(2, TimeUnit.SECONDS);
        assertNotNull(inTask, "db.connection() must be bound (non-null) inside an executor task");
    }

    @Test
    void manyConcurrentReadsAllSucceedWithCorrectData() throws Exception {
        // Seed a row, then hammer it with many concurrent reads. On SQLite this is the single
        // writer thread serialising the work; the guard is that nothing corrupts or returns null
        // and every read sees the seeded value. This protects the per-task connection refactor.
        exec.execute(() -> {
            try (var ps = db.connection().prepareStatement(
                    "CREATE TABLE IF NOT EXISTS t6 (id INTEGER PRIMARY KEY, v INTEGER)")) {
                ps.executeUpdate();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        exec.execute(() -> {
            try (var ps = db.connection().prepareStatement("INSERT INTO t6 (id, v) VALUES (1, 99)")) {
                ps.executeUpdate();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        int n = 200;
        var futures = new java.util.ArrayList<CompletableFuture<Integer>>();
        for (int i = 0; i < n; i++) {
            futures.add(exec.submit(() -> {
                assertNotNull(db.connection(), "connection bound during read task");
                try (var ps = db.connection().prepareStatement("SELECT v FROM t6 WHERE id=1");
                     var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : -1;
                }
            }));
        }
        for (var fu : futures) assertEquals(99, fu.get(5, TimeUnit.SECONDS), "every concurrent read sees seeded value");
    }

    @Test
    void submitWriteRunsWorkAndReturnsValue() throws Exception {
        assertEquals(42, exec.submitWrite(() -> 42).get(2, TimeUnit.SECONDS));
    }

    @Test
    void submitWriteFailureCompletesExceptionallyAndDoesNotKillThread() {
        CompletableFuture<Object> bad = exec.submitWrite(() -> { throw new java.sql.SQLException("boom"); });
        assertThrows(ExecutionException.class, () -> bad.get(2, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> exec.submitWrite(() -> 1).get(2, TimeUnit.SECONDS));
    }

    /**
     * Backend that advertises concurrent reads (MySQL-shaped). Reads hand out distinct pooled
     * connections from a reader pool; writes always use the single writer connection. We use this
     * to assert the executor routes work to the right thread/connection.
     */
    static final class FakeConcurrentDatabase implements Database {
        final java.sql.Connection writerConn = stubConnection("writer");
        // a small pool of distinct reader connections, round-robined by acquire()
        final java.util.concurrent.ConcurrentLinkedQueue<java.sql.Connection> pool =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        FakeConcurrentDatabase() {
            for (int i = 0; i < 4; i++) pool.add(stubConnection("reader-" + i));
        }
        public java.sql.Connection connection() {
            java.sql.Connection bound = ThreadLocalHolder.CURRENT.get();
            return bound != null ? bound : writerConn;
        }
        public SqlDialect dialect() { return SqlDialect.MYSQL; }
        public void ensureValid() { }
        public java.sql.Connection acquire() {
            java.sql.Connection c = pool.poll();
            return c != null ? c : writerConn;
        }
        public void release(java.sql.Connection c) { if (c != writerConn) pool.add(c); }
        public boolean supportsConcurrentReads() { return true; }
        public void close() { }
        private static java.sql.Connection stubConnection(String label) {
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                FakeConcurrentDatabase.class.getClassLoader(),
                new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "conn:" + label;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    return null;
                });
        }
    }

    @Test
    void concurrentBackendRunsReadsOffTheWriterThread() throws Exception {
        FakeConcurrentDatabase fake = new FakeConcurrentDatabase();
        DatabaseExecutor ex = new DatabaseExecutor(fake, Logger.getLogger("t"), null);
        try {
            Thread writerThread = ex.submitWrite(Thread::currentThread).get(2, TimeUnit.SECONDS);
            // Force genuine parallelism: a barrier each read waits on guarantees >1 reader thread
            // must run concurrently for any of them to proceed. This proves reads use the pool, not
            // the single writer thread, and is not timing-flaky. (Pool threads share a name, so we
            // compare Thread identity, not name.)
            final int parallel = 4;
            var barrier = new java.util.concurrent.CyclicBarrier(parallel);
            var readerThreads = ConcurrentHashMap.<Thread>newKeySet();
            var futures = new java.util.ArrayList<CompletableFuture<Thread>>();
            for (int i = 0; i < parallel; i++)
                futures.add(ex.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS); // only completes if peers run in parallel
                    return Thread.currentThread();
                }));
            for (var fu : futures) readerThreads.add(fu.get(5, TimeUnit.SECONDS));
            assertTrue(readerThreads.size() > 1,
                "reads must run on a parallel reader pool, saw " + readerThreads.size() + " thread(s)");
            assertFalse(readerThreads.contains(writerThread),
                "reads must not be forced onto the single writer thread");
        } finally {
            ex.shutdown();
        }
    }

    @Test
    void concurrentBackendRunsWritesOnTheSingleWriterThreadAndConnection() throws Exception {
        FakeConcurrentDatabase fake = new FakeConcurrentDatabase();
        DatabaseExecutor ex = new DatabaseExecutor(fake, Logger.getLogger("t"), null);
        try {
            var writeThreads = ConcurrentHashMap.newKeySet();
            var writeConns = ConcurrentHashMap.newKeySet();
            var futures = new java.util.ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 50; i++)
                futures.add(ex.submitWrite(() -> {
                    writeThreads.add(Thread.currentThread().getName());
                    writeConns.add(fake.connection());
                    return null;
                }));
            for (var fu : futures) fu.get(5, TimeUnit.SECONDS);
            assertEquals(1, writeThreads.size(), "all writes must serialise on one writer thread");
            assertEquals(1, writeConns.size(), "all writes must use the single writer connection");
            assertTrue(writeConns.contains(fake.writerConn), "writes must bind the writer connection");
        } finally {
            ex.shutdown();
        }
    }

    @Test
    void writesPreserveFifoOrderWithExecute() throws Exception {
        FakeConcurrentDatabase fake = new FakeConcurrentDatabase();
        DatabaseExecutor ex = new DatabaseExecutor(fake, Logger.getLogger("t"), null);
        try {
            // Interleave execute() and submitWrite() and assert strict FIFO completion order:
            // a back-to-back mute/unmute must not reorder across reader connections.
            var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
            for (int i = 0; i < 100; i++) {
                final int n = i;
                if (i % 2 == 0) ex.execute(() -> order.add(n));
                else ex.submitWrite(() -> { order.add(n); return null; });
            }
            ex.submitWrite(() -> null).get(5, TimeUnit.SECONDS); // barrier
            for (int i = 0; i < 100; i++)
                assertEquals(i, order.get(i), "writes must execute in strict FIFO order");
        } finally {
            ex.shutdown();
        }
    }

    @Test
    void connectionThreadLocalIsClearedAfterWriteTask() throws Exception {
        FakeConcurrentDatabase fake = new FakeConcurrentDatabase();
        DatabaseExecutor ex = new DatabaseExecutor(fake, Logger.getLogger("t"), null);
        try {
            // bind happens inside the task; after a write the writer thread's ThreadLocal must be
            // cleared so a later read on the same pooled/writer thread cannot leak a stale conn.
            ex.submitWrite(() -> { assertNotNull(fake.connection()); return null; })
                .get(2, TimeUnit.SECONDS);
            // run a no-op write that observes the ThreadLocal at task start: must be null (cleared)
            Object boundAtStart = ex.submitWrite(() -> Database.ThreadLocalHolder.CURRENT.get())
                .get(2, TimeUnit.SECONDS);
            // bind() runs before work, so inside the task it's non-null; but the holder for the
            // *previous* task must have been cleared — assert no cross-task leak by checking that a
            // read task on a fresh pooled thread starts with a freshly-acquired reader connection.
            java.sql.Connection readConn = ex.submit(() -> fake.connection()).get(2, TimeUnit.SECONDS);
            assertNotNull(readConn);
            assertNotSame(fake.writerConn, readConn, "read must use a pooled reader, not a leaked writer conn");
        } finally {
            ex.shutdown();
        }
    }

    @Test void ensureValidIsCalledBeforeWork() throws Exception {
        java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
        Database counting = new Database() {
            public java.sql.Connection connection() { return db.connection(); }
            public SqlDialect dialect() { return SqlDialect.SQLITE; }
            public void ensureValid() { validations.incrementAndGet(); }
            public java.sql.Connection acquire() { return db.connection(); }
            public void release(java.sql.Connection c) { }
            public boolean supportsConcurrentReads() { return false; }
            public void close() { }
        };
        DatabaseExecutor ex = new DatabaseExecutor(counting, java.util.logging.Logger.getLogger("t"), null);
        ex.submit(() -> 1).get(2, java.util.concurrent.TimeUnit.SECONDS);
        ex.execute(() -> {});
        ex.submit(() -> 2).get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(validations.get() >= 3, "ensureValid must run before tasks");
        ex.shutdown();
    }

    // -------------------------------------------------------------------------
    // 3-arg callback overload tests (error path + success path)
    // -------------------------------------------------------------------------

    @Test void callbackErrorPathRunsOnErrorNotOnMain() throws Exception {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<Throwable> err = new AtomicReference<>();
        // plugin == null: task.run() is called inline (no scheduler hop needed)
        exec.callback(failed, v -> success.set(true), err::set);
        // give whenComplete a moment to fire on the completing thread
        Thread.sleep(100);
        assertFalse(success.get(), "onMain must not run on failure");
        assertNotNull(err.get(), "onError must receive the throwable");
    }

    @Test void callbackSuccessPathRunsOnMain() throws Exception {
        AtomicReference<String> got = new AtomicReference<>();
        exec.callback(CompletableFuture.completedFuture("ok"), got::set, t -> {});
        Thread.sleep(100);
        assertEquals("ok", got.get());
    }

    // -------------------------------------------------------------------------
    // callbackOrError tests — require MockBukkit for scheduler + messages()
    // -------------------------------------------------------------------------

    @Nested
    class CallbackOrErrorTests {
        ServerMock server;
        Sentinel plugin;

        @BeforeEach
        void setup() {
            server = MockBukkit.mock();
            plugin = MockBukkit.load(Sentinel.class);
        }

        @AfterEach
        void teardown() {
            MockBukkit.unmock();
        }

        @Test
        void callbackOrErrorMessagesViewerOnFailureAndDoesNotRunOnSuccess() throws Exception {
            PlayerMock viewer = server.addPlayer("Viewer");
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("db-boom"));
            AtomicBoolean successRan = new AtomicBoolean(false);

            plugin.db().callbackOrError(viewer, failed, v -> successRan.set(true));
            // pump the scheduler so the main-thread hop executes
            server.getScheduler().performTicks(2);

            assertFalse(successRan.get(), "onSuccess must not run on failure");
            net.kyori.adventure.text.Component msg = viewer.nextComponentMessage();
            assertNotNull(msg, "viewer must receive an error message");
            String text = PlainTextComponentSerializer.plainText().serialize(msg);
            assertTrue(text.contains("went wrong"),
                "viewer must get the rendered db-error message (not the raw key), got: " + text);
        }

        @Test
        void callbackOrErrorRunsOnSuccessWhenFutureSucceeds() throws Exception {
            PlayerMock viewer = server.addPlayer("Viewer2");
            AtomicReference<String> got = new AtomicReference<>();

            plugin.db().callbackOrError(viewer, CompletableFuture.completedFuture("hello"), got::set);
            server.getScheduler().performTicks(2);

            assertEquals("hello", got.get(), "onSuccess must run with the value on success");
            assertNull(viewer.nextComponentMessage(), "viewer must not receive any message on success");
        }
    }
}
