package de.derfakegamer.sentinel;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicYamlSaveTest {

    @Test void writesKeysAndLeavesNoTmp(@TempDir Path dir) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("a.b", "hello");
        File dest = dir.resolve("messages.yml").toFile();
        Sentinel.saveYamlAtomically(cfg, dest);
        assertTrue(dest.exists());
        assertEquals("hello", YamlConfiguration.loadConfiguration(dest).getString("a.b"));
        assertFalse(new File(dir.toFile(), "messages.yml.tmp").exists(), "no .tmp left behind");
    }

    @Test void replacesExistingFile(@TempDir Path dir) throws Exception {
        File dest = dir.resolve("messages.yml").toFile();
        Files.writeString(dest.toPath(), "old: 1\n");
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("fresh", "val");
        Sentinel.saveYamlAtomically(cfg, dest);
        YamlConfiguration back = YamlConfiguration.loadConfiguration(dest);
        assertEquals("val", back.getString("fresh"));
        assertFalse(back.contains("old"), "old content must be fully replaced");
    }
}
