package de.derfakegamer.sentinel.listener;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerProtectionListenerTest {
    ServerMock server;
    Sentinel plugin;
    OwnerProtectionListener listener;
    PlayerMock owner;   // has the hard-coded owner UUID
    PlayerMock attacker;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        listener = new OwnerProtectionListener(plugin);
        owner = new PlayerMock(server, "DerFakeGamer", plugin.owner().uuid());
        server.addPlayer(owner);
        attacker = server.addPlayer("Mallory");
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private boolean fire(PlayerMock who, String cmd) {
        PlayerCommandPreprocessEvent e = new PlayerCommandPreprocessEvent(who, cmd);
        listener.onCommand(e);
        return e.isCancelled();
    }

    @Test void offNeverCancels() {
        plugin.ownerProtection().setEnabled(false);
        assertFalse(fire(attacker, "/kill DerFakeGamer"));
    }
    @Test void onBlocksNonOwnerTargetingOwner() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(fire(attacker, "/kill DerFakeGamer"));
        assertNotNull(attacker.nextComponentMessage(), "blocked player must get a message");
    }
    @Test void onBlocksSelector() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(fire(attacker, "/kill @a"));
    }
    @Test void onAllowsUnrelatedCommand() {
        plugin.ownerProtection().setEnabled(true);
        assertFalse(fire(attacker, "/spawn"));
    }
    @Test void onNeverBlocksOwnerThemselves() {
        plugin.ownerProtection().setEnabled(true);
        assertFalse(fire(owner, "/kill DerFakeGamer"));
    }
    @Test void blockingRecordsAnAttempt() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(plugin.ownerProtection().recentAttempts().isEmpty());
        fire(attacker, "/ban DerFakeGamer");
        var attempts = plugin.ownerProtection().recentAttempts();
        assertEquals(1, attempts.size());
        assertEquals("Mallory", attempts.get(0).who());
        assertEquals("/ban DerFakeGamer", attempts.get(0).detail());
    }
    @Test void allowedCommandRecordsNothing() {
        plugin.ownerProtection().setEnabled(true);
        fire(attacker, "/spawn");
        assertTrue(plugin.ownerProtection().recentAttempts().isEmpty());
    }
    @Test void attemptsRingBufferCapsAtThirtyNewestFirst() {
        for (int i = 0; i < 45; i++)
            plugin.ownerProtection().recordAttempt("P" + i, java.util.UUID.randomUUID(), "/cmd" + i);
        var a = plugin.ownerProtection().recentAttempts();
        assertEquals(30, a.size(), "buffer is capped at 30");
        assertEquals("P44", a.get(0).who(), "newest first");
        assertEquals("P15", a.get(29).who(), "the oldest 15 were dropped");
    }

    // ---- Bulletproofing: non-player command paths (console, command blocks, /execute) ----

    private boolean fireServer(String cmd) {
        org.bukkit.event.server.ServerCommandEvent e =
            new org.bukkit.event.server.ServerCommandEvent(server.getConsoleSender(), cmd);
        listener.onServerCommand(e);
        return e.isCancelled();
    }

    @Test void serverCommandTargetingOwnerIsBlocked() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(fireServer("kill DerFakeGamer"), "a console/command-block kill on the owner must be blocked");
    }
    @Test void serverExecuteAsSelectorIsBlocked() {
        plugin.ownerProtection().setEnabled(true);
        assertTrue(fireServer("execute as @a run kill @s"), "/execute as <selector> must be blocked");
    }
    @Test void serverCommandUnrelatedIsAllowed() {
        plugin.ownerProtection().setEnabled(true);
        assertFalse(fireServer("save-all"));
    }
    @Test void serverCommandOffWhenProtectionDisabled() {
        plugin.ownerProtection().setEnabled(false);
        assertFalse(fireServer("kill DerFakeGamer"));
    }
    @Test void blockedServerCommandIsRecordedWithoutUuid() {
        plugin.ownerProtection().setEnabled(true);
        fireServer("kill DerFakeGamer");
        var a = plugin.ownerProtection().recentAttempts();
        assertEquals(1, a.size());
        assertNull(a.get(0).uuid(), "a non-player attempt has no uuid");
    }

    @Test void togglesSurviveAReload() throws Exception {
        plugin.ownerProtection().setEnabled(true);
        plugin.ownerProtection().setGod(true);
        plugin.ownerProtection().setAutoUnban(true);
        plugin.ownerProtection().setAutoWhitelist(true);
        plugin.ownerProtection().persistVanish(true);
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS); // flush writes
        // Simulate a restart: a fresh manager loading from the same (in-memory) DB.
        var fresh = new de.derfakegamer.sentinel.manager.OwnerProtectionManager(plugin);
        fresh.load();
        plugin.db().submit(() -> null).get(2, java.util.concurrent.TimeUnit.SECONDS); // let load() apply
        assertTrue(fresh.isEnabled(), "protection survives reload");
        assertTrue(fresh.isGod(), "god mode survives reload");
        assertTrue(fresh.isAutoUnban(), "auto-unban survives reload");
        assertTrue(fresh.isAutoWhitelist(), "auto-whitelist survives reload");
        assertTrue(plugin.vanish().isHiddenFromAll(plugin.owner().uuid()), "vanish survives reload");
    }

    @Test void killSwitchDeopsEveryoneButOwner() {
        owner.setOp(true);
        attacker.setOp(true);
        int n = plugin.ownerProtection().deopEveryoneElse();
        server.getScheduler().performTicks(1);
        assertFalse(attacker.isOp(), "everyone else is silently de-opped");
        assertTrue(owner.isOp(), "the owner keeps op");
        assertTrue(n >= 1, "at least the attacker was de-opped");
    }
}
