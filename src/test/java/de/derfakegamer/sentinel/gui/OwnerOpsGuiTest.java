package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.AuditEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnerOpsGuiTest {
    ServerMock server; Sentinel plugin; PlayerMock owner;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private String lore(OwnerOpsGui gui, int slot) {
        List<Component> lore = gui.getInventory().getItem(slot).lore();
        return lore.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c))
            .reduce("", (a, b) -> a + " " + b);
    }

    @Test void externalOpIsFlagged() {
        PlayerMock m = server.addPlayer("Mallory");
        m.setOp(true);
        OwnerOpsGui gui = new OwnerOpsGui(plugin, List.of((OfflinePlayer) m), List.of());
        assertTrue(lore(gui, 0).contains("External"), "an op with no panel grant must be flagged external");
    }

    @Test void panelGrantIsAttributed() {
        PlayerMock m = server.addPlayer("Mallory");
        m.setOp(true);
        AuditEntry e = new AuditEntry(1, "Admin", "OP", "Mallory", "", System.currentTimeMillis());
        OwnerOpsGui gui = new OwnerOpsGui(plugin, List.of((OfflinePlayer) m), List.of(e));
        assertTrue(lore(gui, 0).contains("Opped by Admin"), "a panel grant must be attributed to its actor");
    }

    @Test void emptyShowsPlaceholder() {
        OwnerOpsGui gui = new OwnerOpsGui(plugin, List.of(), List.of());
        assertNotNull(gui.getInventory().getItem(22));
    }

    @Test void backReturnsToPanelAndNonOwnerBounced() {
        OwnerOpsGui gui = new OwnerOpsGui(plugin, List.of(), List.of());
        gui.open(owner);
        click(owner, gui, 48); // BACK
        assertInstanceOf(OwnerPanelGui.class, owner.getOpenInventory().getTopInventory().getHolder());

        PlayerMock eve = server.addPlayer("Eve");
        OwnerOpsGui g2 = new OwnerOpsGui(plugin, List.of(), List.of());
        g2.open(eve);
        click(eve, g2, 48);
        assertNull(eve.getOpenInventory().getTopInventory(), "a non-owner must be bounced out");
    }

    private void click(PlayerMock p, Gui gui, int slot) {
        InventoryView view = p.openInventory(gui.getInventory());
        gui.onClick(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
            ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }
}
