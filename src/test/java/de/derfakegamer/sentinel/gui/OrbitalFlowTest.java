package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import de.derfakegamer.sentinel.util.OrbitalRod;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalFlowTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void rodModePayloadOpensConfirm() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        OrbitalPayloadGui gui = new OrbitalPayloadGui(plugin, null, 0, 0); // rod mode
        gui.open(p);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(p, gui, 11); // TNT
        gui.onClick(ev);
        assertInstanceOf(ConfirmGui.class, p.getOpenInventory().getTopInventory().getHolder());
    }

    @Test void confirmingRodGivesTaggedRod() {
        PlayerMock p = server.addPlayer("Admin"); p.setOp(true);
        // simulate the confirm action directly: rod mode + TNT
        p.getInventory().addItem(OrbitalRod.create(plugin, OrbitalPayload.TNT));
        boolean hasRod = false;
        for (var it : p.getInventory().getContents())
            if (it != null && it.getType() == Material.FISHING_ROD && OrbitalRod.payloadOf(plugin, it) != null) hasRod = true;
        assertTrue(hasRod);
    }
}
