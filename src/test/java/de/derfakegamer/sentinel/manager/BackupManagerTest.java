package de.derfakegamer.sentinel.manager;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupManagerTest {
    ServerMock server; Sentinel plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void pruneKeepsNewest(@TempDir Path tmp) throws Exception {
        BackupManager m = new BackupManager(plugin);
        for (int i = 0; i < 5; i++) {
            File f = tmp.resolve("backup-" + i + ".zip").toFile();
            Files.writeString(f.toPath(), "x");
            f.setLastModified(1000L * i); // ascending mtime
        }
        m.prune(tmp.toFile(), 2);
        File[] left = tmp.toFile().listFiles((d, n) -> n.endsWith(".zip"));
        assertNotNull(left);
        assertEquals(2, left.length);
        assertTrue(new File(tmp.toFile(), "backup-3.zip").exists());
        assertTrue(new File(tmp.toFile(), "backup-4.zip").exists());
        assertFalse(new File(tmp.toFile(), "backup-0.zip").exists());
    }

    @Test void zipWritesAnArchive(@TempDir Path tmp) throws Exception {
        BackupManager m = new BackupManager(plugin);
        File world = tmp.resolve("world").toFile();
        new File(world, "region").mkdirs();
        Files.writeString(new File(world, "level.dat").toPath(), "data");
        File zip = tmp.resolve("out.zip").toFile();
        m.zipWorlds(java.util.List.of(world), zip);
        assertTrue(zip.exists());
        assertTrue(zip.length() > 0);
    }
}
