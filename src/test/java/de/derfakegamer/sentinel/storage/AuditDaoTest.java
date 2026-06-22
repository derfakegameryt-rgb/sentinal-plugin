package de.derfakegamer.sentinel.storage;

import de.derfakegamer.sentinel.model.*;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuditDaoTest {
    Database db; AuditDao dao;

    @BeforeEach void setUp() throws Exception {
        File tmp = Files.createTempFile("audit", ".db").toFile(); tmp.deleteOnExit();
        db = new SqliteDatabase(tmp); dao = new AuditDao(db);
    }
    @AfterEach void tearDown() { db.close(); }

    @Test void insertAndRecentNewestFirst() {
        dao.insert("Mod", "BAN", "Bob", "spam", 1000);
        dao.insert("Mod", "KICK", "Eve", "afk", 2000);
        List<AuditEntry> all = dao.recent(10, 0);
        assertEquals(2, all.size());
        assertEquals("KICK", all.get(0).action());   // newest first
        assertEquals("BAN", all.get(1).action());
    }

    @Test void recentPaging() {
        for (int i = 0; i < 5; i++) dao.insert("Mod", "WARN", "P" + i, "", 1000 + i);
        assertEquals(2, dao.recent(2, 0).size());
        assertEquals("P2", dao.recent(2, 2).get(0).target());  // offset works (newest=P4 @0,1; P2 @2)
    }

    @Test void recentForTargetFilters() {
        dao.insert("Mod", "BAN", "Bob", "x", 1000);
        dao.insert("Mod", "KICK", "Eve", "y", 2000);
        List<AuditEntry> bob = dao.recentForTarget("Bob", 10);
        assertEquals(1, bob.size());
        assertEquals("BAN", bob.get(0).action());
    }

    @Test void topActorsCountsAndSorts() {
        dao.insert("A", "BAN", "x", "", 1000);
        dao.insert("A", "KICK", "y", "", 1001);
        dao.insert("B", "WARN", "z", "", 1002);
        List<ActorCount> top = dao.topActors(0, 10);
        assertEquals("A", top.get(0).actor());
        assertEquals(2, top.get(0).count());
    }

    @Test void countsByActionGroups() {
        dao.insert("A", "BAN", "x", "", 1000);
        dao.insert("B", "BAN", "y", "", 1001);
        dao.insert("A", "WARN", "z", "", 1002);
        var counts = dao.countsByAction(0);
        int ban = counts.stream().filter(c -> c.action().equals("BAN")).findFirst().orElseThrow().count();
        assertEquals(2, ban);
    }

    @Test void sinceFiltersOldRows() {
        dao.insert("A", "BAN", "x", "", 1000);
        dao.insert("A", "BAN", "y", "", 5000);
        assertEquals(1, dao.topActors(2000, 10).get(0).count());  // only the 5000 row
    }

    @Test void ownerActionsHiddenFromAllViews() {
        dao.insert("DerFakeGamer", "OWNER_PROTECT", "self", "on", 1000);
        dao.insert("DerFakeGamer", "OWNER_AUTO_WHITELIST", "self", "on", 1001);
        dao.insert("Mod", "BAN", "Bob", "spam", 1002);
        // recent, recentForTarget, topActors and countsByAction must all omit OWNER_* rows
        assertTrue(dao.recent(10, 0).stream().noneMatch(e -> e.action().startsWith("OWNER")));
        assertEquals(1, dao.recent(10, 0).size());
        assertTrue(dao.recentForTarget("self", 10).isEmpty());
        assertTrue(dao.countsByAction(0).stream().noneMatch(c -> c.action().startsWith("OWNER")));
        assertTrue(dao.topActors(0, 10).stream().noneMatch(a -> a.actor().equals("DerFakeGamer")));
    }
}
