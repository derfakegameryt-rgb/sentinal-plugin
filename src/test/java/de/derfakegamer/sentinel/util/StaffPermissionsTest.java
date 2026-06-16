package de.derfakegamer.sentinel.util;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class StaffPermissionsTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void nodeMappingIsStable() {
        assertEquals("sentinel.ban", StaffPermissions.node(PunishmentType.BAN));
        assertEquals("sentinel.mute", StaffPermissions.node(PunishmentType.MUTE));
    }
    @Test void helperWithOnlyMuteCannotBan() {
        PlayerMock helper = server.addPlayer("Helper"); // not op
        helper.addAttachment(plugin, "sentinel.mute", true);
        assertTrue(plugin.staffPerms().canPerform(helper, PunishmentType.MUTE));
        assertFalse(plugin.staffPerms().canPerform(helper, PunishmentType.BAN));
    }
    @Test void operatorCanDoEverything() {
        PlayerMock op = server.addPlayer("Admin"); op.setOp(true);
        for (PunishmentType t : PunishmentType.values())
            assertTrue(plugin.staffPerms().canPerform(op, t));
    }
}
