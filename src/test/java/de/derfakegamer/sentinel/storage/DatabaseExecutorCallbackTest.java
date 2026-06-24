package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.CompletableFuture;

class DatabaseExecutorCallbackTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void callbackForRunsBodyForAnEntity() {
        PlayerMock p = server.addPlayer();
        boolean[] ran = {false};
        plugin.db().callbackFor(p, CompletableFuture.completedFuture("x"), v -> ran[0] = "x".equals(v));
        server.getScheduler().performTicks(1);
        assertTrue(ran[0], "callbackFor must deliver the value on the entity's scheduler");
    }

    @Test void callbackRunsGloballyWithoutAViewer() {
        boolean[] ran = {false};
        plugin.db().callback(CompletableFuture.completedFuture(42), v -> ran[0] = (v == 42));
        server.getScheduler().performTicks(1);
        assertTrue(ran[0], "callback must deliver the value globally");
    }
}
