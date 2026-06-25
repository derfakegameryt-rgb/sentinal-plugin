# Update CDN — one-time setup (Vercel + GitHub Action)

The plugin already works **without** any of this: it tries the Vercel CDN first and automatically
falls back to the GitHub release repo if the CDN is unreachable or not configured. These steps just
turn the CDN on so the normal update check stops hitting GitHub's rate-limited API.

## What you get

```
Minecraft server ──► https://<your-domain>/latest.json   (tiny JSON, no GitHub rate limit)
                 ──► https://<your-domain>/<jar>          (the mirrored jar, hash-verified)
On every release: GitHub Action ──► downloads the jar ──► deploys both to Vercel
```

---

## Step 1 — Create the Vercel project

1. Sign in at <https://vercel.com> (GitHub login is fine).
2. **Add New… → Project**. You can import any empty repo, or just create a blank project — the Action
   deploys the files, so the project does not need source.
3. Note the production domain it gives you, e.g. `sentinal-plugin.vercel.app`
   (Project → Settings → Domains). You can also add a custom domain here.

## Step 2 — Get the three Vercel IDs

1. **Token:** Vercel → top-right avatar → **Account Settings → Tokens → Create Token**. Copy it.
2. **Org & Project IDs:** open the project → **Settings → General**, or run locally:
   ```bash
   npm i -g vercel
   vercel link        # pick the project; this writes .vercel/project.json
   cat .vercel/project.json   # shows "orgId" and "projectId"
   ```

## Step 3 — Add the GitHub Action secrets

In the GitHub repo where releases are **published** → **Settings → Secrets and variables → Actions →
New repository secret**, add:

| Secret | Value |
|--------|-------|
| `VERCEL_TOKEN` | the token from Step 2 |
| `VERCEL_ORG_ID` | `orgId` from Step 2 |
| `VERCEL_PROJECT_ID` | `projectId` from Step 2 |
| `CDN_DOMAIN` | your domain **without** `https://`, e.g. `sentinal-plugin.vercel.app` |

## Step 4 — Install the workflow

Copy [`mirror-release.yml`](./mirror-release.yml) to `.github/workflows/mirror-release.yml` in that same
repo and commit it.

- **Recommended (automatic):** put it in the **public release repo** so it fires on every
  `release: published` with zero extra steps. The workflow names nothing about the owner feature —
  it only downloads the release jar and deploys it to a CDN.
- **If you prefer to keep even that private:** put it in a private repo and trigger it from your
  release flow instead (e.g. `gh workflow run mirror-release.yml`), or on a schedule. The repo just
  needs `gh` access to download the public release asset.

## Step 5 — Point the plugin at your domain

In [`UpdateChecker.java`](../src/main/java/de/derfakegamer/sentinel/updater/UpdateChecker.java), set
the `MANIFEST` constant to your domain:

```java
private static final String MANIFEST = "https://sentinal-plugin.vercel.app/latest.json";
```

Then build and release as usual. Tell the assistant your final domain and it can do this swap for you.

---

## Verify it works

After your next release, the Action run should be green and:

```bash
curl https://<your-domain>/latest.json
# {"version":"v3.2.5","url":"https://<your-domain>/Sentinel-3.2.5.jar","sha256":"…","size":…}
```

The plugin will fetch that JSON instead of GitHub. If the CDN ever 404s or goes down, it silently
falls back to the GitHub repo — updates never stop.

## Alternative deploy method (no Action)

If you'd rather use Vercel's built-in Git integration instead of the Action: connect the Vercel project
to a repo, commit [`vercel.json`](./vercel.json) at its root plus `latest.json` and the jar, and every
push redeploys. The Action is simpler for an automatic per-release mirror, which is why it's the default.
