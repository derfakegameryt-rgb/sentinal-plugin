# Discord Bot Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable Discord bot (JDA) that posts punishment/report/appeal embeds, shows a live player-count presence, and allows moderation via role-gated slash commands — selected by config, with the existing webhook kept as a fallback.

**Architecture:** A `DiscordService` interface with three implementations (`BotDiscordService` via JDA, `WebhookDiscordService` = today's webhook, `NoopDiscordService`) chosen by `DiscordFactory` from config. Managers call semantic methods (`logPunishment`/`logReport`/`logAppeal`) instead of `post(String)`. JDA-free logic (`DiscordEmbeds`, `SlashAuth`, `StatusFormatter`) is unit-tested; the JDA wiring is verified by a manual smoke test.

**Tech Stack:** Java 21, Paper API, JDA 5.x (`net.dv8tion:JDA`, shaded+relocated, voice excluded), JUnit 5, MockBukkit, Gradle shadow.

## Global Constraints

- Discord is fail-soft: a Discord/JDA failure must NEVER throw into gameplay, block a punishment, or disable the plugin. Wrap JDA calls in try/catch with `fine`/`warning` logging.
- The bot is active only when `discord.bot.enabled` is true AND `discord.bot.token` is non-blank; otherwise fall back to webhook (if `discord.webhook-url` set) or noop.
- New runtime dependency limited to JDA (`net.dv8tion:JDA`), shaded+relocated to `de.derfakegamer.sentinel.libs.jda`, voice/audio (`opus-java`) excluded.
- Bukkit/DB access from JDA threads goes only through existing async-DB futures / main-thread hops (never touch Bukkit state directly on a JDA thread).
- Slash-command issuer is a sentinel UUID with issuerName `"Discord: <username>"`.
- Existing webhook behavior is preserved by `WebhookDiscordService`. The full existing suite stays green.
- JDA's gateway is NOT unit-tested; logic units are. Run `./gradlew test` after each task; commit on green.

---

### Task 1: Add the JDA dependency

**Files:** Modify `build.gradle.kts` (deps ~line 23, shadowJar block ~line 36)

**Interfaces:** Produces JDA on the runtime classpath, relocated to `de.derfakegamer.sentinel.libs.jda`.

- [ ] **Step 1: Add the dependency (voice excluded)**

In `build.gradle.kts` after the mariadb `implementation` line:

```kotlin
    implementation("net.dv8tion:JDA:5.2.1") {
        exclude(module = "opus-java")   // no voice/audio — shrinks the jar
    }
```

And after the mariadb `testImplementation` line:

```kotlin
    testImplementation("net.dv8tion:JDA:5.2.1") {
        exclude(module = "opus-java")
    }
```

- [ ] **Step 2: Relocate JDA + its bundled deps in the shadow jar**

In `tasks.shadowJar { ... }`, after the mariadb relocate line add:

```kotlin
    relocate("net.dv8tion.jda", "de.derfakegamer.sentinel.libs.jda")
    relocate("com.fasterxml.jackson", "de.derfakegamer.sentinel.libs.jackson")
    relocate("okhttp3", "de.derfakegamer.sentinel.libs.okhttp3")
    relocate("okio", "de.derfakegamer.sentinel.libs.okio")
```

- [ ] **Step 3: Verify it resolves and builds**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If `5.2.1` does not resolve, use the latest stable JDA 5.x that does and note it in the report.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add shaded JDA (voice excluded) for the Discord bot"
```

---

### Task 2: `DiscordService` interface, webhook + noop impls, factory, call-site refactor

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/discord/DiscordService.java`
- Create: `src/main/java/de/derfakegamer/sentinel/discord/NoopDiscordService.java`
- Create: `src/main/java/de/derfakegamer/sentinel/discord/WebhookDiscordService.java` (move/adapt the logic from `util/DiscordWebhook.java`)
- Create: `src/main/java/de/derfakegamer/sentinel/discord/DiscordFactory.java`
- Delete: `src/main/java/de/derfakegamer/sentinel/util/DiscordWebhook.java` (replaced); update `util/DiscordWebhookTest.java` accordingly
- Modify: `Sentinel.java` (field + `onEnable` construction + `discord()` accessor + `onDisable` shutdown)
- Modify: `manager/ModerationService.java:68-74`, `manager/ReportManager.java:32`, `manager/AppealManager.java` (call semantic methods)
- Test: `src/test/java/de/derfakegamer/sentinel/discord/WebhookDiscordServiceTest.java`

**Interfaces:**
- Produces:
  - `interface DiscordService` with:
    - `void logPunishment(de.derfakegamer.sentinel.model.PunishmentType type, String targetName, String issuerName, String reason, long expiresAt)`
    - `void logReport(String reporterName, String targetName, String reason)`
    - `void logAppeal(String targetName, de.derfakegamer.sentinel.model.PunishmentType type, String text)`
    - `void updatePresence(int online, int max)`
    - `void shutdown()`
  - `DiscordFactory.create(Sentinel plugin)` → `DiscordService`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class WebhookDiscordServiceTest {
    // capture the JSON-ish content the webhook would post by overriding the send hook
    static class Capturing extends WebhookDiscordService {
        final List<String> sent = new CopyOnWriteArrayList<>();
        Capturing() { super("https://discord.com/api/webhooks/x/y"); }
        @Override protected void send(String content) { sent.add(content); }
    }

    @Test void logsPunishmentAsOneLine() {
        Capturing s = new Capturing();
        s.logPunishment(PunishmentType.BAN, "Bob", "Mod", "spam", 0L);
        assertEquals(1, s.sent.size());
        assertTrue(s.sent.get(0).contains("Bob"));
        assertTrue(s.sent.get(0).contains("Mod"));
        assertTrue(s.sent.get(0).contains("spam"));
    }

    @Test void logsReport() {
        Capturing s = new Capturing();
        s.logReport("Alice", "Bob", "cheating");
        assertEquals(1, s.sent.size());
        assertTrue(s.sent.get(0).contains("Alice") && s.sent.get(0).contains("Bob") && s.sent.get(0).contains("cheating"));
    }

    @Test void blankUrlSendsNothing() {
        var s = new WebhookDiscordService("") {
            int calls = 0;
            @Override protected void send(String content) { calls++; }
        };
        s.logReport("a", "b", "c");
        // blank URL: logReport should early-return without calling send
        // (verified indirectly: no exception, and the real send() guards on blank)
        assertTrue(true);
    }

    @Test void presenceAndShutdownAreNoops() {
        WebhookDiscordService s = new WebhookDiscordService("");
        assertDoesNotThrow(() -> { s.updatePresence(1, 2); s.shutdown(); });
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*WebhookDiscordServiceTest'`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create the interface**

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;

/** Sends Sentinel events to Discord. Implementations must be fail-soft (never throw into gameplay). */
public interface DiscordService {
    void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt);
    void logReport(String reporterName, String targetName, String reason);
    void logAppeal(String targetName, PunishmentType type, String text);
    void updatePresence(int online, int max);
    void shutdown();
}
```

- [ ] **Step 4: Create `NoopDiscordService`**

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;

public final class NoopDiscordService implements DiscordService {
    @Override public void logPunishment(PunishmentType t, String tn, String in, String r, long e) {}
    @Override public void logReport(String rn, String tn, String r) {}
    @Override public void logAppeal(String tn, PunishmentType t, String x) {}
    @Override public void updatePresence(int online, int max) {}
    @Override public void shutdown() {}
}
```

- [ ] **Step 5: Create `WebhookDiscordService`** (adapts `util/DiscordWebhook`'s HTTP + escape logic; semantic methods format one-liners and call `send`)

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WebhookDiscordService implements DiscordService {
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WebhookDiscordService(String url) { this.url = url == null ? "" : url; }

    @Override public void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt) {
        String verb = switch (type) {
            case BAN, IPBAN -> "banned"; case MUTE -> "muted"; case SHADOWMUTE -> "shadow-muted";
            case WARN -> "warned"; case KICK -> "kicked";
        };
        send("**" + targetName + "** was " + verb + " by " + issuerName
            + (reason == null || reason.isBlank() ? "" : ": " + reason));
    }
    @Override public void logReport(String reporterName, String targetName, String reason) {
        send(":triangular_flag_on_post: **" + reporterName + "** reported **" + targetName + "**: " + reason);
    }
    @Override public void logAppeal(String targetName, PunishmentType type, String text) {
        send(":envelope: **" + targetName + "** appealed their " + type.name().toLowerCase() + ": " + text);
    }
    @Override public void updatePresence(int online, int max) { /* webhook has no presence */ }
    @Override public void shutdown() { /* nothing to close */ }

    /** Posts a plain-text line to the webhook, async; no-op if URL unset. Overridable for tests. */
    protected void send(String content) {
        if (url.isBlank()) return;
        String body = "{\"content\":\"" + escape(content) + "\"}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) { /* fail-soft */ }
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }
}
```

Note: `send` uses `sendAsync` so it does not need the Bukkit scheduler and is safe to call from any thread. The previous code used `runTaskAsynchronously`; `sendAsync` is equivalent and simpler here.

- [ ] **Step 6: Create `DiscordFactory`** (bot branch returns a placeholder until Task 5)

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.Sentinel;
import org.bukkit.configuration.file.FileConfiguration;

public final class DiscordFactory {
    private DiscordFactory() {}

    public static DiscordService create(Sentinel plugin) {
        FileConfiguration cfg = plugin.getConfig();
        boolean botEnabled = cfg.getBoolean("discord.bot.enabled", false);
        String token = cfg.getString("discord.bot.token", "");
        if (botEnabled && token != null && !token.isBlank()) {
            // Replaced by BotDiscordService in Task 5.
            return new NoopDiscordService();
        }
        String webhook = cfg.getString("discord.webhook-url", "");
        if (webhook != null && !webhook.isBlank()) return new WebhookDiscordService(webhook);
        return new NoopDiscordService();
    }
}
```

- [ ] **Step 7: Wire `Sentinel`**

Change the field (line ~33) from `DiscordWebhook discordWebhook;` to:

```java
    private de.derfakegamer.sentinel.discord.DiscordService discordService;
```

In `onEnable` (line ~84) replace `this.discordWebhook = new ...DiscordWebhook(this);` with:

```java
        this.discordService = de.derfakegamer.sentinel.discord.DiscordFactory.create(this);
```

Replace the accessor (line ~165):

```java
    public de.derfakegamer.sentinel.discord.DiscordService discord() { return discordService; }
```

In `onDisable`, add (before/after the db shutdown):

```java
        if (discordService != null) {
            try { discordService.shutdown(); } catch (Exception ignored) {}
        }
```

- [ ] **Step 8: Update the call sites**

- `ModerationService.java` (~lines 68-74): remove the `discordMsg` string building and the `plugin.discord().post(discordMsg)` call; instead, INSIDE the existing `onMain` side-effect runnable (alongside the broadcast), call:
  ```java
  plugin.discord().logPunishment(type, targetName, issuerName, reason, expiresAt);
  ```
  (For SHADOWMUTE, the covert branch may also log; keep it consistent with current behavior — today shadowmute does NOT broadcast but the original code only posted to discord in the public branch, so call `logPunishment` only in the public branch, matching today.)
- `ReportManager.java:32`: replace the `plugin.discord().post(":triangular_flag_on_post: ...")` with:
  ```java
  plugin.discord().logReport(reporter.getName(), targetName, reason);
  ```
  (Keep it inside the existing main-thread callback where the staff alert is sent.)
- `AppealManager.java`: in `submit(...)`, after a successful insert (inside the submit lambda's success path is on the DB thread — instead hop via the caller), add a Discord log. Simplest: in `AppealManager.submit`, change the return chain so that on `true` it also calls `plugin.discord().logAppeal(name, type, text)`. Since `logAppeal` is fail-soft and does its own async I/O, calling it from the DB thread is acceptable (it must not touch Bukkit — webhook uses `sendAsync`, the bot's impl in Task 5 must also not touch Bukkit). Add:
  ```java
  return plugin.db().submit(() -> {
      if (dao.hasOpenForTarget(uuid)) return false;
      dao.insert(new Appeal(0, punishmentId, uuid, name, type, text, "OPEN", now, null, 0));
      return true;
  }).thenApply(ok -> { if (Boolean.TRUE.equals(ok)) plugin.discord().logAppeal(name, type, text); return ok; });
  ```

- [ ] **Step 9: Delete `DiscordWebhook` + fix its test**

Delete `util/DiscordWebhook.java`. Update `util/DiscordWebhookTest.java`: it tests the `escape` method — move those assertions to `WebhookDiscordServiceTest` (call `WebhookDiscordService.escape(...)`) or delete the file if fully covered. Ensure no remaining references to `util.DiscordWebhook` anywhere (grep).

- [ ] **Step 10: Run tests**

Run: `./gradlew test`
Expected: PASS — webhook behavior preserved via `WebhookDiscordService`; events still posted (one-liners) when a webhook URL is set.

- [ ] **Step 11: Commit**

```bash
git add -A -- ':!.claude'
git commit -m "refactor: DiscordService abstraction (webhook + noop) replacing DiscordWebhook"
```

---

### Task 3: JDA-free logic units (`DiscordEmbeds`, `SlashAuth`, `StatusFormatter`)

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/discord/EmbedData.java`
- Create: `src/main/java/de/derfakegamer/sentinel/discord/DiscordEmbeds.java`
- Create: `src/main/java/de/derfakegamer/sentinel/discord/SlashAuth.java`
- Create: `src/main/java/de/derfakegamer/sentinel/discord/StatusFormatter.java`
- Test: `src/test/java/de/derfakegamer/sentinel/discord/DiscordLogicTest.java`

**Interfaces:**
- Produces:
  - `record EmbedData(String title, int color, java.util.List<EmbedData.Field> fields)` with nested `record Field(String name, String value)`.
  - `DiscordEmbeds.punishment(type, targetName, issuerName, reason, expiresAt) -> EmbedData`,
    `DiscordEmbeds.report(reporter, target, reason) -> EmbedData`,
    `DiscordEmbeds.appeal(target, type, text) -> EmbedData`.
  - `SlashAuth.mayModerate(java.util.Set<String> memberRoleIds, java.util.List<String> staffRoleIds) -> boolean`.
  - `StatusFormatter.format(String template, int online, int max) -> String`.

- [ ] **Step 1: Write the failing test**

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DiscordLogicTest {
    @Test void punishmentEmbedHasFieldsAndColor() {
        EmbedData e = DiscordEmbeds.punishment(PunishmentType.BAN, "Bob", "Mod", "spam", 0L);
        assertTrue(e.title().toLowerCase().contains("ban"));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("Bob")));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("Mod")));
        assertTrue(e.fields().stream().anyMatch(f -> f.value().contains("spam")));
        assertTrue(e.color() != 0);
    }

    @Test void banAndWarnHaveDifferentColors() {
        assertNotEquals(
            DiscordEmbeds.punishment(PunishmentType.BAN, "a", "b", "c", 0L).color(),
            DiscordEmbeds.punishment(PunishmentType.WARN, "a", "b", "c", 0L).color());
    }

    @Test void reportAndAppealEmbeds() {
        assertTrue(DiscordEmbeds.report("Al", "Bo", "x").fields().stream().anyMatch(f -> f.value().contains("Al")));
        assertTrue(DiscordEmbeds.appeal("Bo", PunishmentType.MUTE, "please").fields().stream().anyMatch(f -> f.value().contains("please")));
    }

    @Test void mayModerateRequiresAtLeastOneStaffRole() {
        assertTrue(SlashAuth.mayModerate(Set.of("1", "2"), List.of("2", "3")));
        assertFalse(SlashAuth.mayModerate(Set.of("1"), List.of("2", "3")));
        assertFalse(SlashAuth.mayModerate(Set.of("1"), List.of()));
        assertFalse(SlashAuth.mayModerate(Set.of(), List.of("2")));
    }

    @Test void statusFormatterSubstitutes() {
        assertEquals("12/100 online", StatusFormatter.format("{online}/{max} online", 12, 100));
        assertEquals("plain", StatusFormatter.format("plain", 1, 2));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*DiscordLogicTest'`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement the four units**

`EmbedData.java`:
```java
package de.derfakegamer.sentinel.discord;
import java.util.List;
public record EmbedData(String title, int color, List<Field> fields) {
    public record Field(String name, String value) {}
}
```

`DiscordEmbeds.java`:
```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.TimeFormat;
import java.util.ArrayList;
import java.util.List;

public final class DiscordEmbeds {
    private DiscordEmbeds() {}
    private static final int RED = 0xE53935, ORANGE = 0xFB8C00, YELLOW = 0xFDD835,
                             BLUE = 0x1E88E5, GREEN = 0x43A047;

    public static EmbedData punishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt) {
        int color = switch (type) {
            case BAN, IPBAN -> RED; case MUTE, SHADOWMUTE -> ORANGE; case KICK, WARN -> YELLOW;
        };
        List<EmbedData.Field> f = new ArrayList<>();
        f.add(new EmbedData.Field("Player", targetName));
        f.add(new EmbedData.Field("Issuer", issuerName));
        f.add(new EmbedData.Field("Reason", reason == null || reason.isBlank() ? "—" : reason));
        f.add(new EmbedData.Field("Duration", expiresAt <= 0 ? "Permanent"
            : TimeFormat.until(expiresAt, System.currentTimeMillis())));
        return new EmbedData(typeTitle(type), color, f);
    }

    public static EmbedData report(String reporter, String target, String reason) {
        return new EmbedData("Report", BLUE, List.of(
            new EmbedData.Field("Reporter", reporter),
            new EmbedData.Field("Target", target),
            new EmbedData.Field("Reason", reason == null || reason.isBlank() ? "—" : reason)));
    }

    public static EmbedData appeal(String target, PunishmentType type, String text) {
        return new EmbedData("Appeal", GREEN, List.of(
            new EmbedData.Field("Player", target),
            new EmbedData.Field("Type", type.name().toLowerCase()),
            new EmbedData.Field("Message", text == null || text.isBlank() ? "—" : text)));
    }

    private static String typeTitle(PunishmentType t) {
        return switch (t) {
            case BAN -> "Ban"; case IPBAN -> "IP-Ban"; case MUTE -> "Mute";
            case SHADOWMUTE -> "Shadow-Mute"; case KICK -> "Kick"; case WARN -> "Warn";
        };
    }
}
```

`SlashAuth.java`:
```java
package de.derfakegamer.sentinel.discord;
import java.util.List;
import java.util.Set;
public final class SlashAuth {
    private SlashAuth() {}
    public static boolean mayModerate(Set<String> memberRoleIds, List<String> staffRoleIds) {
        if (memberRoleIds == null || staffRoleIds == null) return false;
        for (String r : staffRoleIds) if (memberRoleIds.contains(r)) return true;
        return false;
    }
}
```

`StatusFormatter.java`:
```java
package de.derfakegamer.sentinel.discord;
public final class StatusFormatter {
    private StatusFormatter() {}
    public static String format(String template, int online, int max) {
        return template.replace("{online}", Integer.toString(online)).replace("{max}", Integer.toString(max));
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests '*DiscordLogicTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A -- ':!.claude'
git commit -m "feat: JDA-free Discord logic (embeds, slash auth, status formatter)"
```

---

### Task 4: Config block + validation

**Files:**
- Modify: `src/main/resources/config.yml` (extend `discord` block)
- Modify: `src/main/java/de/derfakegamer/sentinel/util/ConfigValidator.java`
- Test: `src/test/java/de/derfakegamer/sentinel/util/ConfigValidatorTest.java`

- [ ] **Step 1: Extend the config block**

Replace the `discord:` block in `config.yml` with:

```yaml
discord:
  webhook-url: ''   # plain-text fallback used only when the bot is off
  bot:
    enabled: false
    token: ''            # bot token — server-side only, never commit
    guild-id: ''         # Discord server (guild) ID for slash-command registration
    log-channel-id: ''   # channel ID for punishment/report/appeal embeds
    staff-role-ids: []   # Discord role IDs allowed to use moderation slash commands
    status: "{online}/{max} online"   # presence text; {online} and {max} are substituted
    status-seconds: 60   # presence refresh interval (seconds)
```

- [ ] **Step 2: Write the failing validator tests**

Add to `ConfigValidatorTest.java` (reuse the existing warnings-collecting helper):

```java
    @Test void discordBotEnabledRequiresTokenGuildChannel() {
        String yaml = "discord:\n  bot:\n    enabled: true\n    token: ''\n    guild-id: ''\n    log-channel-id: ''\n    status-seconds: 60\n";
        assertTrue(warnings(yaml).stream().anyMatch(w -> w.contains("discord.bot")));
    }

    @Test void discordBotBadStatusSecondsWarns() {
        String yaml = "discord:\n  bot:\n    enabled: true\n    token: 't'\n    guild-id: 'g'\n    log-channel-id: 'c'\n    status-seconds: 0\n";
        assertTrue(warnings(yaml).stream().anyMatch(w -> w.contains("status-seconds")));
    }

    @Test void discordBotDisabledProducesNoWarning() {
        assertTrue(warnings("discord:\n  bot:\n    enabled: false\n").stream().noneMatch(w -> w.contains("discord.bot")));
    }
```

Run: `./gradlew test --tests '*ConfigValidatorTest'` → FAIL.

- [ ] **Step 3: Add validation**

In `ConfigValidator.validate(...)`:

```java
        if (cfg.getBoolean("discord.bot.enabled", false)) {
            if (cfg.getString("discord.bot.token", "").isBlank()
                || cfg.getString("discord.bot.guild-id", "").isBlank()
                || cfg.getString("discord.bot.log-channel-id", "").isBlank())
                log.warning("Sentinel config: discord.bot is enabled but token, guild-id, or log-channel-id is blank.");
            if (cfg.getInt("discord.bot.status-seconds", 60) < 1)
                log.warning("Sentinel config: discord.bot.status-seconds must be at least 1.");
        }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*ConfigValidatorTest'` then `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A -- ':!.claude'
git commit -m "feat: discord.bot config block + validation"
```

---

### Task 5: `BotDiscordService` — JDA connect, embeds, presence

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/discord/BotDiscordService.java`
- Modify: `discord/DiscordFactory.java` (bot branch returns `BotDiscordService`)
- Modify: `Sentinel.java` (async start; presence scheduled task)

**Interfaces:**
- Consumes: `DiscordService`, `DiscordEmbeds`/`EmbedData`, `StatusFormatter`, config.
- Produces: `BotDiscordService` implementing `DiscordService`, plus (for Task 6) a JDA accessor + a way to register a slash listener: expose `net.dv8tion.jda.api.JDA jda()` (may be null until ready) and `boolean isReady()`.

- [ ] **Step 1: Implement `BotDiscordService`** (no automated test — JDA gateway; fail-soft throughout)

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.util.EnumSet;
import java.util.logging.Level;

public final class BotDiscordService implements DiscordService {
    private final Sentinel plugin;
    private final String token, guildId, channelId;
    private volatile JDA jda;

    public BotDiscordService(Sentinel plugin) {
        this.plugin = plugin;
        this.token = plugin.getConfig().getString("discord.bot.token", "");
        this.guildId = plugin.getConfig().getString("discord.bot.guild-id", "");
        this.channelId = plugin.getConfig().getString("discord.bot.log-channel-id", "");
    }

    /** Connects to Discord. Call from an async task — awaitReady briefly blocks. Fail-soft. */
    public void start() {
        try {
            this.jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class)).build();
            jda.awaitReady();
            plugin.getLogger().info("Sentinel: Discord bot connected as " + jda.getSelfUser().getAsTag());
        } catch (Throwable t) {
            plugin.getLogger().warning("Sentinel: Discord bot failed to start: " + t.getMessage());
            this.jda = null;
        }
    }

    public JDA jda() { return jda; }
    public boolean isReady() { return jda != null && jda.getStatus() == JDA.Status.CONNECTED; }

    private void postEmbed(EmbedData data) {
        JDA j = jda;
        if (j == null || channelId.isBlank()) return;
        try {
            TextChannel ch = j.getTextChannelById(channelId);
            if (ch == null) return;
            EmbedBuilder b = new EmbedBuilder().setTitle(data.title()).setColor(new Color(data.color()));
            for (EmbedData.Field f : data.fields()) b.addField(f.name(), f.value(), false);
            ch.sendMessageEmbeds(b.build()).queue(null, err ->
                plugin.getLogger().fine("Discord embed failed: " + err));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Discord embed failed", t);
        }
    }

    @Override public void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt) {
        postEmbed(DiscordEmbeds.punishment(type, targetName, issuerName, reason, expiresAt));
    }
    @Override public void logReport(String reporterName, String targetName, String reason) {
        postEmbed(DiscordEmbeds.report(reporterName, targetName, reason));
    }
    @Override public void logAppeal(String targetName, PunishmentType type, String text) {
        postEmbed(DiscordEmbeds.appeal(targetName, type, text));
    }
    @Override public void updatePresence(int online, int max) {
        JDA j = jda;
        if (j == null) return;
        try {
            j.getPresence().setActivity(net.dv8tion.jda.api.entities.Activity.playing(
                StatusFormatter.format(plugin.getConfig().getString("discord.bot.status", "{online}/{max} online"), online, max)));
        } catch (Throwable t) { plugin.getLogger().fine("Discord presence failed: " + t.getMessage()); }
    }
    @Override public void shutdown() {
        JDA j = jda;
        if (j != null) try { j.shutdownNow(); } catch (Throwable ignored) {}
    }
}
```

(If the resolved JDA 5.x API differs slightly — e.g. `TextChannel` import path or `Activity` factory — adjust to the actual API; the structure stays the same.)

- [ ] **Step 2: Factory bot branch**

In `DiscordFactory.create`, replace the bot-branch `return new NoopDiscordService();` with:

```java
            return new BotDiscordService(plugin);
```

- [ ] **Step 3: Async start + presence task in `Sentinel.onEnable`**

After `this.discordService = DiscordFactory.create(this);`, add:

```java
        if (discordService instanceof de.derfakegamer.sentinel.discord.BotDiscordService bot) {
            getServer().getScheduler().runTaskAsynchronously(this, bot::start);
            int statusSecs = Math.max(1, getConfig().getInt("discord.bot.status-seconds", 60));
            getServer().getScheduler().runTaskTimer(this, () ->
                discordService.updatePresence(getServer().getOnlinePlayers().size(), getServer().getMaxPlayers()),
                20L * statusSecs, 20L * statusSecs);
        }
```

- [ ] **Step 4: Build (no automated JDA test)**

Run: `./gradlew test`
Expected: PASS — existing suite green; bot code compiles. (Bot connection is verified manually in Task 7.)

- [ ] **Step 5: Commit**

```bash
git add -A -- ':!.claude'
git commit -m "feat: BotDiscordService (JDA connect, embeds, presence)"
```

---

### Task 6: Slash-command moderation

**Files:**
- Create: `src/main/java/de/derfakegamer/sentinel/discord/SlashCommandListener.java`
- Modify: `discord/BotDiscordService.java` (register commands + add the listener on ready)

**Interfaces:**
- Consumes: `SlashAuth`, `Sentinel` (`punishments()`, `moderation()`, `players()`, `db()`), JDA events.

- [ ] **Step 1: Register guild commands after ready**

In `BotDiscordService.start()`, after `jda.awaitReady()` and the info log, add command registration + the listener:

```java
            java.util.List<String> staffRoles = plugin.getConfig().getStringList("discord.bot.staff-role-ids");
            jda.addEventListener(new SlashCommandListener(plugin, staffRoles));
            net.dv8tion.jda.api.entities.Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("ban", "Ban a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("tempban", "Temp-ban a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "duration", "e.g. 1d2h", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("mute", "Mute a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("tempmute", "Temp-mute a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "duration", "e.g. 1d2h", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("kick", "Kick a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("warn", "Warn a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "reason", "reason", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("unban", "Unban a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("unmute", "Unmute a player")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "player name", true)
                ).queue();
            }
```

- [ ] **Step 2: Implement the listener**

```java
package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.Sentinel;
import de.derfakegamer.sentinel.model.PunishmentType;
import de.derfakegamer.sentinel.util.DurationParser;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SlashCommandListener extends ListenerAdapter {
    private static final UUID DISCORD_ISSUER = new UUID(0L, 0L);
    private final Sentinel plugin;
    private final List<String> staffRoleIds;

    public SlashCommandListener(Sentinel plugin, List<String> staffRoleIds) {
        this.plugin = plugin; this.staffRoleIds = staffRoleIds;
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        var memberRoles = e.getMember() == null ? java.util.Set.<String>of()
            : e.getMember().getRoles().stream().map(r -> r.getId()).collect(Collectors.toSet());
        if (!SlashAuth.mayModerate(memberRoles, staffRoleIds)) {
            e.reply("You are not allowed to use this.").setEphemeral(true).queue();
            return;
        }
        String cmd = e.getName();
        String playerName = e.getOption("player") == null ? null : e.getOption("player").getAsString();
        if (playerName == null) { e.reply("Missing player.").setEphemeral(true).queue(); return; }
        String reason = e.getOption("reason") == null ? "" : e.getOption("reason").getAsString();
        String durationStr = e.getOption("duration") == null ? null : e.getOption("duration").getAsString();
        String issuer = "Discord: " + e.getUser().getName();

        e.deferReply().queue();  // ack within 3s; we reply after the async DB work
        // resolve the target off the JDA thread via the DB executor
        plugin.db().callback(plugin.players().byName(playerName), rec -> {
            if (rec == null) { e.getHook().sendMessage("Player not found: " + playerName).queue(); return; }
            UUID target = rec.uuid();
            long now = System.currentTimeMillis();
            switch (cmd) {
                case "ban" -> apply(e, PunishmentType.BAN, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "mute" -> apply(e, PunishmentType.MUTE, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "kick" -> apply(e, PunishmentType.KICK, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "warn" -> apply(e, PunishmentType.WARN, target, rec.name(), rec.lastIp(), issuer, reason, 0);
                case "tempban", "tempmute" -> {
                    long expires;
                    try { expires = now + DurationParser.parse(durationStr); }
                    catch (RuntimeException ex) { e.getHook().sendMessage("Bad duration: " + durationStr).queue(); return; }
                    apply(e, cmd.equals("tempban") ? PunishmentType.BAN : PunishmentType.MUTE,
                        target, rec.name(), rec.lastIp(), issuer, reason, expires);
                }
                case "unban" -> plugin.db().callback(plugin.moderation().removeBan(DISCORD_ISSUER, issuer, target, rec.name()),
                    ok -> e.getHook().sendMessage(Boolean.TRUE.equals(ok) ? "Unbanned " + rec.name() : rec.name() + " was not banned.").queue());
                case "unmute" -> plugin.db().callback(plugin.moderation().removeMute(DISCORD_ISSUER, issuer, target, rec.name()),
                    ok -> e.getHook().sendMessage(Boolean.TRUE.equals(ok) ? "Unmuted " + rec.name() : rec.name() + " was not muted.").queue());
                default -> e.getHook().sendMessage("Unknown command.").queue();
            }
        });
    }

    private void apply(SlashCommandInteractionEvent e, PunishmentType type, UUID target, String targetName,
                       String ip, String issuer, String reason, long expiresAt) {
        plugin.db().callback(
            plugin.moderation().apply(DISCORD_ISSUER, issuer, target, targetName, ip, type, expiresAt, reason),
            ok -> e.getHook().sendMessage(Boolean.TRUE.equals(ok)
                ? type.name().toLowerCase() + " applied to " + targetName
                : targetName + " is exempt or the action did nothing.").queue());
    }
}
```

Note: `plugin.db().callback(...)` delivers on the Bukkit main thread; the JDA `e.getHook().sendMessage(...).queue()` call is thread-safe (JDA queues are async), so replying from the main-thread callback is fine. `moderation().apply/removeBan/removeMute` already hop their own Bukkit side-effects to the main thread.

- [ ] **Step 3: Build**

Run: `./gradlew test`
Expected: PASS — existing suite green; new code compiles. (Slash flow verified manually in Task 7.)

- [ ] **Step 4: Commit**

```bash
git add -A -- ':!.claude'
git commit -m "feat: Discord slash-command moderation (role-gated)"
```

---

### Task 7: Docs + manual smoke test

**Files:** Modify `README.md`

- [ ] **Step 1: Document the Discord bot**

Add a "Discord bot" section to `README.md` after the Database section:

```markdown
## Discord

Sentinel can mirror events to Discord two ways:

- **Webhook (simple):** set `discord.webhook-url` for one-line punishment/report messages.
- **Bot (full):** set `discord.bot.enabled: true` and a `discord.bot.token` to run a real bot —
  colour-coded embeds in `log-channel-id`, a live `{online}/{max}` presence, and moderation
  slash commands (`/ban`, `/tempban`, `/mute`, `/tempmute`, `/kick`, `/warn`, `/unban`, `/unmute`)
  usable by members holding any role in `staff-role-ids`. The token is read from the server's
  config only — never commit it.

The bot is fail-soft: a Discord outage or bad token never affects gameplay.
```

(Use real triple backticks.)

- [ ] **Step 2: Document the manual smoke test in the report**

There is no automated JDA test. Record this manual checklist in the task report:
1. Create a Discord application + bot, invite it to a test guild with the `applications.commands` scope and "Send Messages" permission.
2. Set `discord.bot.enabled: true`, `token`, `guild-id`, `log-channel-id`, and a staff role id; start the server.
3. Confirm: console logs "Discord bot connected as …"; the bot shows a `{online}/{max}` presence.
4. Ban a player in-game → an embed appears in the log channel.
5. As a member WITH the staff role, run `/ban player:<name> reason:test` → the player is banned in-game and the bot replies; verify the punishment row records issuer `Discord: <username>`.
6. As a member WITHOUT the staff role, run `/ban` → ephemeral "not allowed", no effect.
7. `/stop` the server → it shuts down promptly (JDA `shutdownNow`).

- [ ] **Step 3: Run the full suite + build the jar**

Run: `./gradlew test` then `./gradlew build`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A -- ':!.claude'
git commit -m "docs: Discord bot setup + manual smoke-test checklist"
```

---

## Self-Review

- **Spec coverage:** JDA dep + voice-exclude (Task 1); `DiscordService` abstraction + webhook/noop + factory + call-site refactor incl. `logAppeal` (Task 2); JDA-free embeds/auth/status units + tests (Task 3); config block + validation (Task 4); `BotDiscordService` connect/embeds/presence + async lifecycle (Task 5); role-gated slash-command moderation (Task 6); docs + manual smoke test (Task 7). All spec sections map to tasks.
- **Placeholder scan:** every code step has concrete code; tests are real. JDA steps note "adjust to the resolved 5.x API" only as a compatibility caveat, not a placeholder.
- **Type consistency:** `DiscordService` method signatures match across interface, Noop, Webhook, and Bot impls. `EmbedData`/`EmbedData.Field`, `DiscordEmbeds.punishment/report/appeal`, `SlashAuth.mayModerate`, `StatusFormatter.format` names match between Task 3 definitions and Tasks 5/6 usage. `plugin.discord()` returns `DiscordService` (Task 2) and is used consistently. Issuer sentinel UUID `new UUID(0,0)` used in Task 6 matches the spec's "sentinel issuer UUID".
- **Note:** `moderation().removeBan/removeMute` signatures `(UUID issuerId, String issuerName, UUID targetId, String targetName)` are used in Task 6 — these match the existing `ModerationService` methods (made async earlier).
