package de.derfakegamer.sentinel.gui;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class ServerInfoGuiTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void buildsAndUpdatesWithoutError() {
        ServerInfoGui gui = new ServerInfoGui(plugin);
        assertNotNull(gui.getInventory().getItem(12)); // memory item present
        gui.update();                                  // manual refresh does not throw
        assertNotNull(gui.getInventory().getItem(12));
    }
}
