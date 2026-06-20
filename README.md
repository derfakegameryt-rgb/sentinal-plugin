# Sentinel

A complete, GUI-driven moderation and server-management plugin for Paper Minecraft servers.
Punishments, reports, appeals, chat moderation, staff tooling, and admin GUIs — backed by a
local SQLite database with no external services required.

One universal JAR runs on **Minecraft 1.21.11 (Java 21)** and **26.1.1 (Java 25)**.

- **Storage:** SQLite (bundled, zero setup)
- **Author:** DerFakeGamer

---

## Features

**Punishments**
- Ban, temp-ban, IP-ban, mute, temp-mute, kick, warn
- **Shadow-mute** — the muted player still sees their own messages, but no one else does
- **Warn escalation** — automatically apply a configurable punishment when a player reaches N warnings
- Full per-player **history** and **alt detection** (accounts sharing an IP)
- Ban screen shows a configurable **appeal URL**

**Player reports & appeals**
- `/report` for players to flag others; staff handle them from a GUI
- `/appeal` for muted players to contest a punishment; staff review from a GUI

**Chat moderation**
- Anti-spam (repeat detection), anti-advertising (domain whitelist), word filter (block/censor),
  caps filter, flood filter, configurable slow-mode
- Full **chat & command logging** with configurable retention (sensitive commands like `/login` are never logged)

**Staff tooling**
- Staff chat (`/sc`), self-vanish, freeze, live inventory & ender-chest viewing (invsee / echestsee), player notes
- **GUI-first:** `/sentinel` opens a point-and-click menu; durations and reasons are entered in chat
  or picked from presets, with a confirmation step against misclicks
- Admin GUIs: player list, player actions, active bans/mutes, reports, appeals, history, notes, alts, stats, chat log, templates
- Quick-punishment **templates** and preset **reasons**

**Server management**
- Maintenance mode (custom MOTD + kick message, owner bypass)
- Scheduled restarts, world backups (zip), playtime tracking
- Auto-announcer, scheduled console tasks (cron-like)
- Discord webhook mirroring of punishments & reports
- **Auto-updater** (see below)

---

## Installation

1. Download the latest `Sentinel.jar` from the [Releases](https://github.com/derfakegameryt-rgb/sentinal-plugin/releases) page.
2. Drop it into your server's `plugins/` folder.
3. Start the server once to generate `plugins/Sentinel/config.yml`, `messages.yml`, and `rules.txt`.
4. Edit the config to taste and run `/sentinel reload`.

No database or external service to configure — SQLite is bundled.

---

## Commands

Open the main admin GUI with `/sentinel` (alias `/sn`). Most actions are available both as commands and via the GUIs.

| Command | Description |
|---|---|
| `/sentinel` · `/sn` | Open the admin panel / player list; `reload`, `update` subcommands |
| `/ban <player> <reason>` | Permanently ban |
| `/tempban <player> <duration> <reason>` | Temporary ban (e.g. `1d2h`) |
| `/ipban <player> <reason>` | Ban the player's IP |
| `/unban <player>` | Remove a ban |
| `/mute <player> <reason>` | Mute |
| `/tempmute <player> <duration> <reason>` | Temporary mute |
| `/unmute <player>` | Remove a mute |
| `/shadowmute <player> <reason>` · `/unshadowmute <player>` | Shadow-mute / remove |
| `/kick <player> <reason>` | Kick |
| `/warn <player> <reason>` | Warn (may trigger escalation) |
| `/history <player>` | View punishment history |
| `/report <player> <reason>` | Report a player (all players) |
| `/appeal <reason>` | Appeal an active mute (muted players) |
| `/rules` | Show the server rules |
| `/sc <message>` | Staff chat |
| `/clearchat` · `/cc` | Clear chat |
| `/maintenance` | Toggle maintenance mode |
| `/broadcast <message>` · `/bc` | Broadcast |
| `/restart <delay>` | Schedule a restart |
| `/playtime [player]` | Show playtime |
| `/backup` | Zip all worlds to a backup |

**Durations** accept formats like `30s`, `10m`, `2h`, `1d`, `1d2h30m`.

---

## Permissions

The server **owner** (an op) bypasses every check. Individual actions are gated by per-action nodes,
all defaulting to `op`:

| Node | Grants |
|---|---|
| `sentinel.use` | Access to the admin commands and GUIs |
| `sentinel.ban` | Ban / temp-ban |
| `sentinel.ipban` | IP-ban |
| `sentinel.mute` | Mute / temp-mute |
| `sentinel.kick` | Kick |
| `sentinel.warn` | Warn |
| `sentinel.shadowmute` | Shadow-mute |
| `sentinel.unban` | Remove bans |
| `sentinel.unmute` | Remove mutes |

`/report`, `/appeal`, and `/rules` are available to all players.

---

## Configuration

`config.yml` is documented inline. Highlights:

- `exempt` — UUIDs that can never be punished
- `reasons` / `templates` — presets shown in the Reason and Templates GUIs
- `warn-actions` — map a warning count to an action, e.g. `3: "tempban 1d Reached 3 warnings"`
- `chat` — anti-spam, anti-advertising, word/caps/flood filters, slow-mode
- `logging` — chat-log retention and commands to never log
- `maintenance`, `announcements`, `afk`, `backup`, `scheduled-tasks`
- `discord.webhook-url` — paste a webhook URL to mirror punishments & reports (blank = off)
- `appeals.url` — public appeal URL shown on the ban screen
- `update.github-token` — optional; only needed to raise the GitHub API rate limit

On startup Sentinel **validates the config** and logs clear warnings for malformed values
(bad webhook URL, invalid durations, unknown sound, bad `warn-actions`/`scheduled-tasks` syntax,
invalid UUIDs). Warnings never stop the server — fix them and `/sentinel reload`.

---

## Auto-updater

Sentinel checks this repository's GitHub Releases on startup and every 5 minutes. When a newer
version is published it downloads the new JAR into `plugins/update/` to apply on the next restart.
The repository is public, so **no token is required** — leave `update.github-token` blank.
Transient failures (offline, rate limit) are kept out of the console to avoid log spam; run
`/sentinel update` to check on demand and see any error.

---

## Building

```bash
./gradlew build   # shaded plugin jar -> build/libs/
./gradlew test    # run the test suite
```

---

## Architecture notes

All database access is routed through a single-threaded `DatabaseExecutor` that exclusively owns
the JDBC connection, so the SQLite connection is never touched concurrently. Reads return
`CompletableFuture`s delivered back to the main thread; writes are fire-and-forget. This keeps the
server tick free of blocking database I/O. DAOs are synchronous and only ever run on the executor
thread.
