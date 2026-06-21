package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AppealCooldownTest {
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

    /** Drains the DB executor + scheduler so async→main hops finish before assertions. */
    private void drain() throws Exception {
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
    }

    @Test
    void nonStaffSecondAppealWithinCooldownRejected() throws Exception {
        plugin.getConfig().set("appeals.cooldown-seconds", 60);
        PlayerMock p = server.addPlayer("Muted"); // no op / no sentinel.use

        // First /appeal — cooldown records the timestamp (then the async active-mute check runs)
        p.performCommand("appeal please unmute me");
        drain();
        while (p.nextMessage() != null) { /* drain message queue */ }

        // Second /appeal within the window — must be rejected by the cooldown
        p.performCommand("appeal again please");
        drain();

        String msg = p.nextMessage();
        assertNotNull(msg, "second /appeal within cooldown must produce a message");
        assertTrue(msg.toLowerCase().contains("wait"),
            "second /appeal within cooldown must be rate-limited, got: " + msg);
    }

    @Test
    void staffBypassAppealCooldown() throws Exception {
        plugin.getConfig().set("appeals.cooldown-seconds", 60);
        PlayerMock staff = server.addPlayer("StaffMember");
        staff.setOp(true); // satisfies staffPerms().canUse(p, "sentinel.use")

        staff.performCommand("appeal first");
        drain();
        while (staff.nextMessage() != null) { /* drain */ }

        staff.performCommand("appeal second");
        drain();

        String msg = staff.nextMessage();
        if (msg != null) {
            assertFalse(msg.toLowerCase().contains("wait") && msg.toLowerCase().contains("again"),
                "staff must not be rate-limited on /appeal, got: " + msg);
        }
    }

    @Test
    void zeroCooldownDisablesAppealRateLimit() throws Exception {
        plugin.getConfig().set("appeals.cooldown-seconds", 0);
        PlayerMock p = server.addPlayer("PlayerB");

        p.performCommand("appeal one");
        drain();
        while (p.nextMessage() != null) { /* drain */ }

        p.performCommand("appeal two");
        drain();

        String msg = p.nextMessage();
        if (msg != null) {
            assertFalse(msg.toLowerCase().contains("wait") && msg.toLowerCase().contains("again"),
                "appeals.cooldown-seconds=0 must not rate-limit, got: " + msg);
        }
    }
}
