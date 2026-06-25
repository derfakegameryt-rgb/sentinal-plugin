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

    @Test void usesPublicBrowserDownloadUrl() {
        // Real GitHub asset objects carry both "url" (API) and "browser_download_url".
        // For a PUBLIC repo we use the direct browser_download_url (no token / no auth needed).
        String json = """
            {"tag_name":"v1.5.0","assets":[
              {"name":"Sentinel.jar",
               "url":"https://api.github.com/repos/o/r/releases/assets/42",
               "browser_download_url":"https://example.com/Sentinel.jar"}
            ]}""";
        assertEquals("https://example.com/Sentinel.jar",
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

    // ---- Vercel CDN manifest (primary source) ----

    @Test void parsesManifest() {
        String json = """
            {"version":"v3.2.5","url":"https://cdn.example/sentinel.jar",
             "sha256":"abc123","size":245678}""";
        String[] m = UpdateChecker.parseManifest(json);
        assertNotNull(m);
        assertEquals("v3.2.5", m[0]);
        assertEquals("https://cdn.example/sentinel.jar", m[1]);
        assertEquals("abc123", m[2]);
    }

    @Test void manifestWithoutSha256ParsesWithNullHash() {
        String[] m = UpdateChecker.parseManifest(
            "{\"version\":\"v3.2.5\",\"url\":\"https://cdn.example/sentinel.jar\"}");
        assertNotNull(m);
        assertNull(m[2], "a manifest without sha256 yields a null hash (verification is skipped)");
    }

    @Test void manifestMissingVersionOrUrlReturnsNull() {
        assertNull(UpdateChecker.parseManifest("{\"url\":\"https://cdn.example/sentinel.jar\"}"));
        assertNull(UpdateChecker.parseManifest("{\"version\":\"v3.2.5\"}"));
        assertNull(UpdateChecker.parseManifest("{}"));
    }

    // ---- sha256 download verification ----

    @Test void verifySha256AcceptsMatchingDigest() throws Exception {
        java.io.File f = java.io.File.createTempFile("sentinel-sha", ".bin");
        f.deleteOnExit();
        byte[] data = "hello sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(f.toPath(), data);
        String expected = sha256Hex(data);
        assertDoesNotThrow(() -> UpdateChecker.verifySha256(f, expected));
        assertDoesNotThrow(() -> UpdateChecker.verifySha256(f, expected.toUpperCase()), "case-insensitive");
    }

    @Test void verifySha256RejectsMismatch() throws Exception {
        java.io.File f = java.io.File.createTempFile("sentinel-sha", ".bin");
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), "real bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
            () -> UpdateChecker.verifySha256(f, "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
