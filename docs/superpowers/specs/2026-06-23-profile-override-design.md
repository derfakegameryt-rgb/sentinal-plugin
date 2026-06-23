# Profile Override (display name + skin) — Design

**Date:** 2026-06-23
**Status:** Approved (design)

## Problem

Staff want to change a player's **display name** (chat, TAB list, and the name above their head) and
their **skin** straight from the admin GUI — entering a Mojang username copies that account's skin onto
the target. Today Sentinel has no way to do either.

## Goal

From the PlayerActions GUI (for an **online** target), let staff:

1. **Set name** — type a name in chat; it is applied to chat, the TAB list, and the above-head nametag.
2. **Set skin** — type a Mojang username in chat; that account's skin is fetched from Mojang and applied
   to the target.
3. **Reset** — restore the target's real name and skin.

Both overrides are **persistent**: stored in SQLite and re-applied automatically on the player's next
join (the skin from stored textures, with no repeat Mojang call).

Non-goals (v1): coloured/MiniMessage display names; changing the name above-head to anything other than a
plain ≤16-char token; changing names/skins of **offline** players from the GUI; a chat command (GUI only).

## Decisions & hard constraints

- **One uniform name on all three surfaces.** The above-head nametag is rendered by the client from the
  profile **name**, which only accepts **≤16 chars from `[A-Za-z0-9_]` and no colour**. So the display
  name is a single plain token applied identically to chat, TAB, and above-head — like a classic nick.
- **Self-view: fixed on relog, lags only live.** Persisted overrides are applied at **login** by mutating
  the login profile (`AsyncPlayerPreLoginEvent#getPlayerProfile()`), so after a relog the player sees their
  **own** overridden name+skin correctly from spawn, with no packets. The only residual lag is the *live*
  change while the target is already online: others see it immediately (hide/show resend), the target sees
  their own new skin after the next relog. Forcing an immediate client-side self-respawn would need
  NMS/packets, which this plugin deliberately avoids (small jar, two MC versions on one jar).
- **No new heavy dependencies.** Everything uses the Paper API already on the classpath:
  `Server#createProfile(String)`, `PlayerProfile#complete()/setName()/setProperty()/getProperties()`,
  `Player#getPlayerProfile()/setPlayerProfile()`, `Player#hidePlayer/showPlayer(Plugin, Player)`,
  `Player#playerListName(Component)`, `Player#displayName(Component)`.

## 1. Data model (`profile_overrides` table)

