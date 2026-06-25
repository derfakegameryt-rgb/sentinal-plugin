# Vercel update CDN + GitHub fallback — Design Spec

**Date:** 2026-06-25
**Status:** Approved (user: full mirror, automatic GitHub Action, GitHub fallback)

## Problem

The auto-updater polls `https://api.github.com/repos/derfakegameryt-rgb/sentinal-plugin/releases?per_page=100`
every 5 minutes. `api.github.com` is rate-limited to 60 requests/hour per IP for unauthenticated
callers. The actual jar download (`browser_download_url` → `objects.githubusercontent.com`) is a CDN
and is **not** meaningfully rate-limited — only the JSON listing call is. Under restarts / shared NAT /
many servers this 60/hr ceiling is hit, and the update check fails.

## Goal

Move the version-check (and, for full independence, the jar) onto a Vercel static CDN that the plugin
queries instead of the GitHub API — while keeping GitHub as an automatic fallback so updates never stop
working, even if the CDN is down or never configured.

## Global Constraints

- Minecraft 1.21 (Paper/Folia), Java 21, Gradle + shadow. `spotlessCheck` runs in `build` and FAILS on
  unused imports (4-space indent, no reformat of untouched code).
- **No new dependencies.** SHA-256 via `java.security.MessageDigest`; HTTP via the existing `HttpClient`.
- The updater is a quiet feature: scheduled-run failures stay at `FINE` (no console spam). The CDN→GitHub
  fallback is logged only at `FINE`.
- Secrecy: nothing in the CDN, manifest, or workflow names the owner feature. The manifest is plain
  release metadata (`version`, `url`, `sha256`, `size`).

## Layer 1 — Plugin (`UpdateChecker`) — works immediately, no Vercel needed

- New primary source constant `MANIFEST = "https://<vercel-domain>/latest.json"` (placeholder until the
  user supplies the real production domain).
- **Manifest format:**
  ```json
  { "version": "3.2.5", "url": "https://<domain>/sentinel.jar", "sha256": "<hex>", "size": 245678 }
  ```
- New static `parseManifest(json)` → `[version, jarUrl, sha256OrNull]`, or `null` if `version`/`url`
  are missing.
- New `resolveLatest()`:
  1. `httpGet(MANIFEST)` → `parseManifest`. On success return it.
  2. On **any** failure (unreachable, non-2xx, unparseable, missing fields) → log `FINE` and fall back
     to `httpGet(API)` → existing `parseBestRelease` → `[tag, jarUrl]`, sha256 = null.
- `check()` uses `resolveLatest()`; everything downstream (version compare, dedupe, atomic download,
  `validateJar`, invisible scheduled run) is unchanged.
- `download(url, dest, expectedShaOrNull)`: after the bytes land in the temp file and **before**
  `validateJar`, if `expectedSha != null`, verify SHA-256 (`verifySha256`) — a stronger integrity check
  than today's structure-only validation. GitHub-fallback downloads (sha == null) skip this step and
  rely on `validateJar` as before.

### Behaviour when Vercel is not set up
DNS/404 on the manifest → `FINE` log → GitHub fallback → identical to today. No regression, no
user-visible change. Hardcoding the placeholder domain costs at most one failed request per 5-minute
cycle until the real domain is live.

## Layer 2 — CDN population (user activates) — staged as deliverables, not pushed

Staged under `cdn/` in this repo as templates + a setup guide; **not** auto-pushed anywhere (the
workflow's home repo is a secrecy decision left to the user).

- `cdn/vercel.json` — static config, long-cache headers for the jar, short-cache for `latest.json`.
- `cdn/mirror-release.yml` — GitHub Action: `on: release: { types: [published] }` →
  download the release `.jar` asset → compute sha256 + size → write `public/latest.json` +
  `public/sentinel.jar` → `vercel deploy --prod` (CLI; `VERCEL_TOKEN`/`VERCEL_ORG_ID`/`VERCEL_PROJECT_ID`
  from repo secrets).
- `cdn/SETUP.md` — exact one-time steps: create the Vercel project, mint the token, capture ORG/PROJECT
  IDs, add the three GitHub secrets, choose where the workflow lives (public release repo = automatic on
  `release: published`, recommended; or private repo triggered from the release flow), and report the
  production domain so the `MANIFEST` constant can be set.

## Testing

- `parseManifest` happy path (version + url + sha256), and `null` when `version`/`url` missing.
- `verifySha256` accepts a matching digest, rejects a mismatch.
- Existing `parseBestRelease` / `validateJar` / integration tests stay green (fallback path unchanged).

## Known tradeoffs (accepted)

- **Stale CDN beats GitHub:** if the CDN serves a *valid* manifest whose `version` is not newer than
  what GitHub has (a half-failed deploy that left an old `latest.json`), `resolveLatest()` returns it
  and does not consult GitHub — the server can stay on an older version until the next successful
  deploy. The mirror Action is the single writer and overwrites `latest.json` every release, and the
  manifest is cached only 60s, so the window is small. Any CDN *failure* (down/404/malformed) still
  falls back to GitHub.
- **Root of trust moves to the CDN host:** with the CDN primary, the trust anchor is the hardcoded
  `MANIFEST`/`API` HTTPS hosts. The `sha256` check guards against accidental corruption only — the hash
  ships in the same manifest as the url, so it is not a defense against a compromised/typosquatted CDN.
  `validateJar` (structural: must be a jar with a `name: Sentinel` plugin.yml) is the only barrier
  against a wrong jar. True protection would be jar code-signing (out of scope, noted for later).
- **Older jar URLs 404 after each deploy:** each `vercel deploy ./site` deploys only the current jar +
  manifest, so previous versioned jar URLs stop resolving. Harmless because the plugin always reads
  `latest.json` first and only ever wants the newest jar.
- The manifest `version` ships exactly as the release tag (e.g. `v3.2.5`); `Version.parse` strips a
  leading `v`, so both `v3.2.5` and `3.2.5` compare correctly.

## Out of scope

- Serverless proxy/caching of the GitHub API (we mirror statically instead).
- Auto-creating the Vercel project (requires the user's account; handed off via `cdn/SETUP.md`).
