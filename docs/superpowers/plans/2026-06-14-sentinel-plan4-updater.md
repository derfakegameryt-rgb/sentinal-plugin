# Sentinel — Plan 4: Auto-Updater

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Sentinel up to date from GitHub Releases. On startup and on a repeating interval, check the latest release of `derfakegameryt-rgb/sentinal-plugin`; if it is newer than the running version, download the `.jar` asset into Bukkit's update folder so Paper applies it on the next restart, and notify online operators. Add `/sentinel update` for an on-demand check.

**Architecture:** A pure `Version` comparator decides "is the remote tag newer than us." An `UpdateChecker` owns the network + scheduling: it calls the GitHub REST API (`releases/latest`) with `java.net.http.HttpClient`, parses the JSON with Gson (provided by Paper at runtime), and on a newer version downloads the release's `.jar` asset to `Bukkit.getUpdateFolderFile()/<our-jar-name>`. The running plugin is never hot-swapped. JSON parsing and the newer-than decision are split into static/pure methods so they're unit-tested without a network; the HTTP and scheduling are thin wiring.

**Tech Stack:** Java 21 `java.net.http.HttpClient`, Gson (compileOnly — Paper bundles it at runtime), Paper update-folder convention, JUnit 5 + MockBukkit. No new runtime dependency is shaded.

> **Config:** `config.yml` already has the `update:` section from Plan 1 (`enabled: true`, `check-interval-seconds: 1800`, `github-token: ''`). This plan reads it; no config schema change needed.

---

## File Structure

```
build.gradle.kts                       MOD  add compileOnly + testImplementation gson
src/main/java/de/derfakegamer/sentinel/
  util/Version.java                     NEW  semantic-version "is newer" comparison
  updater/UpdateChecker.java            NEW  GitHub poll, parse, download, notify, schedule
  command/SentinelCommand.java          MOD  add `/sentinel update`
  Sentinel.java                         MOD  expose getFile() as pluginJar(); build + start updater; getter
  resources/messages.yml                MOD  updater message keys
src/test/java/de/derfakegamer/sentinel/
  util/VersionTest.java                 NEW
  updater/UpdateCheckerTest.java        NEW  JSON parse + newer-than decision (no network)
```

---

## Task 1: Version comparator + Gson dependency

**Files:**
- Modify: `build.gradle.kts`
- Create: `util/Version.java`
- Test: `util/VersionTest.java`

- [ ] **Step 1: Add Gson to `build.gradle.kts`**

In the `dependencies { }` block, add (Gson is provided by Paper at runtime → `compileOnly`; the parse tests need it on the test classpath):

```kotlin
compileOnly("com.google.code.gson:gson:2.11.0")
testImplementation("com.google.code.gson:gson:2.11.0")
```

- [ ] **Step 2: Write the failing test `VersionTest.java`**

```java
package de.derfakegamer.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionTest {
    @Test void higherPatchIsNewer()  { assertTrue(Version.isNewer("1.0.1", "1.0.0")); }
    @Test void higherMinorIsNewer()  { assertTrue(Version.isNewer("1.1.0", "1.0.9")); }
    @Test void higherMajorIsNewer()  { assertTrue(Version.isNewer("2.0.0", "1.9.9")); }
    @Test void equalIsNotNewer()     { assertFalse(Version.isNewer("1.0.0", "1.0.0")); }
    @Test void lowerIsNotNewer()     { assertFalse(Version.isNewer("1.0.0", "1.0.1")); }
    @Test void leadingVIsStripped()  { assertTrue(Version.isNewer("v1.2.0", "1.1.0")); }
    @Test void differentLengths()    { assertTrue(Version.isNewer("1.2", "1.1.9")); assertFalse(Version.isNewer("1.2", "1.2.0")); }
    @Test void garbageIsNotNewer()   { assertFalse(Version.isNewer("abc", "1.0.0")); }
}
```

- [ ] **Step 3: Run it to verify failure**

Run: `./gradlew test --tests VersionTest`
Expected: FAIL — `Version` does not exist.

- [ ] **Step 4: Write `util/Version.java`**

```java
package de.derfakegamer.sentinel.util;

public final class Version {
    private Version() {}

    /** True iff {@code candidate} is a strictly higher dotted numeric version than {@code current}. */
    public static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        if (a == null) return false;
        if (b == null) return true;
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** Parses "v1.2.3" / "1.2" into int parts, or null if not a dotted-numeric version. */
    private static int[] parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) { return null; }
        return out;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests VersionTest`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: semantic version comparator and gson dependency"
