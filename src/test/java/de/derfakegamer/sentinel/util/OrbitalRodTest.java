package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.OrbitalPayload;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import static org.junit.jupiter.api.Assertions.*;

class OrbitalRodTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void createsTaggedRodAndReadsItBack() {
        ItemStack rod = OrbitalRod.create(plugin, OrbitalPayload.CHARGED_CREEPER);
        assertEquals(Material.FISHING_ROD, rod.getType());
        assertEquals(OrbitalPayload.CHARGED_CREEPER, OrbitalRod.payloadOf(plugin, rod));
    }

    @Test void plainItemHasNoPayload() {
        assertNull(OrbitalRod.payloadOf(plugin, new ItemStack(Material.FISHING_ROD)));
        assertNull(OrbitalRod.payloadOf(plugin, null));
    }
}
