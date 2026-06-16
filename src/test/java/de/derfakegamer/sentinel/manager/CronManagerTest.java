package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CronManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private CronManager withTasks(List<Map<String, Object>> entries) {
        plugin.getConfig().set("scheduled-tasks", entries);
        return new CronManager(plugin);
    }

    @Test void configRoundTripsThroughGetMapList() {
        // Guards the load() path: if getMapList didn't return the set entries,
        // taskCount() would be 0 and the other tests would pass trivially.
        CronManager cron = withTasks(List.of(
                Map.of("every", "1s", "do", "say a"),
                Map.of("at", "04:00", "do", "say b")));
        assertEquals(2, cron.taskCount(), "both config entries must be parsed into tasks");
    }

    @Test void intervalFiresThenWaits() {
        CronManager cron = withTasks(List.of(Map.of("every", "1s", "do", "say hi")));
        long t0 = 1_000_000L;
        assertEquals(List.of("say hi"), cron.due(t0, LocalTime.NOON));        // first eval fires
        assertTrue(cron.due(t0 + 500, LocalTime.NOON).isEmpty());            // too soon
        assertEquals(List.of("say hi"), cron.due(t0 + 1500, LocalTime.NOON)); // after interval
    }

    @Test void dailyFiresOncePerMinute() {
        CronManager cron = withTasks(List.of(Map.of("at", "04:00", "do", "restart 60s")));
        long day = 1_000_000_000L;
        assertTrue(cron.due(day, LocalTime.of(3, 59)).isEmpty());
        assertEquals(List.of("restart 60s"), cron.due(day, LocalTime.of(4, 0)));
        assertTrue(cron.due(day, LocalTime.of(4, 0)).isEmpty());  // same minute, no re-fire
    }

    @Test void dailyFiresAgainNextDay() {
        CronManager cron = withTasks(List.of(Map.of("at", "04:00", "do", "restart 60s")));
        long day1 = 0L;
        long day2 = 86_400_000L; // one epoch day later
        assertEquals(List.of("restart 60s"), cron.due(day1, LocalTime.of(4, 0)));
        assertTrue(cron.due(day1, LocalTime.of(4, 0)).isEmpty());      // same day, no re-fire
        assertEquals(List.of("restart 60s"), cron.due(day2, LocalTime.of(4, 0))); // next day fires again
    }

    @Test void invalidEntryIsSkippedNotCrashed() {
        CronManager cron = withTasks(List.of(Map.of("do", "noop")));  // no every/at
        assertEquals(0, cron.taskCount());
        assertTrue(cron.due(1, LocalTime.NOON).isEmpty());
    }

    @Test void invalidDurationIsSkipped() {
        CronManager cron = withTasks(List.of(Map.of("every", "banana", "do", "noop")));
        assertEquals(0, cron.taskCount());
        assertTrue(cron.due(1, LocalTime.NOON).isEmpty());
    }
}