```

---

## Task 2: UpdateChecker (parse, decide, download, notify, schedule)

**Files:**
- Create: `updater/UpdateChecker.java`
- Modify: `Sentinel.java`, `command/SentinelCommand.java`, `messages.yml`
- Test: `updater/UpdateCheckerTest.java`

- [ ] **Step 1: Add message keys to `messages.yml`**

```yaml
update-checking: "<#60A5FA>Checking for updates…"
update-up-to-date: "<#60A5FA>Sentinel is up to date (<version>)."
update-available: "<#60A5FA>Sentinel <white><version></#60A5FA> downloaded — restart the server to apply."
update-failed: "<red>Update check failed: <error>"
```

- [ ] **Step 2: Expose the plugin jar file in `Sentinel.java`**

`JavaPlugin.getFile()` is protected; expose it so the updater can name the downloaded file to match:

```java
public java.io.File pluginJar() { return getFile(); }
```

- [ ] **Step 3: Write the failing test `UpdateCheckerTest.java`**

```java
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
        assertNull(UpdateChecker.parseJarDownloadUrl("{\\"tag_name\\":\\"v1.0.0\\",\\"assets\\":[]}"));
    }

    @Test void newerThanRunningVersionIsDetected() {
        UpdateChecker checker = new UpdateChecker(plugin);
        // plugin.yml version is 1.0.0
        assertTrue(checker.isNewer("v1.5.0"));
        assertFalse(checker.isNewer("v1.0.0"));
        assertFalse(checker.isNewer("v0.9.0"));
    }
}
```

- [ ] **Step 4: Run it to verify failure**

Run: `./gradlew test --tests UpdateCheckerTest`
Expected: FAIL — `UpdateChecker` does not exist.

- [ ] **Step 5: Write `updater/UpdateChecker.java`**

```java
package de.derfakegamer.sentinel.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.util.Version;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public final class UpdateChecker {
    private static final String API =
        "https://api.github.com/repos/derfakegameryt-rgb/sentinal-plugin/releases/latest";

    private final Sentinel plugin;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile String downloadedVersion; // avoid re-downloading the same release each interval

    public UpdateChecker(Sentinel plugin) { this.plugin = plugin; }

    /** Starts the periodic check if enabled in config. Runs one check immediately. */
    public void start() {
        if (!plugin.getConfig().getBoolean("update.enabled", true)) return;
        long seconds = Math.max(60, plugin.getConfig().getLong("update.check-interval-seconds", 1800));
        long ticks = seconds * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
            () -> check(null), 20L, ticks);
    }

    /** On-demand check that always reports back to the requester. */
    public void checkNow(CommandSender requester) {
        requester.sendMessage(plugin.messages().prefixed("update-checking"));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> check(requester));
    }

    /** True iff the given release tag is newer than the running plugin version. */
    public boolean isNewer(String tag) {
        return Version.isNewer(tag, plugin.getPluginMeta().getVersion());
    }

    static String parseLatestTag(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.has("tag_name") ? root.get("tag_name").getAsString() : null;
    }

    static String parseJarDownloadUrl(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("assets")) return null;
        JsonArray assets = root.getAsJsonArray("assets");
        for (var element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (name.toLowerCase().endsWith(".jar"))
                return asset.get("browser_download_url").getAsString();
        }
        return null;
    }

    /** Network check (runs async). Downloads if newer; notifies the requester (or staff on a scheduled run). */
    private void check(CommandSender requester) {
        try {
            String body = httpGet(API);
            String tag = parseLatestTag(body);
            if (tag == null) { report(requester, "update-failed", "error", "no release tag"); return; }

            if (!isNewer(tag)) {
                if (requester != null) report(requester, "update-up-to-date",
                    "version", plugin.getPluginMeta().getVersion());
                return;
            }
            if (tag.equals(downloadedVersion)) return; // already downloaded this release this session

            String jarUrl = parseJarDownloadUrl(body);
            if (jarUrl == null) { report(requester, "update-failed", "error", "no jar asset in release"); return; }

            File dest = new File(Bukkit.getUpdateFolderFile(), plugin.pluginJar().getName());
            download(jarUrl, dest);
            downloadedVersion = tag;
            notifyDownloaded(requester, tag);
        } catch (Exception e) {
            report(requester, "update-failed", "error", String.valueOf(e.getMessage()));
        }
    }

    private String httpGet(String url) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sentinel-Updater")
            .timeout(Duration.ofSeconds(15)).GET();
        String token = plugin.getConfig().getString("update.github-token", "");
        if (token != null && !token.isBlank()) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private void download(String url, File dest) throws Exception {
        dest.getParentFile().mkdirs();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Sentinel-Updater")
            .timeout(Duration.ofSeconds(60)).GET();
        String token = plugin.getConfig().getString("update.github-token", "");
        if (token != null && !token.isBlank()) req.header("Authorization", "Bearer " + token);
        HttpResponse<java.io.InputStream> resp =
            http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode());
        try (var in = resp.body()) {
            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void notifyDownloaded(CommandSender requester, String tag) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("Sentinel " + tag + " downloaded; restart to apply.");
            for (Player op : Bukkit.getOnlinePlayers())
                if (op.isOp()) op.sendMessage(plugin.messages().prefixed("update-available", "version", tag));
            if (requester instanceof Player p && !p.isOp())
                p.sendMessage(plugin.messages().prefixed("update-available", "version", tag));
        });
    }

    private void report(CommandSender requester, String key, String... placeholders) {
        if (requester == null) {
            if (key.equals("update-failed"))
                plugin.getLogger().warning("Update check failed: " + lastValue(placeholders));
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin,
            () -> requester.sendMessage(plugin.messages().prefixed(key, placeholders)));
    }

    private String lastValue(String[] kv) { return kv.length >= 2 ? kv[kv.length - 1] : ""; }
}
```

- [ ] **Step 6: Wire into `Sentinel.java`**

```java
// field
private de.derfakegamer.sentinel.updater.UpdateChecker updateChecker;

