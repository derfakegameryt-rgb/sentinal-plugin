# Sentinel — Moderation Plugin Design

**Date:** 2026-06-14
**Author:** DerFakeGamer (design via brainstorming)
**Status:** Approved design, pending implementation plan

## Overview

**Sentinel** is a GUI-driven moderation plugin for Paper Minecraft servers. All
moderation work is done through chest GUIs (point-and-click) rather than typed
commands. The plugin ships as a single universal JAR that runs on both
**MC 1.21.11 (Java 21)** and **MC 26.1.1 (Java 25)** by compiling to Java 21
bytecode and using only stable Paper API (no NMS / version-specific internals).

- **Name:** Sentinel
- **Primary color:** Blue (`#3B82F6`), accent light blue (`#60A5FA`)
- **Language:** All user-facing text in English (from `messages.yml`)
- **Platform:** Paper (single universal JAR, Java 21 bytecode)
- **Storage:** SQLite (local file, no setup)
- **Build:** Gradle (same toolchain as CustomCrafting)
- **Package:** `de.derfakegamer.sentinel`

## Features

- Ban / Tempban / IP-Ban (+ Unban)
- Mute / Tempmute (+ Unmute)
- Kick
- Warn (with history)
- Reports (player-submitted, staff review GUI)
- Staff chat
- Freeze (toggle, prevents movement)
- Self Vanish (toggle, staff only)
- Invsee (live, editable view of a player's inventory)
- EChestSee (live, editable view of a player's ender chest)
- Punishment history per player
- Auto-updater (checks GitHub Releases, downloads new JAR, applies on next restart)

## Permissions / Access

- **OP-only:** All moderation functions (the `/sentinel` GUI, `/sc`, and every
  punishment/inspection action) require server OP (`isOp()`). There are no
  fine-grained permission nodes.
- **Open to everyone:** `/report <player> <reason>`.
- **Exempt list:** A configurable list of UUIDs in `config.yml` that can never be
  punished (owner/admin protection). Attempting to punish an exempt player is
  blocked with an error message.

## Commands

All commands are optional shortcuts — the GUI is the primary interface.

| Command | Access | Purpose |
|---|---|---|
| `/sentinel [player]` | OP | Open the Players GUI, or jump straight to a player's Actions GUI |
| `/report <player> <reason>` | everyone | Submit a report |
| `/sc [message]` | OP | Toggle staff chat, or send a one-off staff chat message |
| `/sentinel reload` | OP | Reload config + messages |
| `/sentinel update` | OP | Force an immediate update check |

## GUI Flow

### Players GUI (`/sentinel`) — 54 slots
- Slots 0–44: online player heads (up to 45 per page). Head lore shows status
  (muted, banned, warn count).
- Bottom row (45–53) navigation:
  - `45` ◀ previous page (only shown from page 2 onward)
  - `46` 🔍 search (chat input to filter by name)
  - `47` 📋 Reports GUI
  - `48` ⚙ Staff settings (staff chat toggle, etc.)
  - `49` 👁 Vanish — toggles the viewer's own vanish (center bottom)
  - `52` ✖ close
  - `53` ▶ next page (only shown when more than 45 players are online)

### Player Actions GUI (click a head) — 45 slots
- Buttons are **context-sensitive**: if the player is already banned, the Ban
  button becomes "Unban"; if muted, Mute becomes "Unmute" — preventing
  double-punishment.
- Layout:
  - Row 1: player head + status info (center)
  - Row 2: 🔨 Ban · ⏱ Tempban · 🔇 Mute · ⏲ Tempmute · 👢 Kick · ⚠ Warn
  - Row 3: 🌐 IP-Ban · 🧊 Freeze · 🎒 Invsee · 📦 EChestSee · 📜 History
  - Row 5: ◀ Back (to Players GUI) · ✖ Close

### Duration & Reason flow (Tempban / Tempmute)
1. Click Tempban/Tempmute → GUI closes → chat prompt:
   *"Enter duration (e.g. 30s, 10m, 3h, 8d, 2w) or type `cancel`:"*
2. After valid duration → Reason GUI opens.
3. Permanent punishments (Ban/Mute) skip step 1 and go directly to the Reason GUI.

### Reason GUI — 27 slots
- 5 preset reasons (configured in `config.yml`, e.g. Hacking, Spam, Toxicity,
  Advertising, Griefing).
- ✏ Custom → chat prompt: *"Enter reason or type `cancel`:"*
- Preset click or custom entry → **Confirmation GUI** (green Confirm / red Cancel)
  → punishment executed. This guards against misclicks.

### History GUI (📜) — 54 slots, paginated
- One item per past punishment. Icon by type (Ban / Mute / Warn / Kick).
- Lore: reason, moderator, date, duration / expired-or-active status.

### Reports GUI (📋) — 54 slots, paginated
- One item per open report. Lore: reporter, target, reason, time.
- Left-click = teleport to target; Right-click = open target's Actions GUI;
  Shift-click = mark as handled.

### Invsee (🎒) and EChestSee (📦)
- Open the target's **actual** inventory / ender chest via
  `viewer.openInventory(target.getInventory())` and `target.getEnderChest()`.
- The view is **live and fully editable**: the viewer can take items out, put
  items in, and rearrange; changes apply to the target player immediately.

## Duration Parsing

- Units: `s` = seconds, `m` = minutes, `h` = hours, `d` = days, `w` = weeks.
- Combinable, e.g. `1w2d6h`, `30s`, `10m`.
- Invalid input re-prompts; `cancel` aborts.
- Chat input is captured via the chat event so the message never appears in
  public chat.

## Auto-Update

Sentinel keeps itself up to date from a GitHub repository.

- **Source:** GitHub Releases of `derfakegameryt-rgb/sentinal-plugin`.
  - The finished JAR is uploaded as a **release asset**; the release **tag** is
    the version (e.g. `v1.2.0`).
- **Check:** On startup and then on a repeating async task every
  `update.check-interval-seconds` (config; default `1800` = 30 min), Sentinel
  calls `https://api.github.com/repos/derfakegameryt-rgb/sentinal-plugin/releases/latest`
  and reads `tag_name` plus the `.jar` asset's `browser_download_url`.
- **Compare:** The tag (with a leading `v` stripped) is compared against the
  running version (`getPluginMeta().getVersion()`). A strictly higher semantic
  version triggers an update; equal/lower is ignored (no downgrade).
- **Download & apply (next restart):** The new JAR is downloaded async to the
  Bukkit **update folder** (`plugins/update/Sentinel.jar`, filename matching the
  installed plugin JAR). Paper applies it automatically on the next server
  restart. The running plugin is **not** hot-swapped (unsafe).
- **Notify:** On a successful download, log to console and message online OPs:
  *"Sentinel vX.Y.Z downloaded — restart the server to apply."* If already up to
  date, nothing is shown (unless triggered via `/sentinel update`).
- **Rate limits:** Unauthenticated GitHub API allows ~60 requests/hour per IP.
  The default 30-minute interval stays well within that. The config enforces a
  **minimum interval of 60 seconds**, and an optional `update.github-token` can
  be set to raise the limit. Failed checks (network/API errors) are logged at a
  low level and never crash the plugin.
- **Toggle:** `update.enabled` (default `true`) disables the whole feature.

## Data Model (SQLite)

```
punishments
  id           INTEGER PRIMARY KEY AUTOINCREMENT
  type         TEXT     -- BAN / MUTE / WARN / KICK / IPBAN
  target_uuid  TEXT
  target_name  TEXT
  target_ip    TEXT     -- nullable (for IP bans)
  reason       TEXT
  issuer_uuid  TEXT
  issuer_name  TEXT
  created_at   INTEGER  -- epoch millis
  expires_at   INTEGER  -- nullable; 0 = permanent
  active       INTEGER  -- 0/1
  removed_by   TEXT     -- nullable (who unbanned/unmuted)
  removed_at   INTEGER  -- nullable

reports
  id            INTEGER PRIMARY KEY AUTOINCREMENT
  reporter_uuid TEXT
  reporter_name TEXT
  target_uuid   TEXT
  target_name   TEXT
  reason        TEXT
  created_at    INTEGER
  handled       INTEGER  -- 0/1
  handled_by    TEXT     -- nullable
```

- Active punishments are checked on login (`AsyncPlayerPreLoginEvent`) and on
  chat (`AsyncChatEvent` for mutes / staff chat routing).
- Expired punishments are lazily flipped to `active = 0` when encountered.

## Module Structure

```
de.derfakegamer.sentinel
├── Sentinel.java            (main plugin: loads config, registers everything)
├── storage/
│   ├── Database.java        (SQLite connection, schema setup)
│   ├── PunishmentDao.java
│   └── ReportDao.java
├── model/
│   ├── Punishment.java
│   ├── PunishmentType.java
│   └── Report.java
├── manager/
│   ├── PunishmentManager.java  (ban/mute/warn/kick logic, expiry checks)
│   ├── ReportManager.java
│   ├── StaffChatManager.java
│   ├── FreezeManager.java
│   └── VanishManager.java
├── gui/
│   ├── PlayersGui.java
│   ├── PlayerActionsGui.java
│   ├── ReasonGui.java
│   ├── ConfirmGui.java
│   ├── HistoryGui.java
│   └── ReportsGui.java
├── updater/
│   └── UpdateChecker.java    (GitHub Releases poll, download to update folder)
├── command/
│   ├── SentinelCommand.java
│   ├── ReportCommand.java
│   └── StaffChatCommand.java
├── listener/
│   ├── LoginListener.java    (ban / ip-ban enforcement)
│   ├── ChatListener.java     (mute enforcement, staff chat, chat-input prompts)
│   ├── MoveListener.java     (freeze enforcement)
│   ├── JoinQuitListener.java (vanish handling, staff notifications)
│   └── GuiListener.java      (inventory click routing)
└── util/
    ├── Messages.java         (messages.yml, blue theme, prefix)
    └── DurationParser.java
```

## Color Theme (MiniMessage)

- Primary blue: `<#3B82F6>`, accent: `<#60A5FA>`
- Error: red, success: green
- Prefix: `<#3B82F6><bold>Sentinel</bold> <dark_gray>»</dark_gray>`
- GUI border/filler: `LIGHT_BLUE_STAINED_GLASS_PANE`
- All strings sourced from `messages.yml` (English).

## Config Files

- `config.yml` — storage settings, 5 preset reasons, exempt UUID list, defaults,
  and the `update:` section (`enabled`, `check-interval-seconds`, `github-token`).
- `messages.yml` — all user-facing strings (English, MiniMessage formatted).

## Cross-Version Strategy

- Compile to Java 21 bytecode → runs on both Java 21 and Java 25 runtimes.
- Use only stable Paper API (events, `Inventory`, `getEnderChest()`, MiniMessage
  via Adventure). No NMS, no version-specific reflection.
- One JAR for both MC 1.21.11 and MC 26.1.1.

## Out of Scope (YAGNI)

- MySQL / network-wide (BungeeCord/Velocity) sync — SQLite single-server only.
- Web dashboard / Discord integration.
- Appeals system.
- Fine-grained permission nodes (OP gate only).
