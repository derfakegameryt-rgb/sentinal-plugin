package de.derfakegamer.sentinel.gui;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerActionsGuardTest {
    ServerMock server;
    Sentinel plugin;
    PlayerMock owner;
    PlayerMock staff;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        staff = server.addPlayer("Staff");
        staff.setOp(true);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void protectedOwnerCannotBeOpenedByOthers() {
        plugin.ownerProtection().setEnabled(true);
        PlayerActionsGui.open(plugin, owner, staff);
        server.getScheduler().performTicks(3);
        // The guard sends the block message immediately and returns — staff must receive it.
        assertNotNull(staff.nextComponentMessage(), "staff must get the not-found message");
        // And staff must NOT have a PlayerActionsGui open (no async DB work was kicked off).
        var view = staff.getOpenInventory();
        var top = (view != null) ? view.getTopInventory() : null;
        boolean guiOpen = top != null && top.getHolder() instanceof PlayerActionsGui;
        assertFalse(guiOpen, "staff must not have a PlayerActionsGui open for the owner");
    }

    @Test void protectionOffOpensNormally() {
        plugin.ownerProtection().setEnabled(false);
        PlayerActionsGui.open(plugin, owner, staff);
        server.getScheduler().performTicks(5);
        // No block message at the head of the queue — open() proceeded without the guard firing.
        assertNull(staff.nextComponentMessage());
    }
}