// in onEnable(), after everything else is built:
this.updateChecker = new de.derfakegamer.sentinel.updater.UpdateChecker(this);
this.updateChecker.start();

// getter
public de.derfakegamer.sentinel.updater.UpdateChecker updater() { return updateChecker; }
```

- [ ] **Step 7: Add `/sentinel update` to `command/SentinelCommand.java`**

In `onCommand`, after the `reload` branch and before the player/GUI handling, add:

```java
if (args.length == 1 && args[0].equalsIgnoreCase("update")) {
    plugin.updater().checkNow(sender);
    return true;
}
```

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests UpdateCheckerTest` and `./gradlew test --tests VersionTest`
Expected: PASS (UpdateChecker 4, Version 8). The UpdateChecker tests exercise only the pure parse + decision methods — they make no network calls.

- [ ] **Step 9: Run the FULL suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all prior tests + 12 new = full green. Shaded jar produced.

- [ ] **Step 10: Manual smoke test (real server + GitHub repo)**

1. Create a GitHub release on `derfakegameryt-rgb/sentinal-plugin` tagged `v1.0.1` with the built `Sentinel.jar` attached as an asset.
2. Start a server running the current `1.0.0` jar.
3. Within ~30 min (or run `/sentinel update`): console logs "Sentinel v1.0.1 downloaded; restart to apply" and OPs see the message.
4. Confirm `plugins/update/Sentinel.jar` now exists.
5. Restart the server → the plugin is now `1.0.1`. Run `/sentinel update` again → "Sentinel is up to date (1.0.1)".

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: GitHub Releases auto-updater and /sentinel update"
```

---

## Self-Review Notes (plan vs. spec)

- **Spec coverage (Plan 4 scope):** GitHub Releases source for `derfakegameryt-rgb/sentinal-plugin` ✓; check on startup + repeating interval (default 1800s, min 60s clamp) ✓; compare release tag (leading `v` stripped) vs running version, strictly-higher only (no downgrade) ✓; download `.jar` asset to the Bukkit update folder for next-restart apply, no hot-swap ✓; notify console + OPs ✓; optional `github-token` to raise rate limit ✓; `update.enabled` toggle ✓; `/sentinel update` on-demand ✓; failures logged, never crash the plugin (all network work wrapped in try/catch on an async task) ✓.
- **No-redownload guard:** `downloadedVersion` prevents re-downloading and re-notifying the same release every interval within a session.
- **Testability seam:** `parseLatestTag`, `parseJarDownloadUrl` (static) and `isNewer` (instance, reads plugin version) are unit-tested with sample JSON and MockBukkit — no live network in tests. HTTP/scheduling are thin wiring validated by the manual smoke test.
- **Type consistency:** new accessors `Sentinel.pluginJar()` and `Sentinel.updater()`. `UpdateChecker(Sentinel)`, `start()`, `checkNow(CommandSender)`, `isNewer(String)` used consistently.
- **GitHub API correctness:** sends `User-Agent` (required by GitHub) and `Accept: application/vnd.github+json`; follows redirects for the asset download (release asset URLs 302 to a CDN); treats non-2xx as failure.
```
