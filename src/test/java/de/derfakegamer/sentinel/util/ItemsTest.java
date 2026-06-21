package de.derfakegamer.sentinel.util;

import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class ItemsTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void fillerIsBlackGlass() {
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, Items.filler().getType());
    }
    @Test void accentIsLightBlueGlass() {
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Items.accent().getType());
    }
}
