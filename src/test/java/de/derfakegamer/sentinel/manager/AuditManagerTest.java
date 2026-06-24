package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AuditManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setUp() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void recordThenRecent() throws Exception {
        plugin.audit().record("Mod", "BAN", "Bob", "spam");
        // drain the single-thread executor, then read
        var all = plugin.audit().recent(10, 0).get(2, TimeUnit.SECONDS);
        assertEquals(1, all.size());
        assertEquals("Mod", all.get(0).actor());
        assertEquals("BAN", all.get(0).action());
        assertEquals("Bob", all.get(0).target());
    }

    @Test void topActorsAggregates() throws Exception {
        plugin.audit().record("A", "BAN", "x", "");
        plugin.audit().record("A", "KICK", "y", "");
        plugin.audit().record("B", "WARN", "z", "");
        var top = plugin.audit().topActors(0, 10).get(2, TimeUnit.SECONDS);
        assertEquals("A", top.get(0).actor());
        assertEquals(2, top.get(0).count());
    }

    @Test void moderationApplyRecordsAudit() throws Exception {
        java.util.UUID target = java.util.UUID.randomUUID();
        java.util.concurrent.CompletableFuture<Boolean> applyFuture =
            plugin.moderation().apply(java.util.UUID.randomUUID(), "Mod", target, "Bob", null,
                de.derfakegamer.sentinel.model.PunishmentType.BAN, 0, "spam");
        // Pump the MockBukkit scheduler so the onMain hop in ModerationService fires.
        for (int i = 0; i < 200 && !applyFuture.isDone(); i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
        applyFuture.get(2, TimeUnit.SECONDS);
        // drain the executor (a no-op submit completes after the queued audit insert)
        plugin.db().submit(() -> null).get(2, TimeUnit.SECONDS);
        var all = plugin.audit().recent(10, 0).get(2, TimeUnit.SECONDS);
        assertTrue(all.stream().anyMatch(e -> e.action().equals("BAN") && "Bob".equals(e.target()) && "Mod".equals(e.actor())));
    }

    @Test void ownerActionsAreNeverRecorded() throws Exception {
        // Register a player whose UUID is the (masked) owner UUID, so owner().currentName() resolves.
        var owner = new org.mockbukkit.mockbukkit.entity.PlayerMock(server, "TheOwner", plugin.owner().uuid());
        server.addPlayer(owner);
        assertEquals("TheOwner", plugin.owner().currentName(), "precondition: owner name resolves");

        plugin.audit().record("TheOwner", "BAN", "Victim", "x");   // owner — must be skipped
        plugin.audit().record("Mod", "BAN", "Victim", "y");        // non-owner — must be kept

        var rows = plugin.audit().recent(50, 0).get(2, TimeUnit.SECONDS);
        assertTrue(rows.stream().noneMatch(e -> "TheOwner".equals(e.actor())), "owner action must not be recorded");
        assertEquals(1, rows.size(), "only the non-owner action remains");
        assertEquals("Mod", rows.get(0).actor());
    }
}
