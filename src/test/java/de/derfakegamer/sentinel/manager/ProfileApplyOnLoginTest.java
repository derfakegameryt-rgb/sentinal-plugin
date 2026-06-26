package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.derfakegamer.sentinel.Sentinel;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class ProfileApplyOnLoginTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
    }

    @AfterEach
    void teardown() { MockBukkit.unmock(); }

    private void store(de.derfakegamer.sentinel.model.ProfileOverride o) throws Exception {
        plugin.db().execute(() -> new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database()).upsert(o));
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS); // barrier: let the write land
    }

    @Test
    void nameOverrideDoesNotChangeLoginProfileName() throws Exception {
        UUID id = UUID.randomUUID();
        // a name-only override must NOT rename the login profile (that would pollute the account
        // name and trigger vanilla's "(formerly known as …)" on rejoin)
        store(new de.derfakegamer.sentinel.model.ProfileOverride(id, "Renamed", null, null, "Admin", 1L));

        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        plugin.profile().applyOnLogin(event);

        assertEquals("RealName", event.getPlayerProfile().getName(),
            "login profile name must stay the real account name");
    }

    @Test
    void skinOverrideDoesNotMutateTheLoginProfile() throws Exception {
        UUID id = UUID.randomUUID();
        store(new de.derfakegamer.sentinel.model.ProfileOverride(id, null, "SKINVALUE", "SKINSIG", "Admin", 1L));

        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        plugin.profile().applyOnLogin(event);

        // The skin must NOT be injected into the login profile: replacing the textures property of the
        // signed login profile breaks the player's own secure-profile handshake ("took too long to log
        // in"). The skin is applied AFTER join instead (see ProfileManager#applyOverrideOnJoin).
        assertNull(ProfileManager.texturesOf(event.getPlayerProfile()),
            "a skin override must not touch the login profile");
        assertEquals("RealName", event.getPlayerProfile().getName(),
            "applying a skin must not change the account name");
    }

    @Test
    void noOverrideLeavesProfileUntouched() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        plugin.profile().applyOnLogin(event);

        assertEquals("RealName", event.getPlayerProfile().getName());
    }
}
