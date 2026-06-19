package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TemplatesGuiTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void clickingTemplateAppliesPunishment() throws Exception {
        plugin.getConfig().set("templates", java.util.List.of("ban template ban reason"));
        PlayerMock mod = server.addPlayer("Mod"); mod.setOp(true);
        PlayerMock target = server.addPlayer("BadGuy");
        TemplatesGui gui = new TemplatesGui(plugin, target);
        gui.open(mod);
        InventoryClickEvent ev = ConfirmGuiTest.clickSlot(mod, gui, 0);
        gui.onClick(ev);
        assertNotNull(plugin.punishments().activeBan(target.getUniqueId(), System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }
}
