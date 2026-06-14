package de.derfakegamer.sentinel.updater;

import de.derfakegamer.sentinel.Sentinel;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {
    ServerMock server; Sentinel plugin;

    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(Sentinel.class); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private static final String SAMPLE_JSON = """
        {
          "tag_name": "v1.5.0",
          "assets": [
            {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"},
            {"name": "Sentinel.jar", "browser_download_url": "https://example.com/Sentinel.jar"}
          ]
        }""";

    @Test void parsesTagName() {
        assertEquals("v1.5.0", UpdateChecker.parseLatestTag(SAMPLE_JSON));
    }

    @Test void parsesJarAssetUrl() {
        assertEquals("https://example.com/Sentinel.jar", UpdateChecker.parseJarDownloadUrl(SAMPLE_JSON));
    }

    @Test void noJarAssetReturnsNull() {
        assertNull(UpdateChecker.parseJarDownloadUrl("{\"tag_name\":\"v1.0.0\",\"assets\":[]}"));
    }

    @Test void newerThanRunningVersionIsDetected() {
        UpdateChecker checker = new UpdateChecker(plugin);
        // plugin.yml version is 1.0.0
        assertTrue(checker.isNewer("v1.5.0"));
        assertFalse(checker.isNewer("v1.0.0"));
        assertFalse(checker.isNewer("v0.9.0"));
    }
}
