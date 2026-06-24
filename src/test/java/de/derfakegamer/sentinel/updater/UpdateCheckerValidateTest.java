package de.derfakegamer.sentinel.updater;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the download integrity gate: only a genuine, complete Sentinel plugin jar passes. */
class UpdateCheckerValidateTest {

    /** Builds a temp .jar containing the given plugin.yml (or none if null), padded with
     *  incompressible bytes so it comfortably clears the size floor — like a real jar. */
    private static File jar(String pluginYml) throws Exception {
        File f = File.createTempFile("upd-test-", ".jar");
        f.deleteOnExit();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            if (pluginYml != null) {
                zos.putNextEntry(new ZipEntry("plugin.yml"));
                zos.write(pluginYml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            byte[] pad = new byte[8000];
            new Random(42).nextBytes(pad); // incompressible → final zip stays well above the floor
            zos.putNextEntry(new ZipEntry("pad.bin"));
            zos.write(pad);
            zos.closeEntry();
        }
        return f;
    }

    @Test void acceptsRealSentinelJar() throws Exception {
        File f = jar("name: Sentinel\nmain: de.derfakegamer.sentinel.Sentinel\nversion: '9.9.9'\n");
        assertDoesNotThrow(() -> UpdateChecker.validateJar(f));
    }

    @Test void rejectsTooSmallFile() throws Exception {
        File f = File.createTempFile("upd-test-", ".jar");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[50]); // a truncated/near-empty download
        Exception e = assertThrows(Exception.class, () -> UpdateChecker.validateJar(f));
        assertTrue(e.getMessage().contains("too small"));
    }

    @Test void rejectsNonZip() throws Exception {
        File f = File.createTempFile("upd-test-", ".jar");
        f.deleteOnExit();
        Files.write(f.toPath(), "<html>404: Not Found</html>".repeat(100).getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> UpdateChecker.validateJar(f)); // not a valid zip/jar
    }

    @Test void rejectsJarWithoutPluginYml() throws Exception {
        File f = jar(null);
        Exception e = assertThrows(Exception.class, () -> UpdateChecker.validateJar(f));
        assertTrue(e.getMessage().contains("plugin.yml"));
    }

    @Test void rejectsWrongPlugin() throws Exception {
        File f = jar("name: SomeOtherPlugin\nmain: com.example.Other\n");
        Exception e = assertThrows(Exception.class, () -> UpdateChecker.validateJar(f));
        assertTrue(e.getMessage().contains("not the Sentinel plugin"));
    }
}
