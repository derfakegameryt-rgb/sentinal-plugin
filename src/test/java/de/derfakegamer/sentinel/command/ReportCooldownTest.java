package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReportCooldownTest {
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

    /**
     * Drains the DB executor and the Bukkit scheduler so that all async→main
     * hops complete before assertions. Mirrors the pattern used in PunishmentCommandsTest.
     */
    private void drain() throws Exception {
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
    }

    @Test
    void nonStaffSecondReportWithinCooldownRejected() throws Exception {
        // Set a long cooldown so the second call is definitely within the window
        plugin.getConfig().set("report.cooldown-seconds", 30);

        server.addPlayer("Victim");
        PlayerMock reporter = server.addPlayer("Reporter"); // no op / no sentinel.use

        // First /report — should succeed (tryUse records the timestamp)
        reporter.performCommand("report Victim being mean");
        drain();
        // Clear any messages from the first report
        while (reporter.nextMessage() != null) { /* drain message queue */ }

        // Count open reports after first report
        int reportsBefore = plugin.reports().open().get(2, TimeUnit.SECONDS).size();

        // Second /report within the cooldown window — must be rejected
        reporter.performCommand("report Victim again");
        drain();

        String msg = reporter.nextMessage();
        assertNotNull(msg, "reporter should receive a cooldown message on their second attempt");
        assertTrue(
            msg.toLowerCase().contains("wait") || msg.toLowerCase().contains("cooldown"),
            "cooldown message should mention 'wait' or 'cooldown', got: " + msg
        );

        // No additional report should have been filed
        int reportsAfter = plugin.reports().open().get(2, TimeUnit.SECONDS).size();
        assertEquals(reportsBefore, reportsAfter,
            "second /report within cooldown must not file a new report");
    }

    @Test
    void staffBypassCooldown() throws Exception {
        // Set a long cooldown
        plugin.getConfig().set("report.cooldown-seconds", 30);

        server.addPlayer("Target");
        PlayerMock staffPlayer = server.addPlayer("StaffMember");
        staffPlayer.setOp(true); // ops satisfy staffPerms().canUse(p, "sentinel.use")

        // First report — succeeds
        staffPlayer.performCommand("report Target first offence");
        drain();

        // Second report immediately after — must NOT be rate-limited for staff
        while (staffPlayer.nextMessage() != null) { /* drain message queue */ }

        staffPlayer.performCommand("report Target second offence");
        drain();

        // Staff should get the success message, not a cooldown message
        String msg = staffPlayer.nextMessage();
        // If there's a message it must not be the cooldown message
        if (msg != null) {
            assertFalse(
                msg.toLowerCase().contains("wait") && msg.toLowerCase().contains("again"),
                "staff should not be rate-limited, got: " + msg
            );
        }

        // Two reports should exist (or at least more than zero after both)
        int total = plugin.reports().open().get(2, TimeUnit.SECONDS).size();
        assertTrue(total >= 2, "staff should be able to file multiple reports without cooldown, found: " + total);
    }

    @Test
    void zeroCooldownDisablesRateLimit() throws Exception {
        // 0 = disabled
        plugin.getConfig().set("report.cooldown-seconds", 0);

        server.addPlayer("AnotherVictim");
        PlayerMock reporter = server.addPlayer("ReporterB");

        reporter.performCommand("report AnotherVictim reason one");
        drain();
        while (reporter.nextMessage() != null) { /* drain */ }

        reporter.performCommand("report AnotherVictim reason two");
        drain();

        String msg = reporter.nextMessage();
        // Should not get a cooldown message; may get report-filed or similar
        if (msg != null) {
            assertFalse(
                msg.toLowerCase().contains("wait") && msg.toLowerCase().contains("again"),
                "cooldown-seconds=0 must not rate-limit the reporter, got: " + msg
            );
        }
    }
}
