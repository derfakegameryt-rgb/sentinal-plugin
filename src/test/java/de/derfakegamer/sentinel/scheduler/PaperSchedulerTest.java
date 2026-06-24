package de.derfakegamer.sentinel.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperSchedulerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void runGlobalAndEntityExecuteOnTheServerThread() {
        Scheduler s = new PaperScheduler(plugin);
        boolean[] ran = {false, false};
        s.runGlobal(() -> ran[0] = true);
        s.runForEntity(server.addPlayer(), () -> ran[1] = true);
        server.getScheduler().performTicks(1);
        assertTrue(ran[0], "runGlobal task must execute");
        assertTrue(ran[1], "runForEntity task must execute");
    }

    @Test void cancelAllStopsARepeatingTask() {
        Scheduler s = new PaperScheduler(plugin);
        int[] count = {0};
        TaskHandle h = s.globalTimer(() -> count[0]++, 1L, 1L);
        server.getScheduler().performTicks(3);
        int afterRun = count[0];
        assertTrue(afterRun >= 1, "timer must have fired at least once");
        h.cancel();
        server.getScheduler().performTicks(5);
        assertEquals(afterRun, count[0], "cancelled timer must not fire again");
    }

    @Test void schedulerAccessorReturnsPaperOffFolia() {
        assertInstanceOf(PaperScheduler.class, plugin.scheduler(),
            "off Folia, plugin.scheduler() must be the Paper impl");
    }
}
