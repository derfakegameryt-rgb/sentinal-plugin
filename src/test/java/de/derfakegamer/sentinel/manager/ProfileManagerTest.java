package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ProfileManagerTest {

    @Test
    void validNamesAccepted() {
        assertTrue(ProfileManager.isValidName("Notch"));
        assertTrue(ProfileManager.isValidName("a_B9"));
        assertTrue(ProfileManager.isValidName("ABCDEFGHIJKLMNOP")); // 16 chars
    }

    @Test
    void invalidNamesRejected() {
        assertFalse(ProfileManager.isValidName(null));
        assertFalse(ProfileManager.isValidName(""));
        assertFalse(ProfileManager.isValidName("has space"));
        assertFalse(ProfileManager.isValidName("toolongname123456")); // 17 chars
        assertFalse(ProfileManager.isValidName("col<red>or"));
        assertFalse(ProfileManager.isValidName("dash-name"));
    }

    // ---- live apply (mid-session setName / reset) ----

    @Nested
    class LiveApply {
        ServerMock server;
        Sentinel plugin;

        @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
        @AfterEach void teardown() { MockBukkit.unmock(); }

        // reset() cascades async (Mojang skin fetch) -> global -> entity, so settle it over a few rounds.
        private void flush() throws Exception {
            for (int i = 0; i < 4; i++) {
                plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS); // barrier: writes landed
                server.getScheduler().waitAsyncTasksFinished();
                server.getScheduler().performTicks(5);                   // run the scheduled callbacks
            }
        }

        private String plain(net.kyori.adventure.text.Component c) {
            return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c);
        }

        @Test void setNameChangesTheAboveHeadProfileName() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            assertEquals("Renamed", p.getPlayerProfile().getName(),
                "setName must change the profile (above-head) name, not just tab/chat");
            assertEquals("Renamed", plain(p.playerListName()), "tab name also updated");
            assertEquals("Renamed", plain(p.displayName()), "chat name also updated");
        }

        @Test void resetRestoresTheRealAboveHeadName() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            assertEquals("Renamed", p.getPlayerProfile().getName());

            plugin.profile().reset(p, "Admin");
            flush();
            assertEquals("RealName", p.getPlayerProfile().getName(),
                "reset must put the real account name back above the head");
        }
    }
}
