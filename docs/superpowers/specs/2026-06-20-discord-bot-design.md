# Discord Bot Integration — Design

**Date:** 2026-06-20
**Status:** Approved (design)

## Problem

Sentinel currently mirrors punishments and reports to Discord via a one-line plain-text
**webhook** (`DiscordWebhook.post(String)`). Server owners want a real **bot**: drop a bot
token into the config and get rich event embeds, a live status/presence, and the ability to
moderate from Discord via slash commands.

## Goal

A configurable Discord bot that, when a token is set, connects to Discord and provides:
1. **Event embeds** — punishments, reports, and appeals posted as colour-coded embeds to a log channel.
2. **Status/presence** — bot presence shows live player count, refreshed periodically.
3. **Slash-command moderation** — `/ban`, `/tempban`, `/mute`, `/tempmute`, `/kick`, `/warn`,
   `/unban`, `/unmute`, `/history` from Discord, gated by configured Discord staff roles.

Non-goals: a Minecraft↔Discord chat bridge; Discord↔MC account linking; an automated test
against the live Discord gateway.

## Architecture

### `DiscordService` interface replaces `DiscordWebhook.post`

A `discord/DiscordService` interface with semantic methods (not a generic `post(String)`):

```
void logPunishment(PunishmentType type, String targetName, String issuerName, String reason, long expiresAt);
void logReport(String reporterName, String targetName, String reason);
void logAppeal(String targetName, PunishmentType type, String text);
void updatePresence(int online, int max);   // no-op for non-bot impls
void shutdown();
```

Three implementations, selected once at startup by `DiscordFactory`:
- `BotDiscordService` — JDA bot: embeds to the log channel, presence updates, slash commands.
  Active when `discord.bot.enabled` and `discord.bot.token` are set.
- `WebhookDiscordService` — the existing plain-text webhook (kept as a fallback). Active when
  no bot token but `discord.webhook-url` is set. Implements the semantic methods by formatting
  a one-line string (preserving today's behavior).
- `NoopDiscordService` — when neither is configured.

`plugin.discord()` returns the active `DiscordService`. The managers (`ModerationService`,
`ReportManager`, `AppealManager`) call the semantic methods instead of `post(String)`. This is
a small refactor of the few existing `discord().post(...)` call sites.

### Library

JDA (`net.dv8tion:JDA`), shaded and relocated to `de.derfakegamer.sentinel.libs.jda` like the
JDBC drivers. Voice/audio support is excluded in the shadow config to limit jar growth
(`exclude` JDA's `opus`/audio packages and the `club.minnced:opus-java` transitive). Jackson and
the websocket client come along and are relocated under the same prefix.

### Lifecycle

- `onEnable`: build the `DiscordService` via `DiscordFactory.create(plugin)`. For the bot,
  start JDA **asynchronously** (login briefly blocks; must not block server startup): on a
  background task, `JDABuilder.createLight(token, ...)` (no member/message-content intents
  beyond what's needed), `awaitReady()`, register the slash commands as **guild commands** for
  the configured `guild-id` (instant availability), and set initial presence. Schedule the
  periodic presence update task.
- `onDisable`: `discord.shutdown()` → `jda.shutdownNow()` so server stop never hangs.
- Bukkit access from JDA threads (slash-command handlers) goes through the existing async-DB
  futures; punishment side-effects already hop to the main thread via `ModerationService`'s
  `onMain`.

## Configuration

```yaml
discord:
  webhook-url: ''        # fallback when no bot token
  bot:
    enabled: false
    token: ''            # bot token — server-side only, never commit
    guild-id: ''         # the Discord server (guild) where slash commands register
    log-channel-id: ''   # channel for punishment/report/appeal embeds
    staff-role-ids: []   # Discord role IDs allowed to use moderation slash commands
    status: "{online}/{max} online"   # presence text; {online}/{max} substituted
    status-seconds: 60   # presence refresh interval
```

`ConfigValidator` additions (fail-soft warnings): when `discord.bot.enabled` is true, warn if
`token`, `guild-id`, or `log-channel-id` is blank; warn if `status-seconds` < 1.

## Slash-command moderation

Commands registered as guild commands: `/ban`, `/tempban`, `/mute`, `/tempmute`, `/kick`,
`/warn`, `/unban`, `/unmute`, `/history`, each with a `player` string option, plus `duration`
(temp commands) and `reason` (where applicable).

- **Authorization:** `SlashAuth.mayModerate(memberRoleIds, staffRoleIds)` returns true iff the
  invoking member has at least one configured staff role. Otherwise the handler replies with an
  ephemeral "not allowed" message and does nothing.
- **Execution:** resolve the target by name via `players().byName(...)` (offline UUID); if not
  found, reply "player not found". Otherwise call `plugin.moderation().apply(...)` (or
  `punishments().unban/unmute(...)`) with a sentinel issuer UUID and issuerName
  `"Discord: <username>"`. Await the returned `CompletableFuture`, then reply in Discord with
  the outcome (success / exempt / removed / not active). The punishment takes effect in-game
  immediately through the existing main-thread side-effect hops.
- `/history` awaits `punishments().history(uuid)` and replies with an embed listing entries.

## Presence

A scheduled task every `status-seconds` calls `discord.updatePresence(onlineCount, maxPlayers)`,
which (for the bot) sets the JDA presence using `StatusFormatter.format(template, online, max)`
(substitutes `{online}` and `{max}`).

## Embeds

`DiscordEmbeds` builds embed **data objects** (title, fields, colour) from punishment/report/
appeal inputs — pure, JDA-free, unit-testable. `BotDiscordService` converts those to JDA
`MessageEmbed`s and sends them to the log channel. Colours: ban/ipban red, mute/shadowmute
orange, kick/warn yellow, report blue, appeal green.

## Error handling (fail-soft — Discord must never disrupt the server)

- Invalid/rejected token: async JDA login fails → `warning` log; the plugin runs normally
  (no disable, no blocked punishments).
- Missing `guild-id`/`log-channel-id` with the bot enabled: `ConfigValidator` warning; the bot
  runs but posts nothing / registers no commands.
- Every JDA call (send embed, set presence) is wrapped in try/catch with `fine`/`warning`
  logging; a Discord outage never throws into gameplay.
- Slash-command handler errors → ephemeral reply to the user, exception logged.

## Testing

JDA's gateway cannot be unit-tested, so the logic is extracted into JDA-free units:
- `DiscordEmbeds` — builds embed data objects; unit-tested for each event type's fields/colour.
- `SlashAuth.mayModerate(...)` — unit-tested (role present/absent/empty).
- `StatusFormatter.format(...)` — unit-tested for `{online}`/`{max}` substitution.
- `WebhookDiscordService` and `NoopDiscordService` — no JDA; tested (webhook formats the
  expected one-line strings; noop does nothing).
- `ConfigValidator` tests for the `discord.bot` block.
- `BotDiscordService` JDA wiring is verified by a **documented manual smoke test** against a
  real test bot (token set → bot comes online with presence; ban in-game posts an embed;
  `/ban` from Discord by a staff-role member bans in-game; a non-staff member is refused).

## Risks

- JDA jar-size growth — mitigated by excluding voice/audio in the shadow config.
- Slash commands run on JDA threads; all Bukkit/DB access must go through the executor/main-thread
  hops (already the case via `ModerationService`/`punishments()` futures). Mitigation: handlers
  never touch Bukkit state directly; they await futures and reply.
- A sentinel issuer UUID is stored for Discord-issued punishments; this is recorded data only and
  does not affect lookups.
