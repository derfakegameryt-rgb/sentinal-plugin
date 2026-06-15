package de.derfakegamer.sentinel.storage;

import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatLogDaoTest {
    Database db; ChatLogDao dao; File tmp;
    UUID who = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        tmp = Files.createTempFile("sentinel", ".db").toFile();
        db = new Database(tmp);
        dao = new ChatLogDao(db);
    }
    @AfterEach void teardown() throws Exception { db.close(); tmp.delete(); }

    @Test void logsAndReadsRecentNewestFirst() {
        dao.log(who, "Bob", "CHAT", "hello", 100);
        dao.log(who, "Bob", "COMMAND", "/help", 200);
        var recent = dao.recent(who, 10);
        assertEquals(2, recent.size());
        assertEquals("/help", recent.get(0).text()); // newest first
    }

    @Test void recentIsLimited() {
        for (int i = 0; i < 20; i++) dao.log(who, "Bob", "CHAT", "m" + i, i);
        assertEquals(5, dao.recent(who, 5).size());
    }
}
