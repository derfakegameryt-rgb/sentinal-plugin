package de.derfakegamer.sentinel.manager;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.derfakegamer.sentinel.Sentinel;
import java.net.InetAddress;
import java.util.UUID;
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

    @Test
    void appliesStoredDisplayNameToLoginProfile() throws Exception {
        UUID id = UUID.randomUUID();
        // store an override directly through the manager's DAO path
        plugin.db().execute(() -> new de.derfakegamer.sentinel.storage.ProfileOverrideDao(plugin.db().database())
            .upsert(new de.derfakegamer.sentinel.model.ProfileOverride(id, "Renamed", null, null, "Admin", 1L)));
        Thread.sleep(200); // let the async write land

        PlayerProfile profile = server.createProfile(id, "RealName");
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(
            "RealName", InetAddress.getByName("127.0.0.1"), id, true, profile);

        plugin.profile().applyOnLogin(event);

        assertEquals("Renamed", event.getPlayerProfile().getName());
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
