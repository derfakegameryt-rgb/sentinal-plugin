package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.storage.PunishmentDao;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PunishmentManagerTest {
    ServerMock server;
    Sentinel plugin;
    PunishmentManager mgr;
    UUID target = UUID.randomUUID();
    UUID issuer = UUID.randomUUID();

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Sentinel.class);
        mgr = plugin.punishments();
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void banThenActiveBanFound() throws Exception {
        assertTrue(mgr.ban(target, "Notch", issuer, "Admin", "hax", 0).get(2, TimeUnit.SECONDS).isSuccess());
        assertNotNull(mgr.activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void activeBansAmongReturnsOnlyBanned() throws Exception {
        UUID banned = UUID.randomUUID();
        UUID clean = UUID.randomUUID();
        mgr.ban(banned, "Evil", issuer, "Admin", "ban evasion", 0).get(2, TimeUnit.SECONDS);
        Set<UUID> result = mgr.activeBansAmong(java.util.List.of(banned, clean), System.currentTimeMillis())
            .get(2, TimeUnit.SECONDS);
        assertEquals(Set.of(banned), result, "only the banned account is flagged (alt-detection core)");
    }

    @Test void exemptCannotBeBanned() throws Exception {
        UUID exemptId = UUID.randomUUID();
        // Construct a manager with this UUID in the exempt set, sharing the plugin's executor and DB
        PunishmentManager pm2 = new PunishmentManager(plugin, new PunishmentDao(plugin.db().database()), Set.of(exemptId));
        var result = pm2.ban(exemptId, "Owner", issuer, "Admin", "x", 0).get(2, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertNull(pm2.activeBan(exemptId, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void expiredBanReturnsNullAndIsDeactivated() throws Exception {
        long now = 1_000_000L;
        mgr.ban(target, "Notch", issuer, "Admin", "hax", now + 1000).get(2, TimeUnit.SECONDS);
        assertNull(mgr.activeBan(target, now + 2000).get(2, TimeUnit.SECONDS));
        // second lookup confirms it was deactivated, not just filtered
        assertNull(mgr.activeBan(target, now + 1).get(2, TimeUnit.SECONDS));
    }

    @Test void unbanClearsBan() throws Exception {
        mgr.ban(target, "Notch", issuer, "Admin", "hax", 0).get(2, TimeUnit.SECONDS);
        assertTrue(mgr.unban(target, "Admin", System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
        assertNull(mgr.activeBan(target, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void warnIncrementsCount() throws Exception {
        mgr.warn(target, "Notch", issuer, "Admin", "spam").get(2, TimeUnit.SECONDS);
        mgr.warn(target, "Notch", issuer, "Admin", "spam").get(2, TimeUnit.SECONDS);
        assertEquals(2, mgr.warnCount(target).get(2, TimeUnit.SECONDS));
    }

    // ---- mute-cache behaviour ----

    @Test void activeMuteCachedWithinTtlAndInvalidatedOnUnmute() throws Exception {
        long now = System.currentTimeMillis();
        // mute the target → populates muteCache on first activeMute call
        assertTrue(mgr.mute(target, "Notch", issuer, "Admin", "spam", 0).get(2, TimeUnit.SECONDS).isSuccess());
        assertNotNull(mgr.activeMute(target, now).get(2, TimeUnit.SECONDS)); // populates cache
        // second call within TTL must still return the muted value (proves cache is consulted)
        assertNotNull(mgr.activeMute(target, now).get(2, TimeUnit.SECONDS));
        // unmute → must invalidate the cache so the next activeMute reflects the fresh DB state
        assertTrue(mgr.unmute(target, "Admin", now).get(2, TimeUnit.SECONDS));
        assertNull(mgr.activeMute(target, now).get(2, TimeUnit.SECONDS)); // fresh read → not muted
    }

    @Test void activeMuteCacheNotSharedWithShadowMute() throws Exception {
        long now = System.currentTimeMillis();
        // shadow-mute the target
        assertTrue(mgr.shadowMute(target, "Notch", issuer, "Admin", "test", 0).get(2, TimeUnit.SECONDS).isSuccess());
        // shadow-mute must NOT pollute the regular mute cache
        assertNull(mgr.activeMute(target, now).get(2, TimeUnit.SECONDS));
        assertNotNull(mgr.activeShadowMute(target, now).get(2, TimeUnit.SECONDS));
        // unShadowMute must invalidate the shadow cache so the next check returns null
        assertTrue(mgr.unShadowMute(target, "Admin", now).get(2, TimeUnit.SECONDS));
        assertNull(mgr.activeShadowMute(target, now).get(2, TimeUnit.SECONDS));
    }

    @Test void removeIpBanClearsIpBan() throws Exception {
        String ip = "9.9.9.9";
        mgr.ipBan(target, "Notch", ip, issuer, "Admin", "hax", 0).get(2, TimeUnit.SECONDS);
        assertNotNull(mgr.activeIpBan(ip, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
        assertTrue(mgr.removeIpBan(target, "Admin", System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
        assertNull(mgr.activeIpBan(ip, System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void removeIpBanFalseWhenNoActiveIpBan() throws Exception {
        assertFalse(mgr.removeIpBan(target, "Admin", System.currentTimeMillis()).get(2, TimeUnit.SECONDS));
    }

    @Test void expiredWarnsDoNotCountAndArePruned() throws Exception {
        // one warn, issued "now"
        mgr.warn(target, "Notch", issuer, "Admin", "spam").get(2, TimeUnit.SECONDS);
        assertEquals(1, mgr.warnCount(target).get(2, TimeUnit.SECONDS));
        // a cutoff in the future means the warn is "older than" the window → not counted
        PunishmentDao dao = new PunishmentDao(plugin.db().database());
        long futureCutoff = System.currentTimeMillis() + 60_000L;
        assertEquals(0, plugin.db().submit(() -> dao.countWarns(target, futureCutoff)).get(2, TimeUnit.SECONDS));
        // pruneWarns(0) keeps everything; a prune whose cutoff is in the future deletes the row
        assertEquals(1, plugin.db().submitWrite(() -> dao.deleteWarnsOlderThan(futureCutoff)).get(2, TimeUnit.SECONDS));
        assertEquals(0, mgr.warnCount(target).get(2, TimeUnit.SECONDS));
    }
}
