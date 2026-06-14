package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class InvseeGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void mapsHotbarArmorAndDecorations() {
        PlayerMock target = server.addPlayer("Target");
        target.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        target.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));

        InvseeGui gui = new InvseeGui(plugin, target);

        // hotbar slot 0 maps to gui slot 27
        assertEquals(Material.DIAMOND, gui.getInventory().getItem(27).getType());
        // helmet maps to gui slot 46
        assertEquals(Material.IRON_HELMET, gui.getInventory().getItem(46).getType());
        // armor label
        assertEquals(Material.ARMOR_STAND, gui.getInventory().getItem(45).getType());
        // separator filler
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, gui.getInventory().getItem(36).getType());
    }

    @Test void writesBackEditsOnClose() {
        PlayerMock mod = server.addPlayer("Mod");
        PlayerMock target = server.addPlayer("Target");
        target.getInventory().setItem(0, new ItemStack(Material.DIAMOND));

        InvseeGui gui = new InvseeGui(plugin, target);
        // edit gui hotbar slot (27) -> should land back in target hotbar slot 0
        gui.getInventory().setItem(27, new ItemStack(Material.GOLD_INGOT));

        gui.onClose(new InventoryCloseEvent(mod.openInventory(gui.getInventory())));

        assertEquals(Material.GOLD_INGOT, target.getInventory().getItem(0).getType());
    }
}
