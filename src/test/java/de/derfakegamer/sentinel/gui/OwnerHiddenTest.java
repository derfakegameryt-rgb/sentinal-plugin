package de.derfakegamer.sentinel.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Disabled;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

// NOTE: MockBukkit limitation — SkullMetaMock.getOwningPlayer() constructs a new OfflinePlayerMock(name)
// using only the player name, ignoring the stored UUID. Therefore sm.getOwningPlayer().getUniqueId()
// never equals the original player's UUID, making UUID-based skull-meta assertions impossible in MockBukkit.
// This test is disabled per the task brief's guidance ("note it honestly; rely on build + predicate unit tests").
// The owner-filter predicate (plugin.owner().isOwner(UUID)) is independently unit-tested in OwnerManagerTest.

class OwnerHiddenTest {
    ServerMock server;
    Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test @Disabled("MockBukkit SkullMetaMock.getOwningPlayer() constructs OfflinePlayerMock(name) " +
        "ignoring UUID; UUID-keyed skull assertions are never driveable in this version of MockBukkit. " +
        "Owner filter is verified by build + OwnerManager.isOwner() predicate unit tests.")
    void operatorsListExcludesTheOwner() {
        PlayerMock owner = new PlayerMock(server, "OwnerGuy", plugin.owner().uuid());
        server.addPlayer(owner);
        owner.setOp(true);
        PlayerMock staff = server.addPlayer("NormalStaff");
        staff.setOp(true);

        OperatorsGui gui = new OperatorsGui(plugin, 0);

        // The owner's head must not be among the rendered operator heads; a normal op still is.
        boolean ownerShown = false, staffShown = false;
        for (org.bukkit.inventory.ItemStack it : gui.getInventory().getContents()) {
            if (it == null || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm)) continue;
            if (sm.getOwningPlayer() == null) continue;
            if (sm.getOwningPlayer().getUniqueId().equals(plugin.owner().uuid())) ownerShown = true;
            if (sm.getOwningPlayer().getUniqueId().equals(staff.getUniqueId())) staffShown = true;
        }
        assertFalse(ownerShown, "owner must be hidden from the operators list");
        assertTrue(staffShown, "a normal operator is still shown");
    }
}
