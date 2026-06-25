package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class VanishManagerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void toggleTracksVanishState() {
        PlayerMock staff = server.addPlayer("Mod");
        assertTrue(plugin.vanish().toggle(staff));   // now vanished
        assertTrue(plugin.vanish().isVanished(staff.getUniqueId()));
        assertFalse(plugin.vanish().toggle(staff));  // now visible
        assertFalse(plugin.vanish().isVanished(staff.getUniqueId()));
    }

    @Test void vanishedHiddenFromNonOp() {
        PlayerMock staff = server.addPlayer("Mod");
        PlayerMock normal = server.addPlayer("Player");
        plugin.vanish().toggle(staff); // vanish — schedules hidePlayer via runForEntity
        // Pump the scheduler so the runForEntity hide task actually executes.
        server.getScheduler().performTicks(1);
        assertFalse(normal.canSee(staff), "non-op should not see a vanished staff member");
    }

    @Test void ownerVanishHidesEvenFromOps() {
        PlayerMock owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        assertTrue(plugin.vanish().toggleOwner(owner));                 // now owner-tier vanished
        assertTrue(plugin.vanish().isVanished(owner.getUniqueId()));
        assertTrue(plugin.vanish().isHiddenFromAll(owner.getUniqueId()));
        server.getScheduler().performTicks(1);                          // drain the hide tasks
        assertFalse(admin.canSee(owner), "an op must NOT see an owner-tier vanished player");
        assertFalse(plugin.vanish().toggleOwner(owner));               // back to visible
        server.getScheduler().performTicks(1);
        assertTrue(admin.canSee(owner));
        assertFalse(plugin.vanish().isHiddenFromAll(owner.getUniqueId()));
    }

    @Test void ownerVanishBroadcastsFakeLeaveToOwnerToo() {
        PlayerMock owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        while (owner.nextComponentMessage() != null) { /* drain join noise */ }
        plugin.vanish().toggleOwner(owner);                            // ON -> fake leave
        net.kyori.adventure.text.Component msg = owner.nextComponentMessage();
        assertNotNull(msg, "the owner must see the fake leave message, even alone");
        assertInstanceOf(net.kyori.adventure.text.TranslatableComponent.class, msg);
        assertEquals("multiplayer.player.left",
            ((net.kyori.adventure.text.TranslatableComponent) msg).key());
        plugin.vanish().toggleOwner(owner);                            // OFF -> fake join
        net.kyori.adventure.text.Component back = owner.nextComponentMessage();
        assertInstanceOf(net.kyori.adventure.text.TranslatableComponent.class, back);
        assertEquals("multiplayer.player.joined",
            ((net.kyori.adventure.text.TranslatableComponent) back).key());
    }

    @Test void restoredOwnerVanishStaysHiddenOnJoin() {
        java.util.UUID id = plugin.owner().uuid();
        plugin.vanish().restoreOwnerVanish(id);                         // simulate persisted vanish after restart
        assertTrue(plugin.vanish().isVanished(id));
        assertTrue(plugin.vanish().isHiddenFromAll(id));
        PlayerMock op = server.addPlayer("Op");
        op.setOp(true);
        PlayerMock owner = new PlayerMock(server, "DerFakeGamer", id);
        server.addPlayer(owner);
        plugin.vanish().applyOnJoin(owner);                            // the join hook re-hides the owner
        server.getScheduler().performTicks(1);
        assertFalse(op.canSee(owner), "a restored-vanished owner must be hidden again on reconnect");
    }

    @Test void ownerVanishFakeLeaveAlsoGoesToConsole() {
        PlayerMock owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        var console = server.getConsoleSender();
        while (console.nextComponentMessage() != null) { /* drain startup/join noise */ }
        plugin.vanish().toggleOwner(owner);                            // fake leave -> players AND console
        // Console was drained above, so any message now is the fake leave reaching the console
        // (a real disconnect logs to console, so the fake one must too).
        assertNotNull(console.nextComponentMessage(), "the fake leave must also reach the console");
    }

    @Test void adminVanishStillVisibleToOps() {
        PlayerMock staff = server.addPlayer("Mod");
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        plugin.vanish().toggle(staff);                                  // admin-tier vanish
        server.getScheduler().performTicks(1);
        assertTrue(admin.canSee(staff), "an op still sees an admin-tier vanished staff member");
        assertFalse(plugin.vanish().isHiddenFromAll(staff.getUniqueId()));
    }

    @Test void adminVanishSuppressesPotionParticles() {
        PlayerMock staff = server.addPlayer("Mod");
        staff.addPotionEffect(new org.bukkit.potion.PotionEffect(
            org.bukkit.potion.PotionEffectType.SPEED, 200, 0, false, true, true));   // particles on
        plugin.vanish().toggle(staff);                                  // admin-tier vanish
        server.getScheduler().performTicks(1);                          // drain the strip task
        org.bukkit.potion.PotionEffect eff = staff.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
        assertNotNull(eff);
        assertEquals(0, eff.getAmplifier(), "amplifier preserved");
        assertFalse(eff.hasParticles(), "a vanished admin's potion particles must be suppressed");
    }

    @Test void vanishedStaffStaysHiddenAfterRelog() {
        PlayerMock staff = server.addPlayer("Mod");
        PlayerMock normal = server.addPlayer("Player");
        plugin.vanish().toggle(staff); // vanish; schedules hidePlayer via runForEntity
        server.getScheduler().performTicks(1); // drain the hide task
        // Simulate a relog: the server clears visibility state, so normal can momentarily see staff again.
        normal.showPlayer(plugin, staff);
        assertTrue(normal.canSee(staff));
        // The join handler must re-hide the still-vanished staff member.
        plugin.vanish().applyOnJoin(staff);
        server.getScheduler().performTicks(1); // drain the re-hide task
        assertFalse(normal.canSee(staff), "a vanished staff member must remain hidden after relogging");
    }
}
