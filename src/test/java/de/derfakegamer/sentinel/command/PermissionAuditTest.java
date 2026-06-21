package de.derfakegamer.sentinel.command;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.gui.PlayerActionsGui;
import org.bukkit.command.Command;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Permission audit regression tests.
 *
 * Gaps found and fixed:
 *   1. PlayerActionsGui OPTOGGLE (slot 26) — setOp with no sentinel.use check.
 *   2. WhitelistGui NAV_ACT_L1/L2 and item-click — whitelist mutations with no sentinel.use check.
 *   3. ReportsGui shift-click "mark handled" — no sentinel.use check.
 *
 * All command-layer onCommand handlers were already gated; the command tests below serve as
 * control assertions confirming that gating remains in place.
 */
class PermissionAuditTest {
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

    // -----------------------------------------------------------------------
    // Control: command layer already gated
    // -----------------------------------------------------------------------

    /** Non-op running /clearchat must receive no-permission and be rejected. */
    @Test
    void nonOpCannotClearChat() {
        PlayerMock p = server.addPlayer("Nobody");
        // not op, no permissions
        Command cmd = server.getCommandMap().getCommand("clearchat");
        assertNotNull(cmd, "clearchat must be registered");

        new ClearChatCommand(plugin).onCommand(p, cmd, "clearchat", new String[0]);

        String msg = p.nextMessage();
        assertNotNull(msg, "non-op must receive a rejection message");
        // The no-permission message reads "You must be a server operator to do this."
        assertTrue(msg.toLowerCase().contains("permission") || msg.toLowerCase().contains("operator"),
            "rejection message must indicate missing permission; got: " + msg);
    }

    /** Op running /clearchat must succeed (no rejection message). */
    @Test
    void opCanClearChat() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        Command cmd = server.getCommandMap().getCommand("clearchat");
        assertNotNull(cmd, "clearchat must be registered");

        boolean handled = new ClearChatCommand(plugin).onCommand(op, cmd, "clearchat", new String[0]);
        assertTrue(handled, "clearchat must return true for an op");
    }

    // -----------------------------------------------------------------------
    // Gap fix 1: PlayerActionsGui OPTOGGLE must re-check sentinel.use
    // -----------------------------------------------------------------------

    /**
     * A non-op clicking OPTOGGLE in PlayerActionsGui must be rejected with no-permission
     * and must NOT change the target's op status.
     *
     * Slot 26 = OPTOGGLE (see PlayerActionsGui constant OPTOGGLE = 26).
     */
    @Test
    void nonOpCannotToggleOpViaGui() {
        PlayerMock viewer = server.addPlayer("Rogue");
        // viewer is not op and has no sentinel.use permission

        PlayerMock target = server.addPlayer("Target");
        assertFalse(target.isOp(), "target must start as non-op");

        // Construct the GUI with the target pre-fetched as not banned/muted
        PlayerActionsGui gui = new PlayerActionsGui(plugin, target,
            false, false, false, 0, null);

        // Simulate clicking slot 26 (OPTOGGLE)
        InventoryClickEvent event = new InventoryClickEvent(
            viewer.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            26,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL
        );

        // Open the inventory so getOpenInventory() is valid
        gui.open(viewer);
        gui.onClick(event);

        // The target must NOT have been opped
        assertFalse(target.isOp(), "non-op must not be able to op a target via the GUI");

        // The viewer must have received a rejection message
        String msg = viewer.nextMessage();
        assertNotNull(msg, "non-op must receive a rejection message from OPTOGGLE");
        assertTrue(msg.toLowerCase().contains("permission") || msg.toLowerCase().contains("operator"),
            "rejection message must indicate missing permission; got: " + msg);
    }

    /**
     * An op clicking OPTOGGLE must be allowed and the target's op status must toggle.
     */
    @Test
    void opCanToggleOpViaGui() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);

        PlayerMock target = server.addPlayer("Target2");
        assertFalse(target.isOp(), "target must start as non-op");

        PlayerActionsGui gui = new PlayerActionsGui(plugin, target,
            false, false, false, 0, null);

        InventoryClickEvent event = new InventoryClickEvent(
            op.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            26,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL
        );

        gui.open(op);
        gui.onClick(event);

        // Op should have toggled the target's op status (makeOp = true because target was not op)
        assertTrue(target.isOp(), "op must be able to op a target via the GUI");
    }
}
