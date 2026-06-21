# GUI i18n + Tab-Completion — Design

**Date:** 2026-06-21
**Status:** Approved (design)

## Problem

All GUI button labels and lore are hardcoded English `Component.text(...)` literals across ~23
GUIs, so the in-game menus cannot be translated or customised — unlike chat messages, which already
live in `messages.yml`. Tab-completion is also only implemented for `/sentinel` and the punishment
commands; most other commands offer no completion.

## Goal

Make every visible GUI string customisable through `messages.yml` (single active language, English
defaults so existing servers see no visual change), and give every command sensible tab-completion.
No behaviour change beyond text source and completion suggestions.

Non-goals: per-player multi-locale (one configurable language only); changing chat-message keys that
already work; redesigning any GUI layout (that was the prior control-row refactor).

## 1. i18n mechanism

`messages.yml` gains a `gui.*` section of MiniMessage templates with English defaults. Bundled
defaults ship in the jar's `messages.yml`; the existing `mergeMessagesDefaults()` copies any missing
keys into a server's on-disk file on the next start, so existing installs gain the keys automatically
without overwriting admin edits.

`util/Messages` gains:

- `List<Component> list(String key, String... placeholders)` — reads a YAML **string list** at
  `key` and returns one `Component` per line, each with italic explicitly disabled (lore on items
  defaults to italic; GUIs currently disable it manually). MiniMessage per line, same fail-soft
  `deserialize` path as `plain`. A missing key returns an empty list (no lore), never throws.
- `plain(key, ...)` (existing) is used for single-line button names. Button-name Components still go
  through `Items.button`, which already strips italic/bold on the name.

Placeholders use the existing `<name>` MiniMessage unparsed-placeholder convention (e.g.
`gui.players.head.warns: "<gray>Warns: <count>"` resolved with `"count", String.valueOf(n)`).

## 2. GUI conversion

Every GUI replaces hardcoded name/lore Components with `messages().plain("gui...")` and
`messages().list("gui....lore")`. Key schema: `gui.<screen>.<element>` and `gui.<screen>.<element>.lore`
(e.g. `gui.panel.player-manager`, `gui.actions.ban`, `gui.actions.ban.lore`). Status-dependent labels
get one key per state (e.g. `gui.actions.ban` / `gui.actions.unban`). Titles already use
`gui-*-title` keys and stay as-is.

Done in batches across the ~23 GUIs (same approach as the control-row refactor). The shared
`Items.button`/`Items.head` signatures are unchanged — only their arguments change from literals to
message lookups. GUIs that build many Components keep small private helpers but source the text from
`messages()`.

Defaults reproduce the current English text verbatim, so existing tests that assert display-name
substrings (via `PlainTextComponentSerializer`) stay green without changes. Any test that asserted a
hardcoded lore string is updated to read the same key, or left if it only checks the (unchanged)
default text.

## 3. Tab-completion

A `util/Completions` helper centralises suggestion logic, all prefix-filtered (case-insensitive):

- `players(prefix)` — online player names (respecting vanish where a viewer is available; plain
  online names otherwise).
- `durations(prefix)` — `1h, 6h, 12h, 1d, 7d, 30d, perm` (suggestions only; `DurationParser` still
  validates).
- `reasons(prefix)` — the configured reason presets.
- `of(prefix, options...)` / `filter(prefix, collection)` — generic prefix filter for subcommands/flags.

Every command class implements `onTabComplete` (or its registered command gets a `TabCompleter`):
report, appeal, broadcast, clearchat, maintenance, restart, playtime, backup, staffchat, rules, and
the existing `/sentinel` + punishment completers are refined to use `Completions`. Argument position
determines the suggestion set (e.g. arg 1 = player; arg 2 of a temp punishment = duration; trailing
= reason presets).

## Error handling

`Messages.list` is fail-soft like `plain` (bad MiniMessage tag → plain-text fallback; missing key →
empty lore). Completions never throw and return an empty list on any error. Nothing here touches the
DB or async paths.

## Testing

- `MessagesTest`: `list(key)` returns one component per YAML list line; placeholders substituted;
  italic disabled; missing key → empty list; a malformed tag falls back to plain text.
- A representative GUI test asserts a button label is sourced from `messages.yml` — overriding the
  key in the config changes the rendered label (proves it is no longer hardcoded).
- `CompletionsTest`: prefix filtering is case-insensitive; durations/reasons suggested; empty/`null`
  prefix returns all; unknown prefix returns empty.
- All existing GUI tests stay green (English defaults unchanged).

## Risks

- **Breadth** — ~23 GUIs touched. Mitigation: English defaults verbatim so behaviour/tests are
  unchanged; batched conversion with per-batch test runs (the prior control-row refactor used the
  same approach successfully).
- **Key sprawl / typos** — a wrong key renders as the literal key text. Mitigation: defaults shipped
  in the bundled `messages.yml`; a representative test proves lookups resolve; reviewers spot-check
  key names against the bundled file.
