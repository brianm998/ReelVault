# analytics-ingest

Pulls install and download counts from App Store Connect, Google Play, and GitHub Releases into a local SQLite database. Run it daily from cron or launchd.

---

## Contents

- [Build](#build)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Getting credentials](#getting-credentials)
  - [App Store Connect](#app-store-connect)
  - [Google Play](#google-play)
  - [GitHub](#github)
- [Subcommands](#subcommands)
- [Example queries](#example-queries)
- [Scheduling](#scheduling)

---

## Build

```sh
cd tools/analytics-ingest
cargo build --release
# binary: target/release/analytics-ingest
```

Or run without installing:

```sh
cargo run --release -- --config /path/to/analytics.toml <subcommand>
```

---

## Quick start

```sh
# 1. Copy and edit the example config
cp tools/analytics-ingest/config.example.toml ~/.reelvault/analytics.toml
$EDITOR ~/.reelvault/analytics.toml

# 2. Point the tool at the config
export RV_ANALYTICS_CONFIG=~/.reelvault/analytics.toml

# 3. Create the database
analytics-ingest init

# 4. Test credentials before the first real fetch
analytics-ingest verify

# 5. Pull data
analytics-ingest fetch

# 6. Query
analytics-ingest report installs-30d
```

---

## Configuration

The config file is TOML. Pass it with `--config /path/to/file.toml` or set `$RV_ANALYTICS_CONFIG`.

Secret values are never written directly in the file. Instead, use:
- A **file path** — the tool reads the file at runtime.
- An **`${ENV_VAR}`** reference in the path value — expanded at startup.

```toml
[db]
# Where to store the SQLite database. ~ is expanded.
path = "~/.reelvault/analytics.sqlite"

[appstore]
enabled       = true
issuer_id     = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
key_id        = "XXXXXXXXXX"
vendor_number = "12345678"
# Path to the downloaded .p8 key file — keep it outside the repo.
private_key_path = "${APPSTORE_P8_PATH}"

[play]
enabled = true
package = "com.reelvault.android"
bucket  = "pubsite_prod_rev_0123456789"
service_account_json_path = "${PLAY_SA_JSON_PATH}"

[github]
enabled   = true
owner     = "reelvault"
repo      = "reelvault"
# Name of an env var whose value is the PAT (omit for unauthenticated, 60 req/h)
token_env = "GITHUB_TOKEN"
```

Disable a source by setting `enabled = false` — that section is then skipped entirely and its missing credentials are not an error.

---

## Getting credentials

### App Store Connect

You need three non-secret identifiers and one private key file (`.p8`).

**Where to find them:**

1. Sign in to [App Store Connect](https://appstoreconnect.apple.com).
2. Go to **Users and Access → Integrations → App Store Connect API** (top-level keys, not team-scoped).
3. If no key exists, click **+** to generate one. Choose the **Sales** role.
4. Download the `.p8` file — **you can only download it once**. Store it somewhere safe outside the repo (e.g. `~/.reelvault/AuthKey_XXXXXXXXXX.p8`).
5. Copy the **Key ID** (10-character string shown in the table).
6. Copy the **Issuer ID** (UUID shown at the top of the page above the key table).
7. Find your **Vendor Number**: go to **Payments and Financial Reports → Vendor ID** in the sidebar. It's the 8-digit number.

**Config values:**

| Config key | Where it comes from |
|---|---|
| `issuer_id` | Integrations → App Store Connect API → Issuer ID (UUID at top) |
| `key_id` | The 10-char Key ID in the keys table |
| `vendor_number` | Payments and Financial Reports → Vendor ID |
| `private_key_path` | Path to the downloaded `AuthKey_<key_id>.p8` |

**Env var approach:**

```sh
export APPSTORE_P8_PATH=~/.reelvault/AuthKey_XXXXXXXXXX.p8
```

Then in the config: `private_key_path = "${APPSTORE_P8_PATH}"`

> **Note:** Sales & Trends reports lag 24–48 hours. A fetch today will pull reports through two days ago. The tool handles this automatically; a 404 for a not-yet-available date is treated as "not ready" and silently skipped.

---

### Google Play

You need a **Google Cloud service account** with read access to the Play reporting bucket.

**Step 1 — Find your bucket name:**

1. Sign in to [Google Play Console](https://play.google.com/console).
2. Go to **Download reports → Statistics**.
3. At the bottom of the page, look for the `gs://` bucket URI — it looks like `gs://pubsite_prod_rev_0123456789`.
4. Copy everything after `gs://` — that's your `bucket` value.

**Step 2 — Create a service account:**

1. Go to [Google Cloud Console → IAM & Admin → Service Accounts](https://console.cloud.google.com/iam-admin/serviceaccounts).
2. Select (or create) the project linked to your Play Console account.
3. Click **Create Service Account**. Name it something like `reelvault-analytics-reader`.
4. Skip the optional role grants at the project level (you'll grant access at the bucket level in a moment).
5. Click **Done**, then open the new service account and go to the **Keys** tab.
6. Click **Add Key → Create new key → JSON**. Download the file — store it outside the repo.

**Step 3 — Grant the service account access to the bucket:**

1. Go to [Cloud Storage → Buckets](https://console.cloud.google.com/storage/browser) and find `pubsite_prod_rev_<id>`.  
   *(If the bucket doesn't appear in your project, use `gsutil` or the GCS API to confirm the name; it lives in a Google-managed project.)*
2. Click the bucket → **Permissions → Grant access**.
3. Add the service account email (looks like `reelvault-analytics-reader@<project>.iam.gserviceaccount.com`).
4. Assign the role **Storage Object Viewer**.

**Alternative — grant via Play Console:**

Some teams grant access directly in Play Console: **Users and Permissions → Invite new user**, enter the service account email, grant **View app information and download bulk reports**.

**Config values:**

| Config key | Value |
|---|---|
| `package` | Your Android package name, e.g. `com.reelvault.android` |
| `bucket` | The `pubsite_prod_rev_<id>` string (without `gs://`) |
| `service_account_json_path` | Path to the downloaded service account JSON |

```sh
export PLAY_SA_JSON_PATH=~/.reelvault/play-service-account.json
```

---

### GitHub

GitHub credentials are optional. Without a token the API allows 60 requests/hour — enough for a daily cron run on a repo with fewer than ~60 pages of releases (100 per page). With a token you get 5,000 requests/hour.

**Creating a fine-grained PAT:**

1. Go to **GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens**.
2. Click **Generate new token**.
3. Set expiration (1 year is reasonable for a cron job).
4. Under **Repository access**, select **Only select repositories** and pick your repo.
5. Under **Repository permissions**, grant **Contents: Read-only**. No other permissions are needed.
6. Click **Generate token** and copy it.

**Config:**

```toml
[github]
token_env = "GITHUB_TOKEN"   # the tool reads os.env["GITHUB_TOKEN"]
```

```sh
export GITHUB_TOKEN=github_pat_...
```

Or omit `token_env` entirely to run unauthenticated.

---

## Subcommands

### `init`

Create the database and run schema migrations. Safe to re-run.

```sh
analytics-ingest init
```

### `fetch`

Pull new data from one or all sources and upsert into the database.

```sh
# All sources
analytics-ingest fetch

# One source
analytics-ingest fetch --source github
analytics-ingest fetch --source appstore
analytics-ingest fetch --source play

# Override the start date (re-fetch from a specific date forward)
analytics-ingest fetch --source appstore --since 2025-01-01
```

Each source failure is isolated — if the App Store fetch fails, the Play and GitHub fetches still run. The result of each run is logged in the `fetch_runs` table.

GitHub is always snapshotted on every `fetch` run (the API only exposes a cumulative total, so daily snapshots are how a time series is built).

### `report`

Run a canned query and print results to stdout.

```sh
analytics-ingest report installs-30d          # installs by platform + channel, last 30 days
analytics-ingest report installs-daily        # day-by-day trend across all platforms
analytics-ingest report android-installed-base # current Android active device installs
analytics-ingest report github-by-platform    # GitHub downloads by platform + release tag
analytics-ingest report total-installs        # grand total (Layer-2 "% opted in" denominator)
analytics-ingest report fetch-runs            # recent run history with status
```

### `verify`

Check that credentials are valid and the APIs are reachable. Makes no writes.

```sh
analytics-ingest verify
```

Run this after initial setup and after rotating any key. It exits non-zero if any enabled source fails.

---

## Example queries

Open the database directly with any SQLite client:

```sh
sqlite3 ~/.reelvault/analytics.sqlite
```

**Installs per platform, last 30 days:**
```sql
SELECT platform, channel, SUM(installs) AS installs
FROM installs_unified
WHERE date >= date('now','-30 day')
GROUP BY platform, channel
ORDER BY installs DESC;
```

**Daily install trend, all platforms:**
```sql
SELECT date, SUM(installs) AS total
FROM installs_unified
GROUP BY date ORDER BY date;
```

**Total installs across all sources (Layer-2 denominator):**
```sql
SELECT SUM(installs) FROM installs_unified;
```

**Current Android installed base:**
```sql
SELECT date, active_device_installs
FROM play_installs_daily
WHERE dimension_type = 'overview'
ORDER BY date DESC LIMIT 1;
```

**GitHub downloads by asset platform:**
```sql
SELECT platform, release_tag, SUM(downloads_delta) AS downloads
FROM github_asset_daily_delta
WHERE snapshot_date >= date('now','-30 day')
GROUP BY platform, release_tag
ORDER BY downloads DESC;
```

**App Store installs split iOS vs. macOS:**
```sql
SELECT report_date, platform, SUM(units) AS installs
FROM apple_sales_daily
WHERE product_type IN ('1','1F','1E','1EP','1EU')
  AND report_date >= date('now','-30 day')
GROUP BY report_date, platform
ORDER BY report_date, platform;
```

**Play install breakdown by country:**
```sql
SELECT dimension_value AS country, SUM(daily_device_installs) AS installs
FROM play_installs_daily
WHERE dimension_type = 'country'
  AND date >= date('now','-30 day')
GROUP BY country
ORDER BY installs DESC
LIMIT 20;
```

**Fetch run history with row counts:**
```sql
SELECT source, started_at, status, rows_ingested, detail
FROM fetch_runs
ORDER BY id DESC
LIMIT 10;
```

---

## Scheduling

**launchd (macOS)** — create `~/Library/LaunchAgents/com.reelvault.analytics-ingest.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.reelvault.analytics-ingest</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/local/bin/analytics-ingest</string>
    <string>--config</string>
    <string>/Users/you/.reelvault/analytics.toml</string>
    <string>fetch</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>APPSTORE_P8_PATH</key>
    <string>/Users/you/.reelvault/AuthKey_XXXXXXXXXX.p8</string>
    <key>PLAY_SA_JSON_PATH</key>
    <string>/Users/you/.reelvault/play-service-account.json</string>
    <key>GITHUB_TOKEN</key>
    <string>github_pat_...</string>
  </dict>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Hour</key>
    <integer>8</integer>
    <key>Minute</key>
    <integer>0</integer>
  </dict>
  <key>StandardOutPath</key>
  <string>/Users/you/.reelvault/analytics-ingest.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/you/.reelvault/analytics-ingest.log</string>
</dict>
</plist>
```

```sh
launchctl load ~/Library/LaunchAgents/com.reelvault.analytics-ingest.plist
```

**cron (Linux)** — add to crontab (`crontab -e`):

```cron
0 8 * * * APPSTORE_P8_PATH=~/.reelvault/AuthKey_XXXXXXXXXX.p8 \
           PLAY_SA_JSON_PATH=~/.reelvault/play-service-account.json \
           GITHUB_TOKEN=github_pat_... \
           /usr/local/bin/analytics-ingest --config ~/.reelvault/analytics.toml fetch \
           >> ~/.reelvault/analytics-ingest.log 2>&1
```

> Reports are typically available 24–48 hours after the day ends, so running at 08:00 local time gives the previous day's reports time to appear.
