package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    /**
     * Pumps the MockBukkit scheduler while waiting for a future, so that tasks scheduled via
     * runTask (the onMain hop in ModerationService) actually execute and the future completes.
     */
    static <T> T await(ServerMock server, CompletableFuture<T> f) throws Exception {
        for (int i = 0; i < 200 && !f.isDone(); i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(5);
        }
        return f.get(2, TimeUnit.SECONDS);
    }

    @Test void applyBanRecordsActiveBan() throws Exception {
        UUID target = UUID.randomUUID();
        boolean ok = await(server, plugin.moderation().apply(
            new UUID(0,0), "Admin", target, "Griefer", null, PunishmentType.BAN, 0, "hax"));
        assertTrue(ok);
        assertNotNull(plugin.punishments().activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void applyExemptReturnsFalseAndRecordsNothing() throws Exception {
        UUID exempt = UUID.randomUUID();
        plugin.getConfig().set("exempt", java.util.List.of(exempt.toString()));
        plugin.saveConfig();
        plugin.reloadAll();
        boolean ok = await(server, plugin.moderation().apply(
            new UUID(0,0), "Admin", exempt, "Owner", null, PunishmentType.BAN, 0, "x"));
        assertFalse(ok);
        assertNull(plugin.punishments().activeBan(exempt, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void removeBanReturnsFalseWhenNotBanned() throws Exception {
        boolean ok = await(server, plugin.moderation().removeBan(new UUID(0,0), "Admin", UUID.randomUUID(), "Nobody"));
        assertFalse(ok);
    }

    @Test void warningNeverAutoBans() throws Exception {
        UUID target = UUID.randomUUID();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 6; i++)
            await(server, plugin.moderation().apply(new UUID(0,0), "Admin", target, "Repeat", null, PunishmentType.WARN, 0, "w" + i));
        assertNull(plugin.punishments().activeBan(target, now).get(2, TimeUnit.SECONDS),
            "warnings must never escalate into an automatic ban");
    }

    /**
     * Regression test: when an online player is banned the side-effect (kick/broadcast) must fire
     * on the main thread. MockBukkit only runs scheduled tasks during performTicks; if the
     * side-effect is wired up correctly, the player will be kicked after ticking the scheduler.
     */
    @Test void applyBanKicksOnlinePlayer() throws Exception {
        PlayerMock target = server.addPlayer("Griefer");
        CompletableFuture<Boolean> future = plugin.moderation().apply(
            new UUID(0, 0), "Admin", target.getUniqueId(), "Griefer", null, PunishmentType.BAN, 0, "cheating");

        boolean ok = await(server, future);
        assertTrue(ok, "apply should return true for a non-exempt player");

        // The kick is fire-and-forget on the player's entity scheduler (runForEntity), dispatched
        // from inside the global-region task. The future completes when the global task finishes,
        // but the inner entity task is still pending — pump one more tick so it runs.
        server.getScheduler().performTicks(1);

        // MockBukkit sets isOnline() to false when kick() is called. That only happens if the
        // runForEntity task actually ran.
        assertFalse(target.isOnline(),
            "the online player must have been kicked after apply(BAN) side-effects ran on the entity thread");
    }
}
