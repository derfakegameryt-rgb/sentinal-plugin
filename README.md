# Sentinel

A GUI-driven moderation plugin for Paper Minecraft servers.

One universal JAR runs on **Minecraft 1.21.11 (Java 21)** and **26.1.1 (Java 25)**.

## Features

- **Punishments:** ban, tempban, IP-ban, mute, tempmute, kick, warn — with full history
- **GUI-first:** `/sentinel` opens a point-and-click menu; durations and reasons are entered in chat or picked from presets, with a confirmation step against misclicks
- **Tools:** freeze, self-vanish, live inventory & ender-chest viewing (invsee / echestsee)
- **Reports:** players use `/report <player> <reason>`; staff review them in a GUI
- **Staff chat:** `/sc`
- **Auto-updater:** keeps itself up to date from this repository's GitHub Releases

## Installation

1. Download the latest `Sentinel.jar` from the [Releases](../../releases) page.
2. Drop it into your server's `plugins/` folder.
3. Restart the server.

All moderation features require operator (OP) status; `/report` is available to everyone.

## Updates

Sentinel checks this repository's latest release on startup and periodically. When a
newer release is published, it downloads the new JAR into `plugins/update/` and applies
it on the next server restart. This can be configured or disabled in `config.yml`.
