# Audit Log & Moderation Statistics — Design

**Date:** 2026-06-20
**Status:** Approved (design)

## Problem

There is no unified record of *who did what when*. Punishments record their issuer, but
non-punishment staff actions (reload, maintenance toggle, vanish, freeze, clearchat, broadcast)
leave no trace, and there is no moderation-statistics view. Admins want an append-only audit log
of all staff actions plus aggregate stats, viewable in-game, by command, and from Discord.

## Goal

- An append-only `audit` table capturing every staff action (punishments + non-punishment).
- Moderation statistics aggregated from that table (actions per staff, per type, recent window).
- Three view surfaces: in-game GUIs (in the `/sn` hub), commands, and Discord slash commands.
- Fail-soft: audit writing/reading never disrupts gameplay.

Non-goals: editing/deleting audit entries; retention/pruning (out of scope for now).

## Data model & recording

### `audit` table (new, via `SqlDialect` for SQLite + MySQL)

Columns: `id` (auto-increment PK), `actor` (staff name, `"Discord: X"`, or `"CONSOLE"`),
`action` (stable token: `BAN/IPBAN/MUTE/TEMPMUTE-as-MUTE/SHADOWMUTE/KICK/WARN/UNBAN/UNMUTE/`
`RELOAD/MAINTENANCE/VANISH/FREEZE/CLEARCHAT/BROADCAST`), `target` (affected player name, nullable),
`details` (reason/duration/extra, may be empty), `created_at` (BIGINT millis). Indexes on
`created_at` and `actor`. The MySQL dialect uses `VARCHAR(n)` for `actor`/`action`/`target`
(indexed/short) and `BIGINT` PK/timestamp; SQLite uses `TEXT`/`INTEGER ... AUTOINCREMENT`.
This is appended to `SqlDialect.SQLITE.schemaStatements()` and `SqlDialect.MYSQL.schemaStatements()`.

### `AuditDao`

- `void insert(String actor, String action, String target, String details, long createdAt)`
- `List<AuditEntry> recent(int limit, int offset)` — newest first
- `List<AuditEntry> recentForTarget(String target, int limit)` — newest first, filtered
- `List<ActorCount> topActors(long sinceMillis, int limit)` — `GROUP BY actor ORDER BY count DESC`
- `List<ActionCount> countsByAction(long sinceMillis)` — `GROUP BY action`

`AuditEntry`, `ActorCount(String actor, int count)`, `ActionCount(String action, int count)` are
small records in `model`. Aggregate queries are standard SQL (no dialect difference).

### `AuditManager`

- `void record(String actor, String action, String target, String details)` — fire-and-forget
  `plugin.db().execute(() -> dao.insert(..., System.currentTimeMillis()))` (timestamp captured
  before the lambda).
- `CompletableFuture<List<AuditEntry>> recent(int limit, int offset)` / `recentForTarget(...)`.
- `CompletableFuture<List<ActorCount>> topActors(long since, int limit)`.
- `CompletableFuture<List<ActionCount>> countsByAction(long since)`.

Constructed in `Sentinel#onEnable` with `new AuditDao(db.database())`; exposed via `plugin.audit()`.

### Recording hooks (minimal, central)

- **Punishments:** a single `plugin.audit().record(...)` call inside `ModerationService.apply`
  (one per applied punishment) and in `removeBan`/`removeMute`/`removeShadowMute`. This covers
  BOTH in-game commands and Discord slash commands, since both route through `ModerationService`.
  The actor is the issuerName already passed in (so Discord-issued actions log `"Discord: X"`).
- **Non-punishment actions:** one `record(...)` at each handler — reload (`SentinelCommand`),
  maintenance toggle (`MaintenanceCommand`), vanish + staff-chat (now in `AdminPanelGui`),
  freeze (its command/handler), clearchat (`ClearChatCommand`), broadcast (`BroadcastCommand`).

The `audit` table is the single source for the statistics views; `punishments` remains the
source of truth for active state/expiry.

## Views

### In-game GUIs (in the hub)

`AdminPanelGui` gains two entries in the reserved slots:
- **Audit Log** (slot 23) → `AuditGui` — chronological, paged list (time · actor · action ·
  target · details). Static async opener via `plugin.db().callback(audit().recent(limit, offset), ...)`;
  Back → hub; Prev/Next paging.
- **Mod Stats** (slot 24) → `ModStatsGui` — top staff (actions per moderator) and counts per
  action type over a recent window (last 30 days). Static async opener; Back → hub. (Distinct
  from the existing "Playtime" entry.)

### Commands

- `/sentinel audit [player]` — recent entries, optionally filtered to a target, printed to chat
  via `plugin.db().callback(...)`.
- `/sentinel stats` — aggregate counts (top staff + per type) to chat.

Both gated by `sentinel.use` like the other `/sentinel` subcommands; added to the `SUBCOMMANDS`
set and tab-completion.

### Discord (role-gated slash commands)

- `/stats` — embed with top staff + counts per type.
- `/audit player:<name>` — embed with recent entries for a player.

Registered with the other guild commands; gated by `SlashAuth.mayModerate` against the live
`discord.bot.staff-role-ids`; replies ephemeral via `getHook()`. The embed/aggregation
formatting lives in JDA-free helpers (extending the `DiscordEmbeds` approach) so it is
unit-testable; the JDA wiring is verified manually.

## Error handling (fail-soft)

- `record(...)` is fire-and-forget; an audit write error is logged by the executor and never
  affects the underlying action (ban/reload/etc.).
- Read paths (GUI/command/Discord) deliver an empty list / "no data" message on a DB error
  (the callback yields null → treated as empty) rather than hanging.
- `target`/`details` handled defensively (nullable target; empty details rendered as "—").

## Testing

- `AuditDaoTest` (SQLite): insert → `recent` (newest-first ordering, limit/offset paging),
  `recentForTarget`, `topActors(since, limit)` (grouping/sorting), `countsByAction(since)`.
- `AuditManagerTest`: `record` persists (drain executor, then read); aggregate methods return
  correct counts.
- `SqlDialectTest`: extended to assert the `audit` table appears in BOTH dialects' schema.
- GUI tests (`AuditGuiTest`, `ModStatsGuiTest`): static opener + callback (drain/tick); render
  entries/stats; hub buttons at slots 23/24 open them.
- Command test: `/sentinel audit` and `/sentinel stats` produce output via callback.
- Recording-hook test: a ban via `ModerationService` creates an audit entry (drain, then `recent`).
- Discord `/stats` `/audit`: the JDA-free embed/aggregation logic is unit-tested (like
  `DiscordEmbeds`); the JDA wiring is manual.

## Risks

- Adding the `audit` table to both dialects must be exact (VARCHAR for indexed columns on MySQL);
  mitigated by `SqlDialectTest`.
- Recording hooks scattered across handlers could be missed; mitigated by routing all punishment
  logging through the single `ModerationService` choke point and a recording-hook test.
- Slight duplication between `audit` and `punishments` for punishment rows is intentional (audit
  is the immutable timeline; punishments holds active state).
