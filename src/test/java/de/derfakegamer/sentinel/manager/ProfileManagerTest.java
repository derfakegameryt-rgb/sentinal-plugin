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
        assertFalse(ProfileManager.isValidName("toolongname123456")); // 17 visible chars
        assertFalse(ProfileManager.isValidName("dash-name"));
        assertFalse(ProfileManager.isValidName("<red>has space"));    // visible text still has a space
        assertFalse(ProfileManager.isValidName("<red>"));             // no visible text
        assertFalse(ProfileManager.isValidName("King!"));             // symbol in visible text
    }

    @Test
    void colourTagsAreAllowed() {
        assertTrue(ProfileManager.isValidName("<red>King"));
        assertTrue(ProfileManager.isValidName("<gradient:#ff0000:#0000ff>Hero</gradient>"));
        assertTrue(ProfileManager.isValidName("col<red>or")); // visible "color" is valid
        assertEquals("King", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(ProfileManager.renderName("<red>King")));
    }

    @Test
    void interactiveTagsRejected() {
        // Only cosmetic tags are allowed — click/hover/insertion render as literal text, so the visible
        // text contains symbols and fails validation (a name can never become an interactive component).
        assertFalse(ProfileManager.isValidName("<click:run_command:'/op me'>King"));
        assertFalse(ProfileManager.isValidName("<hover:show_text:'hi'>King"));
        assertFalse(ProfileManager.isValidName("<insertion:text>King"));
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

        // The floating TextDisplay can't be asserted under MockBukkit (no TextDisplayMock); the
        // no-nametag team membership is the testable proxy for "custom name active / vanilla hidden".
        private org.bukkit.scoreboard.Team nickTeam() {
            return server.getScoreboardManager().getMainScoreboard().getTeam("sentinel_nick");
        }

        @Test void setNameUpdatesTabChatAndHidesTheVanillaNametag() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            assertEquals("Renamed", plain(p.playerListName()), "tab name updated");
            assertEquals("Renamed", plain(p.displayName()), "chat name updated");
            org.bukkit.scoreboard.Team team = nickTeam();
            assertNotNull(team, "the no-nametag team must exist");
            assertTrue(team.hasEntry("RealName"), "player joins the no-nametag team so the vanilla name is hidden");
            assertEquals(org.bukkit.scoreboard.Team.OptionStatus.NEVER,
                team.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY),
                "the team must suppress the vanilla above-head name");
        }

        @Test void resetRemovesTheFloatingNametag() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            assertTrue(nickTeam().hasEntry("RealName"));

            plugin.profile().reset(p, "Admin");
            flush();
            assertFalse(nickTeam() != null && nickTeam().hasEntry("RealName"),
                "reset restores the vanilla nametag (player leaves the no-nametag team)");
            assertNotEquals("Renamed", plain(p.playerListName()), "tab override cleared");
        }

        @Test void vanishHidesTheFloatingNametag() throws Exception {
            PlayerMock p = server.addPlayer("RealName");
            p.setOp(true);
            plugin.profile().setName(p, "Renamed", "Admin");
            flush();
            assertTrue(nickTeam().hasEntry("RealName"), "nametag shown before vanish");

            plugin.vanish().toggle(p); // a vanished player must have no floating name betraying them
            flush();
            assertFalse(nickTeam().hasEntry("RealName"), "vanish hides the floating nametag");
        }
    }
}
