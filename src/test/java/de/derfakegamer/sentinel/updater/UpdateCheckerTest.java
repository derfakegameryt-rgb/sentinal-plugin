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

    @Test void prefersAssetApiUrlOverBrowserUrlForPrivateRepoSupport() {
        // Real GitHub asset objects carry both "url" (API) and "browser_download_url".
        // The API url is the one that works with a token for private repos, so it wins.
        String json = """
            {"tag_name":"v1.5.0","assets":[
              {"name":"Sentinel.jar",
               "url":"https://api.github.com/repos/o/r/releases/assets/42",
               "browser_download_url":"https://example.com/Sentinel.jar"}
            ]}""";
        assertEquals("https://api.github.com/repos/o/r/releases/assets/42",
            UpdateChecker.parseJarDownloadUrl(json));
    }

    private static final String RELEASES_JSON = """
        [
          {"tag_name":"v1.0.0","draft":false,"prerelease":false,
           "assets":[{"name":"Sentinel-1.0.0.jar","browser_download_url":"https://example.com/1.0.0.jar"}]},
          {"tag_name":"v1.2.0","draft":false,"prerelease":false,
           "assets":[{"name":"Sentinel-1.2.0.jar","browser_download_url":"https://example.com/1.2.0.jar"}]},
          {"tag_name":"v1.1.4","draft":false,"prerelease":false,
           "assets":[{"name":"Sentinel-1.1.4.jar","browser_download_url":"https://example.com/1.1.4.jar"}]}
        ]""";

    @Test void picksHighestVersionRegardlessOfOrder() {
        String[] best = UpdateChecker.parseBestRelease(RELEASES_JSON);
        assertNotNull(best);
        assertEquals("v1.2.0", best[0]);
        assertEquals("https://example.com/1.2.0.jar", best[1]);
    }

    @Test void skipsDraftsPrereleasesAndAssetlessReleases() {
        String json = """
            [
              {"tag_name":"v2.0.0","draft":true,"prerelease":false,
               "assets":[{"name":"Sentinel-2.0.0.jar","browser_download_url":"https://example.com/2.0.0.jar"}]},
              {"tag_name":"v1.9.0","draft":false,"prerelease":true,
               "assets":[{"name":"Sentinel-1.9.0.jar","browser_download_url":"https://example.com/1.9.0.jar"}]},
              {"tag_name":"v1.8.0","draft":false,"prerelease":false,"assets":[]},
              {"tag_name":"v1.3.0","draft":false,"prerelease":false,
               "assets":[{"name":"Sentinel-1.3.0.jar","browser_download_url":"https://example.com/1.3.0.jar"}]}
            ]""";
        String[] best = UpdateChecker.parseBestRelease(json);
        assertNotNull(best);
        assertEquals("v1.3.0", best[0]); // draft 2.0.0, prerelease 1.9.0, assetless 1.8.0 all skipped
    }

    @Test void emptyReleasesListReturnsNull() {
        assertNull(UpdateChecker.parseBestRelease("[]"));
    }

    @Test void newerThanRunningVersionIsDetected() {
        UpdateChecker checker = new UpdateChecker(plugin);
        String running = plugin.getPluginMeta().getVersion();
        assertTrue(checker.isNewer("v999.0.0"));   // far newer than any running version
        assertFalse(checker.isNewer(running));      // same as running version
        assertFalse(checker.isNewer("v0.0.1"));     // older than any running version
    }
}
