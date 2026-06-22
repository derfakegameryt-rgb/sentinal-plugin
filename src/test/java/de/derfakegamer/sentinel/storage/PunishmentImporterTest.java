package de.derfakegamer.sentinel.storage;

import static org.junit.jupiter.api.Assertions.*;

import de.derfakegamer.sentinel.model.Punishment;
import de.derfakegamer.sentinel.model.PunishmentType;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;

class PunishmentImporterTest {
    Database db;
    PunishmentDao dao;
    PunishmentImporter importer;
    File sentinelDb;
    File sourceDb;
    Connection source;

    static final UUID TARGET = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ISSUER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setup() throws Exception {
        sentinelDb = Files.createTempFile("sentinel", ".db").toFile();
        db = new SqliteDatabase(sentinelDb);
        dao = new PunishmentDao(db);
        importer = new PunishmentImporter(dao);
        sourceDb = Files.createTempFile("source", ".db").toFile();
        source = DriverManager.getConnection("jdbc:sqlite:" + sourceDb.getAbsolutePath());
    }

    @AfterEach
    void teardown() throws Exception {
        source.close();
        db.close();
        sentinelDb.delete();
        sourceDb.delete();
    }

    private void exec(String sql) throws Exception {
        try (Statement st = source.createStatement()) { st.execute(sql); }
    }

    // ---- LiteBans ----

    @Test
    void importsLiteBansBanMuteWarnKick() throws Exception {
        exec("CREATE TABLE litebans_bans (uuid TEXT, ip TEXT, reason TEXT, banned_by_uuid TEXT, "
            + "banned_by_name TEXT, time INTEGER, until INTEGER, active INTEGER, ipban INTEGER)");
        exec("CREATE TABLE litebans_mutes (uuid TEXT, ip TEXT, reason TEXT, banned_by_uuid TEXT, "
            + "banned_by_name TEXT, time INTEGER, until INTEGER, active INTEGER)");
        exec("CREATE TABLE litebans_warnings (uuid TEXT, ip TEXT, reason TEXT, banned_by_uuid TEXT, "
            + "banned_by_name TEXT, time INTEGER, until INTEGER, active INTEGER)");
        exec("CREATE TABLE litebans_kicks (uuid TEXT, ip TEXT, reason TEXT, banned_by_uuid TEXT, "
            + "banned_by_name TEXT, time INTEGER, until INTEGER, active INTEGER)");
        exec("INSERT INTO litebans_bans VALUES ('" + TARGET + "', '1.2.3.4', 'hax', '" + ISSUER
            + "', 'Admin', 1000, -1, 1, 0)");
        exec("INSERT INTO litebans_mutes VALUES ('" + TARGET + "', '1.2.3.4', 'spam', '" + ISSUER
            + "', 'Admin', 2000, 5000, 1)");
        exec("INSERT INTO litebans_warnings VALUES ('" + TARGET + "', '1.2.3.4', 'rude', '" + ISSUER
            + "', 'Admin', 3000, -1, 1)");
        exec("INSERT INTO litebans_kicks VALUES ('" + TARGET + "', '1.2.3.4', 'afk', '" + ISSUER
            + "', 'Admin', 4000, 0, 0)");

        PunishmentImporter.Result r = importer.importFrom(PunishmentImporter.Source.LITEBANS, source);
        assertEquals(4, r.imported());
        assertEquals(0, r.skipped());

        List<Punishment> hist = dao.findHistory(TARGET);
        assertEquals(4, hist.size());
        Punishment ban = hist.stream().filter(p -> p.type() == PunishmentType.BAN).findFirst().orElseThrow();
        assertEquals("hax", ban.reason());
        assertEquals("Admin", ban.issuerName());
        assertEquals(0, ban.expiresAt(), "until -1 -> permanent (0)");
        assertTrue(ban.active());
        Punishment mute = hist.stream().filter(p -> p.type() == PunishmentType.MUTE).findFirst().orElseThrow();
        assertEquals(5000, mute.expiresAt());
    }

