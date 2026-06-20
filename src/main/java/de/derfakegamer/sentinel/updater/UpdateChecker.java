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
    // List ALL releases and pick the highest version ourselves. Relying on GitHub's single
    // "latest" flag is unreliable when several release tags point at the same commit (it can
    // surface an older tag like 1.0.0 instead of the newest) — see parseBestRelease.
    private static final String API =
        "https://api.github.com/repos/derfakegameryt-rgb/sentinal-plugin/releases?per_page=100";

    private final Sentinel plugin;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    // Used for the asset download's first hop: we must NOT auto-follow, because GitHub redirects
    // a private asset to a pre-signed CDN URL that rejects the Authorization header. We follow the
    // redirect manually with the auth header stripped.
    private final HttpClient noFollow = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile String downloadedVersion; // avoid re-downloading the same release each interval

    public UpdateChecker(Sentinel plugin) { this.plugin = plugin; }

    /** Starts the periodic update check. Always on; fixed 5-minute interval (not configurable). */
    public void start() {
        long ticks = 300L * 20L; // 5 minutes
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
        return firstJarUrl(JsonParser.parseString(json).getAsJsonObject());
    }

    /**
     * The first .jar asset's download URL in a single release object, or null.
     * Prefers the asset's API {@code url} (e.g. {@code .../releases/assets/123}) over
     * {@code browser_download_url}: the API URL, fetched with an {@code Accept:
     * application/octet-stream} header and a token, downloads assets from BOTH public and
     * private repositories, whereas {@code browser_download_url} does not work for private
     * repos via a token. Falls back to {@code browser_download_url} when no API URL is present.
     */
    private static String firstJarUrl(JsonObject release) {
        if (!release.has("assets")) return null;
        JsonArray assets = release.getAsJsonArray("assets");
        for (var element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (name.toLowerCase().endsWith(".jar")) {
                if (asset.has("url") && !asset.get("url").isJsonNull())
                    return asset.get("url").getAsString();
                return asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : null;
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
        }
    }

    /** The configured GitHub token (PAT), or empty string if none. */
    private String githubToken() {
        String token = plugin.getConfig().getString("update.github-token", "");
        return token == null ? "" : token.trim();
    }

    private String httpGet(String url) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sentinel-Updater")
            .timeout(Duration.ofSeconds(15)).GET();
        String token = githubToken();
        if (!token.isBlank()) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code == 401)
            throw new IllegalStateException("GitHub rejected the token (HTTP 401) — check update.github-token");
        if (code == 404)
            throw new IllegalStateException("release feed not found (HTTP 404) — for a private repo, set "
                + "update.github-token to a PAT with 'Contents: read' on this repo");
        if (code == 403 || code == 429)
            throw new IllegalStateException("rate limited by GitHub (HTTP " + code
                + ") — transient; optionally set update.github-token to raise the limit");
        if (code / 100 != 2) throw new IllegalStateException("HTTP " + code);
        return resp.body();
    }

    /**
     * Downloads an asset. Works for public AND private repos: requests the asset (API URL) with
     * {@code Accept: application/octet-stream} and the token, then follows the redirect to the
     * pre-signed CDN URL manually WITH THE AUTHORIZATION HEADER STRIPPED — the CDN rejects requests
     * that carry both a signature and an Authorization header.
     */
    private void download(String url, File dest) throws Exception {
        dest.getParentFile().mkdirs();
        String token = githubToken();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Sentinel-Updater")
            .header("Accept", "application/octet-stream")
            .timeout(Duration.ofSeconds(60)).GET();
        if (!token.isBlank()) req.header("Authorization", "Bearer " + token);

        HttpResponse<java.io.InputStream> resp =
            noFollow.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            try (var ignored = resp.body()) { /* drain/close the redirect response */ }
            String location = resp.headers().firstValue("location")
                .orElseThrow(() -> new IllegalStateException("asset redirect without Location header"));
            // No Authorization here: the CDN URL is pre-signed and rejects a second auth mechanism.
            HttpRequest redirect = HttpRequest.newBuilder(URI.create(location))
                .header("User-Agent", "Sentinel-Updater")
                .timeout(Duration.ofSeconds(60)).GET().build();
            resp = http.send(redirect, HttpResponse.BodyHandlers.ofInputStream());
            code = resp.statusCode();
        }
        if (code / 100 != 2) throw new IllegalStateException("HTTP " + code);
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
            // Scheduled run (every 5 minutes): NEVER log failures to the console — that would
            // spam the log on any persistent issue (offline, 404, rate limit, parse error).
            // Failures are kept at FINE so they're available with debug logging but invisible
            // by default. Run "/sentinel update" to see the real reason on demand.
            if (key.equals("update-failed"))
                plugin.getLogger().fine("Update check skipped: " + lastValue(placeholders));
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin,
            () -> requester.sendMessage(plugin.messages().prefixed(key, placeholders)));
    }

    private String lastValue(String[] kv) { return kv.length >= 2 ? kv[kv.length - 1] : ""; }
}
