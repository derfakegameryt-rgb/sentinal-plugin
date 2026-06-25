package de.derfakegamer.sentinel.listener;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class OwnerGodListenerTest {
    ServerMock server; Sentinel plugin; OwnerGodListener listener;
    PlayerMock owner; PlayerMock other;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        listener = new OwnerGodListener(plugin);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        other = server.addPlayer("Someone");
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private boolean damage(PlayerMock who) {
        EntityDamageEvent e = new EntityDamageEvent(who, EntityDamageEvent.DamageCause.FALL, 5.0);
        listener.onDamage(e);
        return e.isCancelled();
    }

    @Test void godOnCancelsOwnerDamage() {
        plugin.ownerProtection().setGod(true);
        assertTrue(damage(owner), "owner takes no damage while god-mode is on");
    }

    @Test void godOffLetsOwnerTakeDamage() {
        plugin.ownerProtection().setGod(false);
        assertFalse(damage(owner));
    }

    @Test void godNeverProtectsOthers() {
        plugin.ownerProtection().setGod(true);
        assertFalse(damage(other), "god-mode only protects the owner");
    }
}
