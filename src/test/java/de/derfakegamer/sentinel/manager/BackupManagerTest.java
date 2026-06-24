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

    private static File goodZip(java.nio.file.Path dir) throws Exception {
        File f = dir.resolve("good.zip").toFile();
        try (var zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(f))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
        return f;
    }

    @Test void validateZipAcceptsGoodArchive(@TempDir Path tmp) throws Exception {
        assertDoesNotThrow(() -> BackupManager.validateZip(goodZip(tmp)));
    }

    @Test void validateZipRejectsGarbage(@TempDir Path tmp) throws Exception {
        File f = tmp.resolve("bad.zip").toFile();
        Files.write(f.toPath(), "not a zip at all".repeat(10).getBytes());
        assertThrows(java.io.IOException.class, () -> BackupManager.validateZip(f));
    }

    @Test void validateZipRejectsEmptyArchive(@TempDir Path tmp) throws Exception {
        File f = tmp.resolve("empty.zip").toFile();
        try (var zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(f))) { /* no entries */ }
        assertThrows(java.io.IOException.class, () -> BackupManager.validateZip(f));
    }

    @Test void moveIntoPlaceReplacesDestAndRemovesTmp(@TempDir Path tmp) throws Exception {
        File dest = tmp.resolve("backup-1.zip").toFile();
        Files.writeString(dest.toPath(), "OLD");
        File part = tmp.resolve("backup-1.zip.part").toFile();
        Files.writeString(part.toPath(), "NEW");
        BackupManager.moveIntoPlace(part, dest);
        assertEquals("NEW", Files.readString(dest.toPath()));
        assertFalse(part.exists(), "tmp must be gone after the move");
    }

    @Test void zipWorldsLeavesNoPartFileOnSuccess(@TempDir Path tmp) throws Exception {
        BackupManager m = new BackupManager(plugin);
        File world = tmp.resolve("world").toFile();
        new File(world, "region").mkdirs();
        Files.writeString(new File(world, "level.dat").toPath(), "data");
        File zip = tmp.resolve("backup-9.zip").toFile();
        m.zipWorlds(java.util.List.of(world), zip);
        assertTrue(zip.exists());
        assertFalse(new File(tmp.toFile(), "backup-9.zip.part").exists(), "no .part left behind");
    }

    @Test void pruneLogsWhenDeleteFails(@TempDir Path tmp) throws Exception {
        File d = tmp.resolve("backup-0.zip").toFile();
        new File(d, "child").mkdirs();            // non-empty dir → File.delete() returns false
        d.setLastModified(0L);
        for (int i = 1; i <= 3; i++) {
            File f = tmp.resolve("backup-" + i + ".zip").toFile();
            Files.writeString(f.toPath(), "x"); f.setLastModified(1000L * i);
        }
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var h = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord r) { records.add(r); }
            public void flush() {} public void close() {}
        };
        plugin.getLogger().addHandler(h);
        new BackupManager(plugin).prune(tmp.toFile(), 2);
        plugin.getLogger().removeHandler(h);
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.WARNING
                && r.getMessage().contains("could not delete")), "expected a warning for the failed delete");
    }
}