| column          | type    | notes                                              |
|-----------------|---------|----------------------------------------------------|
| `uuid`          | TEXT PK | the target player                                  |
| `display_name`  | TEXT    | nullable; the plain override name, or null         |
| `skin_value`    | TEXT    | nullable; Mojang "textures" property value         |
| `skin_signature`| TEXT    | nullable; matching signature (keep so it's valid)  |
| `updated_by`    | TEXT    | staff name who set it                              |
| `updated_at`    | INTEGER | epoch ms                                           |

A row exists only while at least one override is active. `reset()` deletes the row. Added via a
`SchemaMigrator` step (new `CREATE TABLE IF NOT EXISTS`), consistent with existing migrations.

## 2. `storage/ProfileOverrideDao`

Plain JDBC over `Database`, following `NoteDao`/`PunishmentDao`:

- `void upsert(ProfileOverride o)` — insert or replace by uuid.
- `ProfileOverride find(UUID)` — or null.
- `void delete(UUID)`.

`model/ProfileOverride` is an immutable record `(UUID uuid, String displayName, String skinValue,
String skinSignature, String updatedBy, long updatedAt)`.

## 3. `manager/ProfileManager` (the mechanics)

Holds the `Sentinel` plugin + DAO. All public methods are main-thread except the Mojang fetch.

- `boolean isValidName(String)` — pure, static, testable: non-blank, length ≤ 16, matches `^[A-Za-z0-9_]+$`.
- `void setName(Player target, String name, String staff)` — validate; if invalid, caller shows
  `profile-bad-name`. Apply (see §5), persist (merge with any existing skin override), audit.
- `void setSkin(Player target, String sourceName, String staff, Consumer<Boolean> done)` — schedule async:
  `Bukkit.createProfile(sourceName).complete(true)`; extract the `textures` property (value+signature).
  Back on the main thread, if the target is still online, apply + persist + audit and call `done(true)`;
  on lookup failure or empty textures call `done(false)` (caller shows `profile-skin-not-found`).
- `void reset(Player target, String staff)` — delete the row, rebuild the real profile from
  `Bukkit.createProfile(uuid, realName).complete(true)` (async), reset `playerListName(null)` /
  `displayName(null)`, re-apply and resend; audit.
- `void applyOnLogin(AsyncPlayerPreLoginEvent)` — runs on the async pre-login thread (where a blocking DB
  read is fine, like the existing ban lookup). Loads the row by UUID; if present, mutates
  `event.getPlayerProfile()` — `setName(displayName)` and replace the `textures` property with the stored
  value+signature. Because this is the *login* profile, the player's own client renders the override from
  spawn. Uses stored value+signature, so **no Mojang call** on join.

### Apply helper (shared)

```text
profile = target.getPlayerProfile()
if (name != null) profile.setName(name)
if (textures != null) { profile.getProperties().removeIf(p -> p.getName().equals("textures"));
                        profile.setProperty(new ProfileProperty("textures", value, signature)); }
target.setPlayerProfile(profile)
if (name != null) { target.playerListName(Component.text(name)); target.displayName(Component.text(name)); }
resend(target)   // for every other online player: hidePlayer(plugin,t); showPlayer(plugin,t)
```

`resend` runs hide/show one tick apart (scheduler) to avoid client glitches, and skips the target itself.

## 4. GUI wiring (`PlayerActionsGui`)

Three buttons in the free slots of row 4 (28–30), shown **only when `target.isOnline()`**:

- **Set name** (slot 28, `NAME_TAG`) → `profile-enter-name` prompt → `chatInput().await` → validate →
  `setName` → message.
- **Set skin** (slot 29, `PLAYER_HEAD`) → `profile-enter-skin` prompt → `chatInput().await` → `setSkin`
  with a callback that messages success/failure.
- **Reset profile** (slot 30, `WATER_BUCKET`) → `reset` → message.

Each is gated by a new permission `sentinel.profile` (default `op`); the click handler checks
`staffPerms().canUse(mod, "sentinel.profile")` first. Follows the existing close-inventory-then-prompt
pattern used by `awaitDuration`/Notes.

## 5. Permissions, config, messages

- New permission node `sentinel.profile` (default `op`) in `plugin.yml`.
- New `messages.yml` keys (+ German in `messages_de.yml`): `profile-enter-name`, `profile-enter-skin`,
  `profile-name-set`, `profile-skin-set`, `profile-skin-not-found`, `profile-bad-name`, `profile-reset`,
  `profile-target-offline`. Plus GUI labels `gui.actions.setname{,-lore}`, `setskin{,-lore}`,
  `resetprofile{,-lore}`.
- No `config.yml` changes.

## 6. Persistence flow

`LoginListener.onPreLogin` (existing) calls `profile.applyOnLogin(event)`, mutating the login profile
before the player spawns. This is what makes the target see their **own** persisted name+skin correctly
after a (re)login, with no packets — the override travels in the login profile itself.

## 7. Edge cases

- Invalid name (empty / >16 / illegal char) → `profile-bad-name`, nothing changed.
- Mojang username not found / Mojang offline / no textures → `profile-skin-not-found`, target unchanged.
- Target logs out during the async fetch → abort silently (re-check `isOnline()` before applying).
- Resetting with no override present → still safe (delete no-ops, real profile re-applied).
- `setPlayerProfile` keeps the real UUID; only the displayed name/textures change.

## 8. Testing

- `ProfileManager.isValidName` — pure unit tests (length, charset, blank).
- Textures property mapping — extract/replace the `textures` property correctly (unit, with a fake
  `PlayerProfile` or by testing the small pure mapping method).
- `ProfileOverrideDao` — upsert / find / delete against a temp SQLite DB (like `NoteDaoTest`).
- `applyOnLogin` wiring — build an `AsyncPlayerPreLoginEvent` with a profile (as `OwnerLoginProtectionTest`
  already does), store an override, and assert the manager mutates the event's profile name (and textures
  property where MockBukkit supports it).
- Live skin rendering and the above-head nametag are **manual** checks on a real server.

## 9. Files touched

- New: `model/ProfileOverride`, `storage/ProfileOverrideDao`, `manager/ProfileManager`,
  `ProfileManagerTest`, `ProfileOverrideDaoTest`.
- Changed: `Sentinel` (wire manager + DAO), `storage/SchemaMigrator` (+ `SqlDialect` schema),
  `gui/PlayerActionsGui` (3 buttons + handlers), `listener/LoginListener` (applyOnLogin in onPreLogin),
  `plugin.yml` (permission), `messages.yml` + `messages_de.yml` (keys), README (feature note).