    @Test
    void liteBansIpbanFlagMapsToIpban() throws Exception {
        exec("CREATE TABLE litebans_bans (uuid TEXT, ip TEXT, reason TEXT, banned_by_uuid TEXT, "
            + "banned_by_name TEXT, time INTEGER, until INTEGER, active INTEGER, ipban INTEGER)");
        // an IP-only ban: uuid is null, ipban flag set
        exec("INSERT INTO litebans_bans VALUES (NULL, '9.9.9.9', 'proxy', NULL, NULL, 1000, -1, 1, 1)");
        // empty other tables
        for (String t : new String[]{"mutes", "warnings", "kicks"})
            exec("CREATE TABLE litebans_" + t + " (uuid TEXT, ip TEXT, reason TEXT, banned_by_uuid TEXT, "
                + "banned_by_name TEXT, time INTEGER, until INTEGER, active INTEGER)");

        PunishmentImporter.Result r = importer.importFrom(PunishmentImporter.Source.LITEBANS, source);
        assertEquals(1, r.imported());
        Punishment ip = dao.findActiveByIp(PunishmentType.IPBAN, "9.9.9.9");
        assertNotNull(ip);
        assertEquals("9.9.9.9", ip.targetIp());
        assertEquals("Console", ip.issuerName());
    }

    // ---- AdvancedBan ----

    @Test
    void importsAdvancedBanActiveAndHistoryWithTypeMapping() throws Exception {
        String cols = "(name TEXT, uuid TEXT, reason TEXT, operator TEXT, punishmentType TEXT, "
            + "start INTEGER, end INTEGER)";
        exec("CREATE TABLE Punishments " + cols);
        exec("CREATE TABLE PunishmentHistory " + cols);
        String u = TARGET.toString().replace("-", ""); // AdvancedBan often stores dashless UUIDs
        exec("INSERT INTO Punishments VALUES ('Notch', '" + u + "', 'cheating', 'Admin', 'BAN', 1000, -1)");
        exec("INSERT INTO Punishments VALUES ('Notch', '" + u + "', 'flooding', 'Admin', 'TEMP_MUTE', 2000, 7000)");
        exec("INSERT INTO Punishments VALUES ('Notch', '" + u + "', 'note text', 'Admin', 'NOTE', 2500, -1)");
        exec("INSERT INTO PunishmentHistory VALUES ('Notch', '" + u + "', 'old', 'Admin', 'KICK', 500, 0)");

        PunishmentImporter.Result r = importer.importFrom(PunishmentImporter.Source.ADVANCEDBAN, source);
        assertEquals(3, r.imported(), "BAN + TEMP_MUTE + KICK");
        assertEquals(1, r.skipped(), "NOTE is skipped");

        List<Punishment> hist = dao.findHistory(TARGET);
        assertEquals(3, hist.size());
        Punishment ban = hist.stream().filter(p -> p.type() == PunishmentType.BAN).findFirst().orElseThrow();
        assertTrue(ban.active());
        assertEquals(0, ban.expiresAt());
        Punishment kick = hist.stream().filter(p -> p.type() == PunishmentType.KICK).findFirst().orElseThrow();
        assertFalse(kick.active(), "history rows are inactive");
        assertEquals("Notch", kick.targetName());
    }

    // ---- helpers ----

    @Test
    void normalizeExpiryTreatsNegativeAndZeroAsPermanent() {
        assertEquals(0, PunishmentImporter.normalizeExpiry(-1));
        assertEquals(0, PunishmentImporter.normalizeExpiry(0));
        assertEquals(123, PunishmentImporter.normalizeExpiry(123));
    }

    @Test
    void parseUuidHandlesDashlessAndInvalid() {
        assertEquals(TARGET, PunishmentImporter.parseUuid(TARGET.toString().replace("-", "")));
        assertEquals(TARGET, PunishmentImporter.parseUuid(TARGET.toString()));
        assertNull(PunishmentImporter.parseUuid("not-a-uuid"));
        assertNull(PunishmentImporter.parseUuid(null));
    }
}
