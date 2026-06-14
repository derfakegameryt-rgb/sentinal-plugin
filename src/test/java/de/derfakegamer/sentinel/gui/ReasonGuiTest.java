package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class ReasonGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void presetHasFivePresetButtons() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        ReasonGui gui = new ReasonGui(plugin, target, null, PunishmentType.BAN, 0);
        // five presets in slots 10..14
        for (int slot = 10; slot <= 14; slot++)
            assertNotNull(gui.getInventory().getItem(slot), "preset at slot " + slot);
    }

    @Test void clickingPresetOpensConfirm() {
        PlayerMock mod = server.addPlayer("Mod");
        OfflinePlayer target = server.addPlayer("Griefer");
        ReasonGui gui = new ReasonGui(plugin, target, null, PunishmentType.BAN, 0);
        gui.open(mod);

        InventoryClickEvent event = ConfirmGuiTest.clickSlot(mod, gui, 10); // first preset
        gui.onClick(event);

        assertTrue(event.isCancelled());
        // the moderator is now looking at a ConfirmGui
        assertInstanceOf(ConfirmGui.class, mod.getOpenInventory().getTopInventory().getHolder());
    }
}
