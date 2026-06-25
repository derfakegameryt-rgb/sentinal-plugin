package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnerCommandVisibilityListenerTest {
    ServerMock server; Sentinel plugin; OwnerCommandVisibilityListener listener;
    PlayerMock owner;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        listener = new OwnerCommandVisibilityListener(plugin);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private List<String> filter(PlayerMock who) {
        PlayerCommandSendEvent e = new PlayerCommandSendEvent(who, new ArrayList<>(List.of("ban", "report")));
        listener.onSend(e);
        return new ArrayList<>(e.getCommands());
    }

    @Test void notOpOwnerHidesPrivilegedButKeepsNormalCommands() {
        assertFalse(owner.isOp());
        List<String> shown = filter(owner);
        assertFalse(shown.contains("ban"), "an op-only command must be hidden from tab when the owner is not op");
        assertTrue(shown.contains("report"), "an unrestricted command stays — tab must look like a normal player's");
    }

    @Test void opOwnerKeepsFullTabCompletion() {
        owner.setOp(true);
        assertTrue(filter(owner).contains("ban"), "an op owner gets normal full tab completion");
    }

    @Test void nonOwnerListIsNeverTouched() {
        PlayerMock eve = server.addPlayer("Eve");
        assertTrue(filter(eve).contains("ban"), "the listener must only ever affect the owner");
    }
}
