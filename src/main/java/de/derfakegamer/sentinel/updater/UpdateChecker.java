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
    // Pulls releases from the PUBLIC release repository. Public releases need no authentication, so
    // the updater carries no token and no private-repo handling. We list ALL releases and pick the
    // highest version ourselves — relying on GitHub's single "latest" flag is unreliable when
    // several tags point at the same commit (it can surface an older tag) — see parseBestRelease.
    private static final String API =
        "https://api.github.com/repos/derfakegameryt-rgb/sentinal-plugin/releases?per_page=100";

    private final Sentinel plugin;
    // NORMAL redirect following: a public asset's browser_download_url 302-redirects to a CDN URL,
    // which the client follows automatically (no auth header is ever sent, so nothing to strip).
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile String downloadedVersion; // avoid re-downloading the same release each interval
    // Guards against the 5-minute timer and an on-demand "/sentinel update" running (and writing the
    // same file) at the same time. Only one check/download is ever in flight.
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

    public UpdateChecker(Sentinel plugin) { this.plugin = plugin; }

    /** Starts the periodic update check. Always on; fixed 5-minute interval (not configurable). */
    public void start() {
        long ticks = 300L * 20L; // 5 minutes
        plugin.scheduler().asyncTimer(() -> check(null), 20L, ticks);
    }

    /** On-demand check that always reports back to the requester. */
    public void checkNow(CommandSender requester) {
        requester.sendMessage(plugin.messages().prefixed("update-checking"));
        plugin.scheduler().runAsync(() -> check(requester));
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
        return firstJarUrl(JsonParser.parseString(json).getAsJsonObject());
    }

    /** The first .jar asset's public {@code browser_download_url} in a single release object, or null. */
    private static String firstJarUrl(JsonObject release) {
        if (!release.has("assets")) return null;
        JsonArray assets = release.getAsJsonArray("assets");
        for (var element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (name.toLowerCase().endsWith(".jar")) {
                return asset.has("browser_download_url") && !asset.get("browser_download_url").isJsonNull()
                    ? asset.get("browser_download_url").getAsString() : null;
            }
        }
        return null;
    }

    /**
     * From a {@code /releases} (array) response, returns the highest-version, non-draft,
     * non-prerelease release that ships a .jar, as {@code [tag, jarUrl]} — or null if none.
     * This is what makes the updater always pick the genuinely newest version, independent of
     * GitHub's "latest" flag (which misbehaves when tags share a commit).
     */
    static String[] parseBestRelease(String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        String bestTag = null, bestUrl = null;
        for (var element : arr) {
            JsonObject rel = element.getAsJsonObject();
            if (rel.has("draft") && rel.get("draft").getAsBoolean()) continue;
            if (rel.has("prerelease") && rel.get("prerelease").getAsBoolean()) continue;
            String tag = rel.has("tag_name") ? rel.get("tag_name").getAsString() : null;
            if (tag == null) continue;
            String jar = firstJarUrl(rel);
            if (jar == null) continue;
            if (bestTag == null || Version.isNewer(tag, bestTag)) { bestTag = tag; bestUrl = jar; }
        }
        return bestTag == null ? null : new String[]{bestTag, bestUrl};
    }

    /** Network check (runs async). Downloads if newer; notifies the requester (or staff on a scheduled run). */
    private void check(CommandSender requester) {
        if (!running.compareAndSet(false, true)) {
            // Another check (the timer or a prior on-demand request) is already running.
            if (requester != null) report(requester, "update-failed", "error", "a check is already in progress");
            return;
        }
        try {
            String body = httpGet(API);
            String[] best = parseBestRelease(body);
            if (best == null) { report(requester, "update-failed", "error", "no downloadable release found"); return; }
            String tag = best[0];
            String jarUrl = best[1];

            if (!isNewer(tag)) {
                if (requester != null) report(requester, "update-up-to-date",
                    "version", plugin.getPluginMeta().getVersion());
                return;
            }
            if (tag.equals(downloadedVersion)) return; // already downloaded this release this session

            File dest = new File(Bukkit.getUpdateFolderFile(), plugin.pluginJar().getName());
            download(jarUrl, dest);
            downloadedVersion = tag;
            notifyDownloaded(requester, tag);
        } catch (Exception e) {
            report(requester, "update-failed", "error", String.valueOf(e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sentinel-Updater")
            .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code == 404)
            throw new IllegalStateException("release feed not found (HTTP 404)");
        if (code == 403 || code == 429)
            throw new IllegalStateException("rate limited by GitHub (HTTP " + code + ") — transient");
        if (code / 100 != 2) throw new IllegalStateException("HTTP " + code);
        return resp.body();
    }

    /**
     * Downloads a public release asset. The download goes into a sibling temp file, is validated,
     * then atomically moved into place — a dropped connection or a bad response can never leave a
     * truncated/corrupt jar in the update folder (which Bukkit would try to apply on the next restart).
     */
    private void download(String url, File dest) throws Exception {
        File dir = dest.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = File.createTempFile(dest.getName() + "-", ".part", dir);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Sentinel-Updater")
                .timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<java.io.InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream()); // follows the CDN redirect
            if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode());
            try (var in = resp.body()) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            validateJar(tmp); // reject corrupt/incomplete/wrong artifacts before they reach the update folder
            moveIntoPlace(tmp, dest);
        } finally {
            Files.deleteIfExists(tmp.toPath()); // no-op once the move succeeded
        }
    }

    /** Atomically replaces {@code dest} with {@code tmp}; falls back to a plain replace if the
     *  filesystem can't do an atomic move (e.g. some Windows setups). */
    private static void moveIntoPlace(File tmp, File dest) throws Exception {
        try {
            Files.move(tmp.toPath(), dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Rejects anything that is not a genuine Sentinel plugin jar — a truncated download, an HTML
     * error page served with HTTP 200, or the wrong asset. Throws if the file is too small, is not
     * a valid zip/jar, has no {@code plugin.yml}, or that {@code plugin.yml} is not Sentinel's.
     */
    static void validateJar(File f) throws Exception {
        if (f.length() < 1000)
            throw new IllegalStateException("downloaded file is too small (" + f.length() + " bytes) — incomplete download");
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(f)) { // throws on a non-zip/corrupt file
            java.util.zip.ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null)
                throw new IllegalStateException("downloaded jar has no plugin.yml — not a plugin jar");
            try (java.io.InputStream in = jar.getInputStream(entry)) {
                String yml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!yml.contains("name: Sentinel"))
                    throw new IllegalStateException("downloaded jar is not the Sentinel plugin");
            }
        }
    }

    private void notifyDownloaded(CommandSender requester, String tag) {
        // Auto-update (scheduled run, requester == null) is invisible: download silently, no console
        // line and no player notifications. Only an explicit "/sentinel update" reports back.
        if (requester == null) return;
        plugin.scheduler().runGlobal(() -> {
            if (requester instanceof Player p)
                p.sendMessage(plugin.messages().prefixed("update-available", "version", tag));
            else
                plugin.getLogger().info("Sentinel " + tag + " downloaded; restart to apply.");
        });
    }

    private void report(CommandSender requester, String key, String... placeholders) {
        if (requester == null) {
            // Scheduled run (every 5 minutes): NEVER log failures to the console — that would
            // spam the log on any persistent issue (offline, 404, rate limit, parse error).
            // Failures are kept at FINE so they're available with debug logging but invisible
            // by default. Run "/sentinel update" to see the real reason on demand.
            if (key.equals("update-failed"))
                plugin.getLogger().fine("Update check skipped: " + lastValue(placeholders));
            return;
        }
        plugin.scheduler().runGlobal(
            () -> requester.sendMessage(plugin.messages().prefixed(key, placeholders)));
    }

    private String lastValue(String[] kv) { return kv.length >= 2 ? kv[kv.length - 1] : ""; }
}
