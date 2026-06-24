package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class VanishManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void toggleTracksVanishState() {
        PlayerMock staff = server.addPlayer("Mod");
        assertTrue(plugin.vanish().toggle(staff));   // now vanished
        assertTrue(plugin.vanish().isVanished(staff.getUniqueId()));
        assertFalse(plugin.vanish().toggle(staff));  // now visible
        assertFalse(plugin.vanish().isVanished(staff.getUniqueId()));
    }

    @Test void vanishedHiddenFromNonOp() {
        PlayerMock staff = server.addPlayer("Mod");
        PlayerMock normal = server.addPlayer("Player");
        plugin.vanish().toggle(staff); // vanish — schedules hidePlayer via runForEntity
        // Pump the scheduler so the runForEntity hide task actually executes.
        server.getScheduler().performTicks(1);
        assertFalse(normal.canSee(staff), "non-op should not see a vanished staff member");
    }

    @Test void vanishedStaffStaysHiddenAfterRelog() {
        PlayerMock staff = server.addPlayer("Mod");
        PlayerMock normal = server.addPlayer("Player");
        plugin.vanish().toggle(staff); // vanish; schedules hidePlayer via runForEntity
        server.getScheduler().performTicks(1); // drain the hide task
        // Simulate a relog: the server clears visibility state, so normal can momentarily see staff again.
        normal.showPlayer(plugin, staff);
        assertTrue(normal.canSee(staff));
        // The join handler must re-hide the still-vanished staff member.
        plugin.vanish().applyOnJoin(staff);
        server.getScheduler().performTicks(1); // drain the re-hide task
        assertFalse(normal.canSee(staff), "a vanished staff member must remain hidden after relogging");
    }
}
