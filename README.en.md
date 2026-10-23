# DroidServer

[Türkçe](README.md) | **English**

An app that turns an Android device into a backend server configured to the user's preferences.
A Kotlin + Ktor + SQLite core runs inside the app; side services such as PostgreSQL, Redis and cloudflared
can optionally be downloaded and managed.

> **Distribution:** outside the Play Store (APK / GitHub Releases / F-Droid). `targetSdk = 28` is a deliberate choice;
> the reason is explained below.

## Architecture

```
┌──────────────────────────── app (Android) ────────────────────────────┐
│  MainActivity (Compose)     ServerService (foreground)   BootReceiver │
│  Status · Server ·          wake/wifi lock,              auto-start   │
│  Components · Logs          notification, deploy loop    on boot      │
│  Wizard · JSON · History ·                                            │
│  API explorer (WizardViewModel: shared draft)                         │
│                 │                   │                                 │
│  ConfigRepository (server.json + history)  AndroidSqlDriver           │
│  SecretStore (secrets encrypted with Android Keystore)                │
└─────────────────┼───────────────────┼─────────────────────────────────┘
                  ▼                   ▼
┌──────────────────────────── core (pure Kotlin/JVM) ───────────────────┐
│  ServerConfig ◄── ConfigMigrations (v1 → v3), ConfigSecrets           │
│       │                                                               │
│       ▼                                                               │
│  DeployOrchestrator.deploy()                                          │
│    ├─ resolve secrets + ConfigValidator                               │
│    ├─ components: ComponentManager + ProcessSupervisor                │
│    │    (only changed ones restart) → ReadinessProbe                  │
│    ├─ ServerEngine.deploy()                                           │
│    │    ├─ SchemaMigrator: plan() → apply()  (via SqlDialect)         │
│    │    └─ droidServerModule (Ktor)                                   │
│    │         ├─ automatic CRUD endpoints                              │
│    │         ├─ API key / JWT                                         │
│    │         └─ CORS, error handling                                  │
│    └─ stop components that are no longer wanted                       │
│  ComponentManager (download, sha256, extract, first-time setup)       │
│  ProcessSupervisor (start, monitor, restart with backoff)             │
└───────────────────────────────────────────────────────────────────────┘
```

**Core idea:** Instead of generating code and compiling it on the device, the server interprets a single configuration
file (`server.json`) at runtime. "Deploy" = resolve and validate secrets → sync components →
wait for those with `readiness` defined to become ready → plan and apply the schema → restart the server →
stop components removed from the configuration. If validation or migration fails (including a blocking schema
issue), the running server is left untouched.

The `core` module has no Android dependency; all business logic is tested on a computer.

## Building

Requirements: JDK 17+, Android SDK (platform 37). Opening the project in Android Studio is enough.

```bash
./gradlew :core:test           # core tests (on a computer, real SQLite + PostgreSQL + HTTP)
./gradlew :app:testDebugUnitTest  # wizard ViewModel tests (JVM, no emulator needed)
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Database tests are parameterized for SQLite and PostgreSQL. PostgreSQL is started as a real server via
`io.zonky.test:embedded-postgres` (binaries are downloaded from Maven on the first run). Postgres refuses to run as root;
if the tests are run as root (e.g. in a Docker container with a root user), a warning is printed and the Postgres processes are
automatically run as the non-root user `nobody` (requires `runuser`; see `NonRootPostgres.kt`).

Toolchain: Gradle 9.8, AGP 9.4 (built-in Kotlin), Kotlin 2.4, Ktor 3.6, Compose BOM 2026.09.

## Configuration (`server.json`)

On first launch, if there is no configuration, the **wizard** opens (starting draft `app/src/main/assets/default-config.json`,
with a freshly generated key instead of the placeholder key). After that it is edited from the **Server** tab with the wizard or
with **Advanced (JSON)**; both share the same draft, and the draft is saved to `files/config/draft.json`
(it is not lost when the app closes). On every save the previous version is backed up under `files/config/history/` (last 20).

```json
{
  "version": 9,
  "server": { "port": 8080, "basePath": "/api", "cors": { "enabled": true, "allowedHosts": ["*"] } },
  "auth":   { "mode": "api_key", "apiKeys": ["secret:api_key_3f9a2c1d"],
              "users": { "enabled": true, "allowSignup": true,
                         "extraFields": [ { "name": "name", "type": "text", "required": true } ],
                         "publicFields": ["name"] } },
  "database": { "engine": "sqlite", "file": "data.db" },
  "collections": [
    { "name": "todos", "ownerField": "owner",
      "rules": { "list": "owner", "view": "owner", "create": "auth", "update": "owner", "delete": "owner" },
      "fields": [
        { "name": "title", "type": "text", "required": true },
        { "name": "done",  "type": "boolean", "default": false },
        { "name": "due",   "type": "datetime" },
        { "name": "owner", "type": "relation", "collection": "_users", "onDelete": "cascade" } ] },
    { "name": "notes",
      "rules": { "list": "public", "view": "public", "create": "admin", "update": "admin", "delete": "admin" },
      "fields": [
        { "name": "slug", "type": "text", "required": true, "unique": true },
        { "name": "body", "type": "text" } ] }
  ],
  "access": { "mode": "cloudflare", "cloudflare": { "tunnel": "quick" } },
  "components": [ { "id": "cloudflared" } ]
}
```

| Field | Description |
|---|---|
| `version` | Version of the file format (current: 16). New installs and files created with the wizard always get the current version; the upgrade steps below apply only to files created by older app versions (a file without a `version` field counts as v1). An old file is converted in memory and used, but is not rewritten on disk automatically; the app asks "Upgrade the configuration version?" (see below). A newer version is rejected |
| `auth.mode` | `none`, `api_key` (Bearer key), `jwt` (HS256; the token is generated in the app) |
| `auth.apiKeys`, `auth.jwtSecret` | May be written as plain text; on save they are moved to the encrypted store and replaced with a `"secret:<name>"` reference |
| `database.engine` | `sqlite` (default, `database.file`) or `postgres` (`database.url`, `database.user`, `database.password`) |
| `database.password` | A `secret:<name>` reference; if written as plain text, it is moved to the encrypted store on save |
| `fields[].type` | `text`, `integer`, `real`, `boolean`, `datetime` (ISO-8601), `json`, `relation`, `file`, `select` (one value from a list), `multiselect` (list of options, e.g. `["a","b"]`) |
| `fields[].options` | `select`/`multiselect`: options (at most 200; may not contain `"` or `\`) |
| `fields[].format`, `minLength`, `maxLength`, `pattern` | `text`: `email` or `url` format, character count limits, regular expression (must match the whole value). For `multiselect`, `minLength`/`maxLength` is the number of selections |
| `history` | `{keep: 50}`: record history (at most `keep` versions per record; see History and trash) |
| `trash` | `{days: 30}`: trash (a deleted record is kept for `days` days) |
| `fields[].min`, `max` | `integer`/`real`: value range |
| `fields[].computed` | `text`/`integer`/`real`/`boolean`: value computed from other fields (see Field rules and computed fields) |
| `fields[].maxSizeMb`, `mimeTypes`, `multiple`, `thumbs` | `file`: per-file limit (10 MB), accepted types (e.g. `["image/*", "application/pdf"]`; all if empty), multiple files (at most 20), extra thumbnail sizes (e.g. `["400x0"]`; `100x100` always) |
| `static` | `enabled`, `path` (`/`), `spaFallback` (true): serves the site under `files/www/` (see Files and static site) |
| `backup` | `enabled`, `schedule` (cron, `0 4 * * *`), `keep` (7): scheduled backup (see Backup) |
| `adminPanel` | `enabled`, `sessionHours` (12): the `/_admin` panel in the browser (see Web admin panel) |
| `scripts` | `enabled`, `timeoutMs` (2000), `cronTimeoutSec` (60), `allowedHosts` (for `ctx.http`), `env` (added to `ctx.env` on top of the shared `env`; may hold `secret:` references) — see Scripts |
| `fields[].collection` | Target collection of a `relation` field (may be the collection itself: a tree structure) |
| `fields[].onDelete` | `relation`: when the target record is deleted, `cascade` (these are deleted too), `set_null` (the field is cleared), `restrict` (the delete is rejected with 409). If empty, `restrict` for a required field, otherwise `set_null` |
| `rules` | For `list`, `view`, `create`, `update`, `delete`: `public` (everyone), `auth` (signed-in user), `owner` (owner of the record), `team` (members of the record's team), `admin` (default), `nobody` (closed; not even the admin can). v5's `publicRead`/`readOnly` are converted to this |
| `fields[].index` | Non-unique index: if you frequently filter/sort by this field (relation and unique fields are already indexed; file and JSON fields cannot be indexed) |
| `search` | Text fields to search with `?q=` in the collection (at most 10; see Search) |
| `fullText` | Full-text index: `?q=` by word prefix, from the index and ordered by relevance (see Search) |
| `realtime` | `enabled`, `maxConnections` (100): realtime subscriptions (see Realtime subscriptions) |
| `webhooks` | `[{name, url, collections, events, secret, enabled}]`: signed POST on record events (see Webhooks) |
| `email` | `enabled`, `host`, `port` (587), `security` (`starttls`, `ssl`, `none`), `username`, `password` (in the encrypted store), `from`, `fromName`, `publicUrl` (prefix of links in e-mails) — see E-mail |
| `auth.users.sendVerification`, `requireVerified` | Verification e-mail on sign-up (true); no sign-in until verified (false; e-mail must be enabled) |
| `ownerField` | Relation field linked to `_users` that holds the record's owner; required for the `owner` rule (see Users and rules) |
| `teamField` | Relation field to `_teams` holding the record's team; required for the `team` rule (see Teams) |
| `auth.users` | `enabled`, `allowSignup` (true), `extraFields` (such as name, phone; cannot be relations), `publicFields` (extra fields visible to others), `minPasswordLength` (8), `accessTokenMinutes` (15), `refreshTokenDays` (30) |
| `auth.users.teams` | `enabled`, `allowCreate` (true), `maxMembers` (100), `maxTeamsPerUser` (20): teams (see Teams) |
| `access.mode` | `local`, `direct`, `cloudflare`, `tailscale` (see Internet access) |
| `access.direct` | `network` (`wifi`/`mobile`), `port` (HTTPS, default 8443), `portMapping` (automatic forwarding on the router), `ddns` (`domain`, `token`: DuckDNS; `certificate`: Let's Encrypt certificate for this name) |
| `access.cloudflare` | `tunnel`: `quick` (no account) or `named` (token in the `TUNNEL_TOKEN` environment of the `cloudflared` component), `hostname` (for display) |
| `access.allowNoAuth` | Explicitly accepts running without authentication in an internet-facing mode (otherwise the deploy is rejected) |
| `server.limits` | `maxBodyKb` (1024), `requestsPerMinute` (600, 0: unlimited), `authFailures` (10), `banMinutes` (15) |
| `env` | Shared environment variables (`{"AD": "değer"}`; secrets as `secret:<name>`): used by scripts (`ctx.env`), apps and components with `sharedEnv` enabled (see Environment and secrets) |
| `apps` | Your own apps: `name`, `runtime`, `command`, `build`, `port`, `path`, `access`, `health`, `env`, `git` … (see Your own apps) |
| `automations` | No-code automation rules: `name`, `trigger` (`collection` + `events` or `schedule`), `condition`, `actions` (see Automation rules) |
| `forms` | Public forms: `name` (URL `/forms/<name>`), `collection`, `title`, `fields`, `successMessage`, `redirect` … (see Forms) |
| `ai` | AI: `enabled`, `provider` (`claude`/`openai`/`gemini`), `url`, `apiKey` (`secret:`), `model`, `effort`, `access`, `dailyLimit`, `embeddings`, `semantic` (see AI) |
| `webdav` | Network drive: `enabled`, `path`, `username`, `password` (`secret:`), `readOnly`, `site`, `scripts`, `uploads`, `maxFileMb` (see Network drive) |
| `mqtt` | MQTT (IoT): `enabled`, `port` (1883), `username`, `password` (`secret:`), `routes` (`topic`, `collection`, `valueField`, `topicField`), `events`, `eventPrefix`, `maxClients` (see MQTT) |
| `replication` | Live copy: `source`, `keepDays` (7), `replicaOf` (`url`, `key`, `intervalSec`), `failover` (`auto`, `afterSec` (120), `witness`, `failback`), `formerPrimary` (see Live copy) |
| `components[].sharedEnv` | true: the component also receives the shared environment variables (its own `env` takes precedence) |

Every table automatically gets `id`, `created_at` and `updated_at`. Migrations do not delete data:
fields/tables removed from the configuration stay in place and are reported as "unused" in the log.

Before a deploy, a schema plan is produced (`SchemaMigrator.plan()`, does not write to the database). Adding `unique`
to a field containing duplicate data is **blocking**, and the deploy is rejected. Adding `required` while existing records
have empty values is only a warning (the rule is enforced on new writes at the API layer).

**Type change:** if the type of a field that has stored values changes, the user chooses what happens to the values;
without a choice the plan is blocking. Field types are kept in the `_ds_meta` table, so changes like `text → json`
that map to the same column type in SQLite are also detected. No choice is needed if the column is empty.

```json
{ "name": "views", "type": "integer", "typeChange": { "from": "text", "action": "convert" } }
```

| `action` | Result |
|---|---|
| `convert` | Values are converted to the new type; those that cannot be converted are cleared. The plan shows how many values will be cleared, with examples (`'abc'`). Rules: text → number only exact numbers (`42`, `2.0`), → boolean `true/false/1/0`, → datetime ISO-8601 (UTC if no time zone), → JSON valid JSON as is, plain text becomes a JSON string. Meaningless conversions such as number → datetime are not offered. |
| `clear` | All values in the field are deleted; the records' other fields are kept. |

The choice is applied only if the type in the database is `from`; a later type change asks for a new choice.
If converted values collide in a `unique` field (`"1"` and `"01"` → `1`), the plan is blocking. The column is
rebuilt with the new type (PostgreSQL: temporary column + `DROP/RENAME COLUMN`; since Android 8's SQLite lacks these
commands, the table is rebuilt, preserving indexes and the id counter).

**Configuration version:** when a field is added to or removed from the configuration, `version` is incremented and
a conversion step is added to `ConfigMigrations`, along with a description to show the user. If the active file is in an old
format, the Status screen shows an "Upgrade the configuration version?" card (with the list of changes; **Upgrade
version** / **Not now**), and the Server screen always shows an **Upgrade version** button. A file that is not upgraded
stays compatible with older app versions; on upgrade, the previous state is backed up to History. Saving from the wizard
already writes the file in the current format (noted in the Summary step).

| Step | Change |
|---|---|
| v1 → v2 | API keys and the JWT secret are moved to the encrypted store; only their references remain in the file |
| v2 → v3 | Optional `typeChange` and `database.createIfMissing`; existing settings do not change |
| v3 → v4 | Access settings move to the `access` section (enabled `cloudflared` → `cloudflare`, `named` if a token exists); direct IP (HTTPS) and quick tunnel options; the Tailscale component is removed (the Tailscale app is used) |
| v4 → v5 | `relation` field type (`collection`, `onDelete`) and `server.limits`; existing settings do not change |
| v5 → v6 | `publicRead` / `readOnly` flags become `rules` (behavior stays the same); user accounts (`auth.users`) and `ownerField` |
| v6 → v7 | `file` field type and static site (`static`); existing settings do not change |
| v7 → v8 | Scripts (`scripts`); existing settings do not change |
| v8 → v9 | Scheduled backup (`backup`) and web admin panel (`adminPanel`); existing settings do not change |
| v9 → v10 | Let's Encrypt certificate for the DuckDNS name; existing settings do not change |
| v10 → v11 | Realtime subscriptions, field indexes and search, e-mail (verification, password reset); existing settings do not change |
| v11 → v12 | Webhooks, conditional rules, scoped API keys, social sign-in, web push; existing settings do not change |
| v12 → v13 | `select`/`multiselect` types, field constraints, computed fields, history and trash, quotas, two-factor authentication, full-text index, cloud backup; Phase 13's audit log, firewall, health alerts, local network discovery and live replica sections; existing settings do not change |
| v13 → v14 | Custom roles, encrypted fields (`encrypted`, `database.fieldKey`), retention period, views and reports, WebSocket channels, phone features; existing settings do not change (the version is bumped because an older version would treat the encrypted value as plain text) |
| v14 → v15 | Shared environment variables (`env`), your own apps (`apps`, including Git source), `components[].sharedEnv`; existing settings do not change |
| v15 → v16 | Automation rules (`automations`), public forms (`forms`), GraphQL (`api.graphql`), AI (`ai`); existing settings do not change |

**Secrets:** API keys and the JWT secret are not kept as plain text in `server.json`. When saving
(and when upgrading the file version, including history copies), the app encrypts the values with an Android Keystore key and
moves them to `SecretStore`, writing `"secret:<name>"` to the file; references are resolved at deploy time. To see the key,
use the wizard's Security step or the **Client access / token** button on the Status screen.
Component environment variables can also hold references; the wizard stores the tunnel token (`TUNNEL_TOKEN`) and the DuckDNS token
(`access.direct.ddns.token`) this way.

### Ready-made apps

Wizard → Collections → **Ready-made app** (or Profiles → New profile → Start) sets up a working app
in one tap: collections, relations, rules, optional sample records and a single-page website that uses the API
(sample data and the site are installed after the deploy).

| Template | Collections | Who does what |
|---|---|---|
| Blog | `categories`, `posts`, `comments` | Everyone reads published posts (full-text search) and leaves comments; a comment appears once `approved` |
| Shop and orders | `products`, `orders` | Everyone sees the products on sale; customers who create an account place orders and see only their own orders |
| Appointment | `services`, `appointments` | Everyone requests appointments without an account; only the admin sees and approves the requests |
| Task tracking | `projects`, `tasks` | Each user sees only their own projects and tasks (history, trash) |

When adding to an existing configuration, a collection is not added if one with the same name exists (install it as a new profile). Sample records are
added only to empty collections; if the site is selected, `files/www/index.html` is written (with a warning if a site already exists).

## Wizard and management screens

The wizard has seven steps; if a step is not valid, **Next** is disabled and the error appears under the relevant field
(`ConfigValidator.validateDetailed()` returns errors with field paths like `collections[0].fields[1].name`).

1. **General:** server name, port (below 1024 blocked), API path; optional static site (path, `index.html` for missing pages).
2. **Database:** SQLite (file name) or PostgreSQL (component installation and data migration happen in this step; see PostgreSQL);
   scheduled backup (every night / every 6 hours / weekly or cron, number to keep).
3. **Users:** end-user accounts on/off, whether anyone can sign up, minimum password length, extra fields
   (including whether they are publicly visible). If authentication is off when enabling it, an API key is generated for the admin.
4. **Collections:** ready-made templates (To-dos, Blog posts, Products, Photos, Empty); for each collection: name,
   **who can do what** (presets: admin only / everyone reads, admin writes / everyone reads, users add, owners
   edit / everyone sees only their own records; or a rule per operation) and the record owner field ("Add owner
   field"); fields are reordered by drag and drop, and a default value matching the type can be entered. For a file field, the size limit,
   accepted files (any type / images / images and PDF / PDF / audio / video / custom), multiple files and thumbnail
   sizes are chosen.
   Deleting a field on the running server is shown with a warning; changing its type asks for a
   **Convert / Clear** choice for stored values (with an explanation of which values conversion will convert).
5. **Security:** authentication mode; the API key/JWT secret is generated with `SecureRandom` (Regenerate, Copy).
   Scripts: on/off, time limit, addresses `ctx.http` may send requests to. Web admin panel on/off.
   If the publicly known sample key (`degistir-beni-…`) is used, a warning is shown; whether to use it or not is
   the user's decision. CORS: off / everyone / host list.
6. **Access:** local network only, directly from the internet (IP), Cloudflare Tunnel or Tailscale; depending on the chosen path,
   what the app does, the steps the user must take and possible issues are shown (see Internet access).
   In an internet-facing mode without authentication, either authentication is chosen in the Security step or the risk is explicitly accepted.
7. **Summary and deploy:** a readable summary, a schema plan against the running database ("2 tables will be created …");
   if there is a blocking issue, the deploy button is disabled and the reason is shown. After the deploy, Logs opens.

The **Server** tab shows a summary of the active configuration. On the **History** screen, selecting one version shows its diff
against the current one, and selecting two versions shows the diff between them; **Revert to this version** shows the schema plan first. The **API explorer**
provides `curl` examples prepared with the real key for each collection and sends test requests from the device.
If user accounts are enabled, the **Users** screen lists the running server's accounts: search by e-mail, account
creation, password reset (no e-mail is sent; you pass the new password to the user yourself, and all their sessions are signed out),
making admin, the verified flag and deletion. If the static site is enabled, a zip is uploaded from the **Static site** card on the Server screen
(it replaces the old one, no redeploy needed) or the site is removed. The **Status** screen shows storage usage
(database, uploaded files, site, free space on the phone). On the **Scripts** screen, .js files under `files/scripts/`
are written (sample script, syntax check, "Save and deploy"). On the **Backups** screen, backups are taken,
exported and restored, and the folder that backups are automatically copied to is chosen. The Status screen shows a request
chart for the last hour, the battery state and (if enabled) the web panel pairing code; a notification is sent if the battery exceeds 45 °C.

## API

```bash
H='Authorization: Bearer en-az-16-karakterlik-anahtar'
BASE=http://192.168.1.50:8080/api

curl $BASE/health
curl -H "$H" -X POST $BASE/collections/todos/records \
     -H 'Content-Type: application/json' -d '{"title":"Süt al","tags":["market"]}'
curl -H "$H" "$BASE/collections/todos/records?done=false&sort=-created_at&page=1&perPage=20"
curl -g -H "$H" "$BASE/collections/todos/records?priority[gte]=3&title[like]=süt&due[null]=false&fields=title,due"
curl -H "$H" "$BASE/collections/posts/records?author=1&expand=author"
curl -H "$H" -X PATCH  $BASE/collections/todos/records/1 -H 'Content-Type: application/json' -d '{"done":true}'
curl -H "$H" -X DELETE $BASE/collections/todos/records/1
curl -H "$H" $BASE/_schema
```

**Filters** (`field=value` means equals; `field[op]=value`; conditions are combined with AND):

| op | Meaning | Field types |
|---|---|---|
| `eq`, `ne` | equal / not equal (`ne` also returns empty values) | all except json |
| `gt`, `gte`, `lt`, `lte` | greater / less than | integer, real, datetime, `id`, `created_at`, `updated_at` |
| `like` | contains, case-insensitive (`%` and `_` are matched literally; on SQLite case-insensitive only for ASCII letters) | text |
| `in` | one of the comma-separated values (at most 100) | text, integer, real, datetime, `id` |
| `null` | `true`: empty, `false`: not empty | all except `id` |

A `select` field uses `eq`, `ne`, `in`, `null` like text. On `multiselect`, `?tags=indirim` means "those that include this
option" (case-sensitive); only `eq` and `null` (`true`: nothing selected) are supported. Aggregates can be grouped by a
`select` field, e.g. `groupBy=status`.

**Field rules and computed fields:** in the wizard, a field's "Rules and calculation" section takes a format for text
(e-mail, URL), min/max length and a regular expression; min/max for numbers; and min/max selections for multi-select.
A non-conforming write returns `400` with a description per field (`{"details":{"qty":"at most 1000"}}`); the OpenAPI
schema (`enum`, `minLength`, `pattern`, `minimum`...) and import use the same rules. A computed field is evaluated from
its formula on write and cannot be written by the client (`400`); on update, fields not sent are taken from the record's current value:

```text
round(price * qty, 2)                 # number; an empty input makes the result empty, division by zero is empty
upper(name) + " (" + (status ?? "-") + ")"
if(qty > 0, "stokta", "tükendi")
```

Operators are `?? || && == != < <= > >= + - * / %` and unary `- !`; functions are `round(x[, digits])`, `floor`, `ceil`, `abs`,
`min`, `max`, `upper`, `lower` (with Turkish casing rules), `trim`, `len`, `concat`, `coalesce`, `if(condition, then, else)`.
A formula is at most 500 characters, with no loops and no external access; it can only reference fields of the same
collection (other than file and JSON fields). On an integer field the result is rounded.

**Relations:** a `relation` field holds the ID of a record in the target collection. On write, the target's existence is
checked within the same transaction (`400` if missing); when the target is deleted, `onDelete` applies (`409` on a `restrict`
violation, and nothing is deleted; `cascade` is chained and cycle-safe). `?expand=author,editor` embeds the target records in an `expand` object (one level;
one query per relation, regardless of record count; an ID not present in the target becomes `null`). `expand` into a collection
the request has no view permission for is rejected (see Users and rules). Relation fields support the `eq`, `ne`, `in`, `null`
filters. Rules are enforced in the API layer, not by database foreign keys (see PLAN Phase 4.2): the server is the only
process writing to the database, so `onDelete` and type changes work without rebuilding tables, and data migration works
regardless of table order. Relation columns get a lookup index.

**Search:** if `search` fields are selected on a collection (the field's "Searchable" box in the wizard), `?q=süt ekmek`
searches those fields: case-, Turkish-character- and accent-insensitive (`ISTANBUL`, `istanbul`, `İstanbul` are the same; `sut` finds
"Süt"), all words must match (at most 10 words), and it combines with other filters and sorting. The searchable form of the
values is written to a hidden `_search` column when the record is written (not shown in responses); it is recomputed on
deploy when the search fields change, when a search field's type changes, or when records remain uncomputed after a data
migration/restore (the schema plan shows "arama dizini yeniden hesaplanacak", i.e. the search index will be rebuilt). SQLite and PostgreSQL give the same result. On a collection
with no search fields, `q` returns `400`.

**Full-text index** (`fullText`, the collection's "Full-text index" switch in the wizard): every word of the search text
is indexed in the `_search_terms` table; `?q=ist` finds records with a word starting with "ist" ("İstanbul" yes,
"Bistro" no), without a table scan. If no `sort` is given, results are ordered by relevance (how many times the searched
words occur in the record). The index is portable SQL: SQLite and PostgreSQL give the same result. When enabled later, it is
built from existing records on deploy ("tam metin dizini kurulacak", i.e. the full-text index will be built); when disabled, it is dropped.

**Caching and compression:** `GET` responses come with a content hash (`ETag: W/"…"`); if the client asks with
`If-None-Match`, an unchanged response is a bodiless `304` (saves mobile data). JSON/HTML/JS/CSS responses over 1 KB are
compressed with gzip or deflate according to `Accept-Encoding`; live subscriptions and files are not compressed.

**Index:** the `fields[].index` field adds an index; with many records it speeds up filtering and sorting by that field. The schema plan
shows index additions and removals.

An operator or value that doesn't fit the type returns `400` (e.g. `gt` on text, `like` on a number, any filter on a json field).
Unknown parameters without brackets are ignored. Time values are ISO-8601; they compare correctly even with a different
number of fractional digits. `fields=title,done` returns only these fields in the response (`id` always; also works for
a single record). curl needs `-g` for brackets.

**Batch:** `POST /api/batch` runs up to 100 create/update/delete requests in a single database transaction: if one
fails, none is written. Each request is checked against its own collection's rules; script `before` events run inside the
transaction, while `after` events, webhooks and live subscriptions run after the transaction completes.

```bash
curl -H "$H" -X POST $BASE/batch -H 'Content-Type: application/json' -d '{"requests":[
  {"method":"POST",   "collection":"orders", "body":{"item":"çay","qty":2}},
  {"method":"PATCH",  "collection":"orders", "id":7, "body":{"paid":true}},
  {"method":"DELETE", "collection":"orders", "id":3}
]}'
# → {"results":[{"status":201,"body":{...}},{"status":200,"body":{...}},{"status":204}]}
# Error: {"status":400,"message":"Request 2: Validation error","details":{"qty":"...","index":"1"}} (index is zero-based)
```

Collections with a file field (and collections whose deletion cascades to records with files) are not allowed in a
batch: files on disk can't be rolled back with the transaction; send these one by one.

**Aggregates:** `GET /api/collections/{c}/aggregate` counts, sums and groups records; the list rule,
filters and `q` search apply as usual (under the `owner` rule a user gets aggregates of their own records only).

```bash
curl -g -H "$H" "$BASE/collections/orders/aggregate?sum=qty,price&avg=price&max=at&paid=true"
# → {"count":2,"sum_qty":5,"sum_price":20.0,"avg_price":10.0,"max_at":"2026-10-02T09:00:00Z"}
curl -H "$H" "$BASE/collections/orders/aggregate?groupBy=at:month&sum=qty"
# → {"groups":[{"key":"2026-10","count":3,"sum_qty":6},{"key":"2026-11","count":1,"sum_qty":6}],"truncated":false}
```

`count` is always returned. `sum` and `avg` work on integer/real, `min` and `max` also on datetime and text fields (comma-separate
for multiple fields). `groupBy` takes any field other than json and file; on a datetime field `:day`, `:month` or `:year`
is required (UTC). Empty values are collected in a `"key": null` group; at most 1000 groups are returned (`truncated: true` if there are more).

Errors have a single shape: `{"status":400,"message":"Validation error","details":{"title":"required field"}}`.
A unique field conflict returns `409`, a missing record `404`.

### Two-step verification, e-mail and SMS sign-in

If `auth.users.twoFactor.enabled` is on, users can add an authenticator app (Google Authenticator, Aegis,
1Password… RFC 6238, 6 digits, 30 s) to their account. For a user who has it on, sign-in takes two steps:

```bash
POST /api/auth/2fa/setup                       # (user token) → {secret, uri}; uri = otpauth://… (QR or open directly)
POST /api/auth/2fa/enable   {"code":"123456"}   # → {recoveryCodes:[10 codes]}; shown only once
POST /api/auth/login        {email, password}  # → {twoFactorRequired:true, challenge, expiresIn:300}
POST /api/auth/2fa/verify   {challenge, code}  # code: authenticator code or backup code (single-use) → token pair
GET  /api/auth/2fa                             # {enabled, required, recoveryCodesLeft}
POST /api/auth/2fa/recovery-codes {code}       # new backup codes;  POST /api/auth/2fa/disable {code} turns it off
```

The same authenticator code is not accepted twice; wrong codes count toward the same attempt limit as sign-in. The secret is
stored in the database (`_two_factor`) encrypted with AES-GCM; only hashes of the backup codes are kept. With `required: true`, a user
who hasn't set it up can sign in (`twoFactorSetupRequired: true`) but can only use the account endpoints until they do (admin role included),
and can't turn it off afterwards. For a user who lost their phone, use **Reset 2FA** on the app's Users screen
(or `PATCH /auth/users/{id} {"twoFactor": false}`). Social sign-in and e-mail code sign-in also require the second step.

**Sign-in with an e-mail code** (`auth.users.emailLogin`, requires e-mail settings): `POST /auth/request-login-code {email}`
e-mails an 8-digit, single-use code valid for 10 minutes (the response is the same whether or not the account exists);
sign in with `POST /auth/login-code {email, code}`, and the e-mail counts as verified. If `emailLoginUrl` is set, the e-mail
also includes a `?email=…&code=…` link to your client's page.

**Sign-in by SMS** (`auth.users.smsLogin`; Wizard → Users → Sign-in by SMS): users sign in with their phone number,
without a password. The code is sent as an SMS from the server phone's own SIM (your carrier may charge for it), so Phone
features → **Send SMS** must be on; allowed country codes and the daily SMS limit from there apply.

```bash
POST /api/auth/request-sms-code {"phone":"+905551234567"}   # 202; if an account exists, a 6-digit code is sent by SMS (10 min)
POST /api/auth/sms-code         {"phone":"+905551234567","code":"123456"}   # → token pair (second step if 2FA is on)
```

- The number lives in the user's `phoneField` extra field (default `phone`; must be text and **unique**). Turning the
  option on in the wizard adds the field if missing. On sign-up and updates spaces, dashes and parentheses are removed
  and the international format (`+90…`) is required.
- The response is the same whether or not the number is registered; unregistered numbers get no SMS.
- Abuse protection: one code per minute and at most 5 per day for the same number, at most 10 requests per hour from the
  same client; wrong codes count toward the same attempt limit as sign-in, and a code expires after 5 tries or 10 minutes.
- If users should not change their own number, set the field's visibility to **Admins only** (non-admins can't write
  it) and enter numbers yourself in the app's Users screen.
- In the client SDKs: `auth.requestSmsCode(phone)` and `auth.loginWithSms(phone, code)`.

**Web panel:** if "Also require authenticator code in the panel" is turned on in the Security step, panel sign-in asks for
an authenticator code in addition to the pairing code; the key is generated on the phone and kept in encrypted storage ("Add to authenticator app" or copy).

### Quotas

`auth.users.quotas` (Users → Quotas in the wizard) puts upper limits on each user; a limit left empty is unlimited,
and users with the admin role and API keys are exempt:

| Setting | Meaning | When exceeded |
|---|---|---|
| `records` | Max records per collection (in collections with an owner field, the records the user owns) | `403` |
| `collections` | Per-collection record limit, e.g. `{"photos": 100}` | `403` |
| `storageMb` | Total size of files in the records the user owns (thumbnails excluded) | `413` |
| `dailyRequests` | API requests made with the user's token per UTC day (counter in memory; resets when the app restarts) | `429` |

The error body contains `details.quota` (`records`, `storage`, `requests`) and `details.limit`. Users can see their own
usage; the app's Users screen also shows it on each user's card:

```bash
curl -H "Authorization: Bearer <user token>" http://<phone>:8080/api/auth/me/usage
# → {"records":{"notes":{"used":2,"limit":50}},"storage":{"usedBytes":734003,"limitBytes":10485760},
#    "requests":{"today":12,"limit":1000,"resetsAt":"2026-10-15T00:00:00Z"}}
```

Limits are checked by counting right before the write; concurrent requests may exceed them slightly.

### History and trash

If **record history** is on for a collection ("History, trash and retention" in the wizard), every create, update, delete and
restore stores the record's full state at that moment, who did it (`user:5`, `key:ad`, `admin`; empty for scripts and imports) and when;
at most `keep` versions are kept per record. If **trash** is on, a deleted record (including linked records deleted by cascade,
if trash is on in their collection) is kept with its files for `days` days, then permanently deleted (at startup and hourly).

```bash
GET    /api/collections/notes/records/5/history                  # {items:[{version, action, at, actor, data}], totalItems}
POST   /api/collections/notes/records/5/history/3/revert         # revert to version 3's values (as a new version)
GET    /api/collections/notes/trash                              # {items:[{id, deletedAt, expiresAt, actor, data}], totalItems}
POST   /api/collections/notes/trash/5/restore                    # restore with the same ID
DELETE /api/collections/notes/trash/5                            # delete permanently, with files
```

Because these endpoints contain the full record including hidden fields, regardless of rules they can only be used by the admin (and by
a named key authorized on that collection; write permission for revert and restore); in the web
panel they open via the **History** button on the record row and the **Trash** button on the collection. Reverting doesn't change file
and computed fields. A restored record is validated against today's rules: if the record it links to was deleted or
a required field was added later, `400` (restore the linked record first). Relations emptied by `set_null` on
deletion are not re-linked on restore. History and trash live in the `_history` and `_trash` tables; they are included in backups and data
migration, and are not deleted when the feature is turned off.

### Retention

**Retention** (`retention`) on a collection: records older than the given number of days are deleted automatically every hour. Age
is measured from `created_at` by default; `updated_at` or a datetime field (e.g. `due`) can be chosen, and an optional
condition deletes only some of them:

```json
{ "name": "tasks", "retention": { "days": 30, "field": "updated_at", "filter": "status=done" }, "trash": { "days": 7 } }
```

Deletion works like a normal delete: the relations' "on delete" setting and files apply, and if trash is on the record goes there.
Records that can't be deleted because other records link to them (`restrict`) are skipped and written to Logs. Automatic deletion doesn't produce webhook or live
subscription events.

### Views and reports

Publish a read-only list or summary without changing the collection or its rules (Wizard → Collections →
**Views and reports**). A view contains selected columns of the collection, one step further over a relation (`customer.name`),
a fixed condition and a sort order; a report view returns counts, sums and averages (optionally grouped) instead of records.
Who can see it is chosen in the view: everyone, signed-in users, or the admin (+ selected roles).

```json
"views": [
  { "name": "paid", "collection": "orders", "columns": ["id", "total", "customer.name"], "filter": "status=paid", "sort": "-total", "access": "public" },
  { "name": "revenue", "collection": "orders", "report": { "groupBy": "created_at:month", "sum": ["total"] }, "access": "admin", "roles": ["accountant"] },
  { "name": "mine", "collection": "orders", "columns": ["id", "total", "status"], "filter": "owner=@me", "access": "auth" }
]
```

```bash
curl http://<phone>:8080/api/views/paid                 # {items, page, perPage, totalItems}
curl "http://<phone>:8080/api/views/paid?total[gt]=100" # extra filter on the view's columns
curl -O "http://<phone>:8080/api/views/paid?format=csv" # Excel-compatible CSV
curl http://<phone>:8080/api/views                      # views you can see
```

In a view open to non-admins, admin-only/owner-only fields and users' e-mail addresses can't be selected. In the web panel the
**Views** tab shows the first 100 rows and downloads CSV.

### Users and rules

With `auth.users.enabled` on, your app's users sign up and sign in; the API key / admin JWT remains
**admin access** (user accounts require admin credentials: `auth.mode` can't be `none`).

```bash
curl -X POST $BASE/auth/signup -H 'Content-Type: application/json' -d '{"email":"ali@example.com","password":"gizli-parola","name":"Ali"}'
# → 201 {"token":"<access, 15 min>","refreshToken":"<30 days>","expiresIn":900,"user":{"id":1,"email":"ali@example.com",...}}
curl -X POST $BASE/auth/login   -H 'Content-Type: application/json' -d '{"email":"ali@example.com","password":"gizli-parola"}'
curl -X POST $BASE/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'   # new pair; the old one is invalidated
curl -X POST $BASE/auth/logout  -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'   # 204
curl -H "Authorization: Bearer <token>" $BASE/auth/me
curl -H "Authorization: Bearer <token>" -X PATCH $BASE/auth/me -d '{"name":"Ali V.","password":"yeni","currentPassword":"gizli-parola"}'
curl -H "$H" "$BASE/auth/users?email=ali"                       # admin: list, POST to add, PATCH /{id} (role, password), DELETE /{id}
```

- Accounts live in the `_users` system collection: `email` (lowercased, unique), `password_hash`, `verified`,
  `role` (`user` / `admin`) and extra fields. The password hash never appears in responses.
- **Password:** PBKDF2-HMAC-SHA256, 310,000 iterations, random 16-byte salt, constant-time comparison; format
  `pbkdf2$<iterations>$<salt>$<hash>` (the iteration count is stored with the hash, so it can be raised later). A hash is computed
  even for a nonexistent e-mail (response time doesn't reveal whether the account exists).
- **Tokens:** the access token is an HS256 JWT; the signing key is randomly generated in the database (separate from the admin JWT
  secret, so a user token can't stand in for an admin). The user is read from the database on every request: a deleted user's
  token is invalidated immediately, and role changes take effect immediately. The refresh token is a random 256 bits; only its SHA-256 hash
  is stored in `_sessions`, and it is **single-use** (a new one is issued on every refresh). When the password changes or
  the admin resets it, all of the user's sessions are closed.
- **Sign-in limit:** after 5 failed attempts from the same client for the same e-mail, or 20 in total, `429` for 15 minutes (even the correct password
  must wait); the general `authFailures` block also applies.
- No e-mail verification or password reset by e-mail (requires SMTP); the admin resets passwords from the app.

**Rules** (`rules`, per collection; all default to `admin`):

| Rule | Who |
|---|---|
| `public` | Everyone, even without a token |
| `auth` | Any signed-in user |
| `owner` | The record's owner (`ownerField` = the requesting user); lists show only their own records, someone else's record is `404`. Same as `auth` for create |
| `admin` | API key, admin JWT, or a user with the `admin` role |
| `nobody` | No one (not even the admin); e.g. write operations on a read-only collection |

The admin bypasses every rule except `nobody`. Without permission, a request without a token gets `401` and a signed-in but unauthorized user
gets `403`; an invalid or expired token counts as anonymous. For non-admin requests `ownerField` is not taken from the
client: on a new record it is filled with the requesting user (empty if anonymous) and can't be changed later (`403`); the admin
writes it freely. `expand` follows the target collection's `view` rule (with `owner` on the target, only the user's own records are embedded);
when relations to `_users` are expanded, only `id` and `publicFields` are visible. With `auth.mode` `none`, everyone
counts as admin and rules are not applied (legacy behavior).

**Abuse protection** (`server.limits`, per client address; requests from the phone itself are not limited):
more than `requestsPerMinute` requests per minute get `429` + `Retry-After`; an address making `authFailures` failed credential
attempts (`401`) in a row gets `429` for `banMinutes` minutes; a body exceeding `maxBodyKb` (including one without a declared length)
gets `413`. The real client address is read from the `X-Forwarded-For` / `CF-Connecting-IP` header set by the HTTPS endpoint and cloudflared;
these headers are trusted only on connections from inside the phone. Requests reaching the plain HTTP port from the
internet (from a public IPv4/IPv6 address; excluding the home network's IPv6 prefix) get `403`: the internet-facing endpoint is the
HTTPS endpoint or the tunnel. All responses carry `X-Content-Type-Options: nosniff`, API responses `Cache-Control: no-store`.

**Import / export:** `GET /api/collections/{c}/export?format=csv|json` downloads the collection as a file (the list
rule, filters, `q` and `sort` apply; hidden fields excluded; at most 100,000 records). `delimiter=semicolon` is the semicolon-separated
CSV that Turkish Excel expects; the CSV starts with a UTF-8 BOM, and text that starts like a formula (`=`, `+`, `-`, `@`)
is prefixed with `'` (so it doesn't execute in a spreadsheet; this is undone on import).

```bash
curl -g -H "$H" "$BASE/collections/products/export?format=csv&delimiter=semicolon&active=true" -o urunler.csv
curl -H "$H" -X POST "$BASE/collections/products/import?dryRun=true" -H 'Content-Type: text/csv' --data-binary @urunler.csv
# → {"columns":["id","name",...],"mapping":{"id":"id","name":"name",...},"total":120,"created":120,"updated":0,"errors":[],"sample":[...],"dryRun":true,"applied":false}
curl -H "$H" -X POST "$BASE/collections/products/import?mode=upsert" -H 'Content-Type: text/csv' --data-binary @urunler.csv
```

Import is admin-only and runs as a single transaction: if even one row is invalid, nothing is written (`400`, invalid rows
in `errors`, at most 100). `dryRun=true` runs the same checks (including uniqueness and relations) and rolls back. In CSV the delimiter
is detected from the header; comma decimals like `12,5`, `evet/hayır`, `2026-10-14` and `2026-10-14 09:30` (UTC) are accepted; an empty
cell doesn't send the field (default value on a new record, unchanged on update). Column names match field names
(case-insensitive) or are mapped with `mapping={"Ürün adı":"name","Not":null}`. With `mode=upsert`, a record with an `id`
is updated and one without is added (for editing an exported file and uploading it back). For JSON, send an array of records.
File fields are not imported; imported records don't produce webhook, live subscription or script events. At most 10,000 rows
and 20 MB (independent of the general body limit). In the app: Server → Import / export (preview and column mapping); in the web
panel, above the records table.

**Conditional rules:** when a condition is added to a rule (Wizard → Collection → Who can do what? → "condition"), even if the rule
allows it, only records matching the condition are accessible. The condition uses the list filter syntax, with `&` for AND (except `like`):

```json
"rules": {
  "list": "public", "view": "public", "create": "auth", "update": "owner", "delete": "owner",
  "conditions": {
    "list":   {"filter": "status=published", "owner": true},
    "view":   {"filter": "status=published", "owner": true},
    "create": {"filter": "status=draft"},
    "update": {"filter": "status=draft"}
  }
}
```

In this blog everyone sees published posts, and the author (`"owner": true`, the owner exception) also sees their own drafts; a user can only
add drafts and only edit drafts (to publish, `status` is changed while it is a draft). Records not matching the condition are invisible in
lists and aggregates, `404` for a single record and for update/delete; on create, if the submitted record doesn't match, `403` and nothing is
written. In a relation field pointing to users, `@me` is the requesting user: with `{"filter": "assignee=@me"}` everyone sees only
the tasks assigned to them. Conditions also apply to `expand`, live subscriptions and batches; the admin is not bound by
conditions. Conditions can only be used with the "Everyone", "Signed-in users" and "Record owner" rules.

**Field visibility:** if a field's "Who can see" option (`fields[].visibility`) is `owner`, the field is returned only to the record's owner and
the admin; if `admin`, only to the admin (in others' responses, in `expand` and in live subscriptions the field is absent).
Non-admins can't filter, sort, aggregate or expand by a hidden field (`400`; allowed on an owner-visible
field when the "Record owner" rule restricts the scope to their own records); a hidden field can't be searchable.
Only the admin writes an `admin` field (e.g. a moderation note, an internal score). Scripts, webhooks and the admin see records
in full.

### Custom roles

Define your own roles besides user (`user`) and admin (`admin`): Wizard → Users → **Roles**
(`auth.users.roles`). You assign a role in Server → Users (or in the panel, or with `PATCH /auth/users/{id} {"role":"editor"}`);
what it can do is chosen per operation in the collection's rules (**Also:** editor):

```json
"auth": { "users": { "enabled": true, "roles": [{ "name": "editor", "title": "Editor" }, { "name": "moderator" }] } },
"collections": [{ "name": "posts", "ownerField": "author",
  "rules": { "list": "public", "view": "public", "create": "auth", "update": "owner", "delete": "admin",
             "roles": { "update": ["editor"], "delete": ["editor", "moderator"] } } }]
```

Here the author edits their own post; the editor edits and deletes all of them; the moderator deletes. A role permission grants that operation
on all records unconditionally, but it is not admin privilege: admin-only fields stay hidden, and an "Off" operation remains
off. In script endpoints use `routerAdd("GET", "/rapor", fn, "role:editor")` (the admin can call it too); in the handler,
`ctx.auth.role`.

### Teams

For groups such as a family, a crew or a branch: Wizard → Users → **Teams** (`auth.users.teams`). Users create teams
(the creator becomes the **owner**; with `allowCreate: false` only the admin can create them), and the owner adds members
by email. When the **Team members see and edit** preset is chosen on a collection, a `team` relation field is added
(`teamField`) and the `team` rule applies:

- A user lists, views, updates and deletes only the records of teams they belong to; another team's record is 404.
- A new record can only be added to the user's own team; if they have a single team the field is filled in
  automatically, with several teams `team` must be sent. A record can't be moved to a team the user isn't in.
- A user who leaves a team immediately stops seeing its records (membership is read on every request).
- When a team is deleted, its records are deleted (`cascade`), cleared, or the delete is refused, according to the team
  field's delete behaviour.

```json
"auth": { "users": { "enabled": true, "teams": { "enabled": true, "allowCreate": true, "maxMembers": 100, "maxTeamsPerUser": 20 } } },
"collections": [{ "name": "tasks", "teamField": "team",
  "fields": [{ "name": "title", "type": "text" }, { "name": "team", "type": "relation", "collection": "_teams", "onDelete": "cascade" }],
  "rules": { "list": "team", "view": "team", "create": "team", "update": "team", "delete": "team" } }]
```

```bash
curl -X POST -H "Authorization: Bearer <token>" -d '{"name":"Home"}' http://phone:8080/api/teams        # create
curl -X POST -H "Authorization: Bearer <token>" -d '{"email":"ayse@example.com"}' .../api/teams/1/members  # add a member
curl -H "Authorization: Bearer <token>" http://phone:8080/api/teams/1                                   # members
curl -X DELETE -H "Authorization: Bearer <token>" .../api/teams/1/members/<own id>                       # leave
```

Team roles are `owner` (adds/removes members, gives roles, renames, deletes) and `member`; the team's last owner can't
leave. Members see each other's email. In the SDK: `ds.teams.create("Home")`, `ds.teams.addMember(id, { email })`. In the
app, Server → **Teams** shows all teams as admin. A condition can also be added to the `team` rule (`status=open`).

### Encrypted fields

**Store encrypted** on text and JSON fields (`"encrypted": true`): the value is stored encrypted with AES-256-GCM in the database, history, trash,
backups and the live copy, and is returned in plain form by the API (subject to rules). Even if the phone is stolen or
a backup file leaks, data such as national ID numbers, phone numbers and addresses can't be read.

- The key is generated with the first encrypted field and kept in the phone's encrypted storage (`database.fieldKey`: `secret:field_key`);
  it is **not included in backups**. Save it to a password manager via Wizard → Database → **Field encryption** → *Copy key*;
  when restoring the backup on another phone, use *Enter an existing key* there. A restore with the wrong key is rejected.
- Encrypted fields can't be filtered, sorted, aggregated or searched; they can't be unique or indexed, or be used in conditions or
  computed fields (the same value encrypts differently every time).
- Turning encryption on or off later is safe: deploy converts existing values.

### Social sign-in (Google, GitHub)

Wizard → Users → Social sign-in: the client ID and secret of your own OAuth app (the secret goes in encrypted storage),
the server's browser-facing address (`publicUrl`, e.g. a DuckDNS + Let's Encrypt address) and the allowed redirect URLs.
The callback URL to register with the provider is shown in the app: `<publicUrl>/api/auth/oauth/<google|github>/callback`.

```text
1. Client opens in the browser: GET /api/auth/oauth/google?redirect=https://uygulamam.com/giris
2. The user consents at Google; the server exchanges the code for a token with PKCE and reads the verified e-mail.
3. Browser returns to:          https://uygulamam.com/giris?code=<one-time code>   (error: ?error=<message>)
4. Client:                      POST /api/auth/oauth/exchange {"code": "..."} → {token, refreshToken, expiresIn, user}
```

A verified e-mail is matched to an existing account (the account becomes verified); otherwise, if sign-up is open, a verified account
with a random password is created (a password can be set via "forgot password" if needed). If sign-up is closed or there are required extra fields, no account
is created. Security: PKCE (S256), a single-use `state` and an HttpOnly cookie bound to the browser that started the flow (prevents
signing someone in through a flow started by someone else), only allowed redirect URLs (no open redirect), and a single-use code valid for 60 s
in the URL instead of a token. A mobile app can use a custom-scheme URL (`uygulamam://giris`).
`GET /api/auth/oauth/providers` lists the enabled providers (for the buttons on a sign-in screen).

### Web push notifications

Wizard → Users → Web push: when turned on, a VAPID key pair is generated on the phone (the private key goes in encrypted storage). When a user
allows notifications in your web app, the browser's subscription is linked to their account; the server sends notifications to
that browser (even when the tab is closed). The content is encrypted with the browser's key (RFC 8291, `aes128gcm`), and the request is signed with VAPID
(RFC 8292); the push service (Google, Mozilla, Apple) can't see the content.

```js
// Your web app (user signed in, HTTPS):
const reg = await navigator.serviceWorker.register("/sw.js");
const { publicKey } = await (await fetch(`${base}/api/push/key`)).json();
const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: publicKey });
await fetch(`${base}/api/push/subscribe`, { method: "POST", headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json" },
  body: JSON.stringify(sub.toJSON()) });

// sw.js
self.addEventListener("push", (e) => {
  const m = e.data.json(); // {title, body, url, data}
  e.waitUntil(self.registration.showNotification(m.title, { body: m.body, data: m.url }));
});
self.addEventListener("notificationclick", (e) => { e.notification.close(); if (e.notification.data) e.waitUntil(clients.openWindow(e.notification.data)); });
```

Sending: from scripts `ctx.push.send(userId, "Title", "Text", "/path")` (returns the number of devices), the admin's
`POST /api/push/send {"user": 5, "title": "...", "body": "...", "url": "..."}`, or automatic rules (`push.notify`):
when a record is added/changed/deleted in a collection, to the record's owner, with `{field}` placeholders (`"Your order: {status}"`).
At most 20 devices per user; if the service reports the subscription has ended (404/410), the subscription is deleted. The subscription URL
must belong to one of the known push services (prevents the server from sending requests to internal network addresses). A notification is at most 3800
bytes. If the VAPID keys are regenerated, browsers need to subscribe again.

### Files and static site

```bash
curl -H "$H" -F 'title=Deniz' -F 'image=@tatil.jpg' $BASE/collections/photos/records
# → {"id":7,"title":"Deniz","image":"tatil_k3j9x0a2mq.jpg",...}
curl -H "$H" -o tatil.jpg $BASE/files/photos/7/tatil_k3j9x0a2mq.jpg
curl -H "$H" -o kucuk.jpg "$BASE/files/photos/7/tatil_k3j9x0a2mq.jpg?thumb=100x100"
curl -H "$H" -X PATCH -F 'image=@yeni.jpg' $BASE/collections/photos/records/7          # replaces the old one
curl -H "$H" -X PATCH -H 'Content-Type: application/json' -d '{"image":null}' $BASE/collections/photos/records/7
```

- Files for a `file` field are sent with `POST`/`PATCH` as `multipart/form-data`; other form fields are converted to their types
  (`count=3` → 3). Files are stored under `files/storage/<collection>/<record-id>/`; the database holds only their names
  (a name for a single field, an array for a multiple field). Names have the form `<original-name>_<random>.<extension>`.
- **The size limit is enforced while streaming**: if the limit is exceeded while the file is written to a temp folder, reading stops and `413` is returned
  (the file is never fully loaded into memory or onto disk). File uploads are subject to the field's limit, not the general body limit (`maxBodyKb`).
- **The content type is detected from the file's first bytes** (PNG, JPEG, GIF, WebP, HEIC, PDF, MP4, MP3, OGG, WAV, zip, text…);
  a file not matching `mimeTypes`, or one whose extension doesn't match its content (an HTML file named `resim.png`), is rejected with `400`.
  The stored extension comes from the content: executable text such as HTML/SVG becomes `.txt`.
- **Download** `GET /api/files/<collection>/<id>/<name>`: the record's `view` rule applies (with `owner`, someone else's file is
  `404`); the name must belong to that record. Responses carry `Content-Security-Policy: sandbox` (uploaded content can't run scripts
  from the server's origin); images, audio, video, PDF and plain text open in the browser, everything else is downloaded.
- **Thumbnails:** `?thumb=WxH` only in allowed sizes (`100x100` and the field's `thumbs`; `400x0` keeps the aspect ratio), generated
  on first request and cached (`BitmapFactory` on the phone; large photos are decoded with subsampling). For non-images the original file is returned.
- With `PATCH` in JSON, set a file field to `null` (remove the files) or to the existing names to keep; on a multiple field, new
  uploads are appended. Removed files are deleted, and when a record is deleted (including by cascade) its folder is deleted.

### Backups

A backup is a single zip: `manifest.json`, `server.json` (without secret values, only `secret:` references), `db/<table>.jsonl`
(one record per line), `files/storage|scripts|www/…`. Instead of an engine-specific dump (VACUUM INTO, pg_dump), the database
is written record by record: the same backup restores into both SQLite and PostgreSQL, works on Android's older SQLite
versions, and the PostgreSQL component needs no extra tools. Reading happens in a single transaction (REPEATABLE READ on
PostgreSQL); the backup is a consistent snapshot and the server keeps running while it is taken.

- **Scheduled backup** (`backup`): uses the phone's clock; backups go to `files/backups/` and the newest `keep` are retained
  (manual and pre-restore backups are not deleted). If you pick a folder in Server → Backups (Drive, SD card …), a copy of
  each backup is written there too.
- **Restore:** the backup is first validated end to end (format, configuration, record counts, safe file names), a backup
  of the current state is taken and the server is stopped; the database switches to the backed-up state in a single
  transaction (on error nothing changes and the server comes back up as before), then the folders are swapped. The
  backup's configuration is saved, but the database setting stays this phone's. When restored to a new phone, secrets
  (API key etc.) are not in the store, so they are regenerated in the Security step.

**Cloud backup** (Wizard → Database → Cloud backup): scheduled (and optionally manual) backups are encrypted on the phone
and uploaded as `.dsbk` to WebDAV (Nextcloud, ownCloud, Synology) or S3-compatible storage (Backblaze B2, Cloudflare R2,
MinIO, AWS); the last `keep` are kept in the store.

- Encryption: AES-256-GCM in 1 MiB chunks (removed, reordered and truncated chunks are detected); the key is derived from
  the passphrase with PBKDF2-HMAC-SHA256 (200,000 iterations). The provider cannot see the contents.
- The passphrase is generated randomly when you turn it on (25 characters), or you type your own; **write it down
  somewhere other than the phone** — it is needed to restore to another phone, and if it is lost the backup cannot be
  opened. The storage password/secret key and the encryption passphrase are kept in the encrypted store (`secret:` reference).
- S3 requests are signed with AWS Signature V4 (path-style address: `endpoint/bucket/prefix/name`); WebDAV uses basic
  authentication (an app password is recommended on Nextcloud), and the folder is created if missing.
- Moving to a new phone: install the app, enter the same cloud settings and encryption passphrase in the Wizard and save,
  then Server → Backups → **List backups in the cloud** → **Download and restore**. "Test connection" checks the settings right away.

```json
"backup": { "enabled": true, "remote": { "kind": "s3", "url": "https://s3.eu-central-003.backblazeb2.com",
  "bucket": "evim-yedek", "region": "eu-central-003", "prefix": "droidserver/", "username": "<key ID>",
  "password": "secret:backup_password_1", "passphrase": "secret:backup_passphrase_2", "keep": 30 } }
```

### Metrics

`GET /api/_metrics` (admin) returns request count, status code distribution, in-flight requests, per-endpoint count and latency
(p50/p95 over the last 512 requests; IDs in paths become `:id`), a per-minute series for the last 60 minutes and device status
(battery level, temperature, charging, free storage, memory). `?format=prometheus` gives the Prometheus text format. Metrics live
in memory and reset when the app restarts.
Successful `_metrics` reads (regular polling by the panel and Prometheus) are not counted in request counts; rejected ones (401) are.

### Web admin panel

With `adminPanel.enabled`, open `http://<phone>:<port>/_admin/` in a browser (or use your internet address). The landing
screen is metrics (chart and endpoint table; refreshed every 10 seconds while the tab is open). Other tabs: records (table,
filter and search, add/edit/delete; refreshes by itself when realtime subscription is on), users, live logs, API
(OpenAPI document and client SDK download), backups (create, download), **schema** (collection, field and rule editor: field
type and options, constraints, computed fields, relations, search/full-text, history and trash; when a type changes you are
asked whether to convert or delete stored values, "Show plan" shows the changes and any data loss; data of removed fields and
collections is not deleted) and configuration (edit, validate, save and deploy). Record rows have **History**, and
collections have **Trash** (when history/trash is on). Sign-in can optionally also require an authenticator code (see
Two-step verification). Sign-in uses a **one-time 6-digit
code** generated on the phone's Status screen: the code is valid for 5 minutes, can be used once and is invalidated after 5
failed attempts; a successful sign-in opens an admin session lasting `sessionHours` (in memory; ends when the app closes, and
all sessions can be closed from the Status screen). The panel is dependency-free plain JavaScript (`admin/` in the core
resources), loads only its own files (`Content-Security-Policy`), cannot be framed by other sites and writes data only as
text. Record/user/metrics operations use the normal API with the panel session; configuration, logs and backups use
`/_admin/api/...`.

Panel counterparts of the app's screens:

- **Users → Teams** (when teams are on): all teams; create, delete, add members (by e-mail), make owner/member, remove.
- **Automation:** add, edit, enable/disable and delete automation rules, **Try now**; recent runs. **Forms**: address,
  title, collection, fields and their order, thank-you text or redirect, submission limit.
- **Services:** MQTT (status, connected devices, recent messages, routes, record events, messages to devices), network drive
  (shares, read-only, address), live replica and failover (sync status, automatic takeover, witness address, failback,
  **Take over**), AI (today's usage, semantic index, rebuild), your own apps (status, release, deploy) and the command line
  tool's install commands.

Settings on these screens are saved the same way as the Schema and Configuration tabs: they pass the app's validation, the
plan is shown first, and the audit log records them as `panel`. A new MQTT or network drive password can be typed in the
panel (the phone moves it to the encrypted store; left empty, the saved password is kept). The AI provider key, replica
setup (the primary's key) and app zips are entered on the phone only.

### Audit log

Admin actions are logged with who, when and from where: server starts and configuration changes (added/removed collections
and fields, type and rule changes, created/revoked keys, access and panel settings), user create/update/delete (role,
password reset, 2FA reset; the password itself is never written), reverting a record to an older version, restoring from
and permanently deleting from the trash, imports, manual backups and restores, and panel sign-ins (including failed ones).
The log lives in the profile's folder (`audit.jsonl`), not in the database, so a database migration or a restore from
backup cannot erase it. Entries older than `audit.keepDays` (365) days are dropped.

```bash
curl -H "Authorization: Bearer <admin key>" "http://<phone>:8080/api/_audit?action=user.&q=ali&limit=100"
curl -H "Authorization: Bearer <admin key>" -o denetim.csv "http://<phone>:8080/api/_audit?format=csv"
```

In the app: Server → **Audit log** (search, CSV sharing); in the panel: the **Audit** tab.

### Health alerts

Wizard → General → **Health alerts** (on by default, with phone notifications). Checked every 30 seconds while the server
is running:

| Alert | When | Critical |
|---|---|---|
| Storage is almost full / Storage full | free space below `diskFreeMb` (500) MB / below 100 MB | full: yes |
| Server errors increased | 5xx rate above `errorRatePercent` (20%) over the last 5 minutes (at least 20 requests) | |
| Certificate expiring | less than `certificateDays` (14) days until the Let's Encrypt certificate expires | |
| Battery too hot | battery at or above `batteryTempC` (45) °C | |
| Server isn't running / Component stopped | the server could not start; a component exceeded its restart limit | yes |
| Backup failed | scheduled backup, copy to folder or cloud upload failed | |
| App crashed | the app was closed by an uncaught error (reported on next launch) | yes |
| The system restarted the server | Android killed and restarted the service; Android 11+ also gives the reason | |

The same problem is repeated every `repeatHours` (6) hours, and a single "resolved" message is sent once it clears. During
**quiet hours** (`quietHours`, e.g. 23:00-07:00) only critical alerts are sent; the others follow when quiet hours end if the problem persists.

Channels: phone notification, **e-mail** (`alerts.email`; using the server's SMTP settings) and **Telegram**: create a bot
with `/newbot` in @BotFather, paste the token into the wizard (it goes to the encrypted store), send the bot a message and
tap **Find chat**; test right away with **Send test**.

```json
"alerts": { "email": ["ben@example.com"], "telegram": { "botToken": "secret:telegram_token_1", "chatId": "987654321" },
            "quietHours": { "from": "23:00", "to": "07:00" } }
```

Ongoing problems and recent alerts: Server → **Health alerts** (test alerts are sent from here too), the top of the
**Metrics** tab in the panel, and `GET /api/_alerts` and `POST /api/_alerts/test` (admin) in the API.

### Firewall

Wizard → Security → **Firewall**:

- **Blocked addresses**: a single IP or range (`203.0.113.7`, `10.0.0.0/8`, `2001:db8::/32`); requests from these addresses
  get 403.
- **Allowed only**: if non-empty, only listed addresses (and the local network, if enabled) have access. The phone itself
  is never blocked.
- **Requests per minute per user** (`firewall.userRequestsPerMinute`): each account is counted separately; users sharing
  the same address don't affect each other. Named keys can have their own limit too (`auth.keys[].requestsPerMinute`);
  a key's limit is independent of the address.

```json
"firewall": { "deny": ["203.0.113.0/24"], "allow": ["198.51.100.0/24"], "allowLocalNetwork": true, "userRequestsPerMinute": 120 }
```

Addresses with too many failed authentication attempts (`server.limits.authFailures`) are banned for a period. Banned
addresses appear on the Server → **Firewall** screen and in the panel's **Audit** tab; from there a ban can be lifted or an
address banned manually. Bans survive redeploys, and every ban and unban is written to the audit log.

```bash
curl -H "Authorization: Bearer <admin key>" http://<phone>:8080/api/_firewall
curl -X POST -H "Authorization: Bearer <admin key>" -H "Content-Type: application/json" \
  -d '{"client":"203.0.113.50","minutes":1440,"reason":"tarama"}' http://<phone>:8080/api/_firewall/bans
curl -X DELETE -H "Authorization: Bearer <admin key>" http://<phone>:8080/api/_firewall/bans/203.0.113.50
```

### Scripts

With `scripts.enabled`, the `.js` files under `files/scripts/` are loaded in name order on every deploy (JavaScript,
Mozilla Rhino; arrow functions, `let`/`const` and template literals are supported). A syntax error, unknown collection or
invalid cron expression rejects the deploy (`main.js:12: …`); the running server keeps its previous state.

```js
// GET /api/merhaba/Ayşe → {"mesaj":"Merhaba Ayşe"}. The fourth argument is access: "public" (default), "auth", "admin".
routerAdd("GET", "/merhaba/:ad", (req, ctx) => ({ mesaj: "Merhaba " + req.params.ad }))
routerAdd("POST", "/siparis", (req, ctx) => reply(201, ctx.db.create("orders", req.body)), "auth")

onBeforeCreate("todos", (record, ctx) => {          // may modify the record; fail() aborts the operation
  record.title = (record.title || "").trim()
  if (!record.title) fail(400, "Başlık boş olamaz")
})
onBeforeUpdate("todos", (changes, ctx) => { if (ctx.current.done) fail(409, "Tamamlanmış iş değişmez") })
onAfterCreate("orders", (record, ctx) => { ctx.http.post("https://hooks.example.com/yeni", { id: record.id }) })
cronAdd("0 3 * * *", (ctx) => ctx.log("gece görevi"))   // minute hour day month day-of-week (phone's clock)
```

- **Endpoints** live under the API path (`/api/merhaba/...`); the server's own endpoints (`/collections`, `/files`, `/auth`,
  `/health`, `/_schema`) cannot be used. `req`: `method`, `path`, `params`, `query`, `headers`, `body` (JSON). The return value
  becomes the JSON response; `reply(status, body, headers)` sets the status code, `fail(status, message)` returns an error.
- **Record events** (`onBefore/AfterCreate/Update/Delete`, collection name or `"*"`) run only for writes coming from the API.
  `before` can modify the record or reject it with `fail()`; on update and delete `ctx.current` is the existing record.
  Errors in `after` don't change the response; they are written to the log.
- **`ctx`**: `ctx.db.list/get/create/update/delete(collection, …)` (with admin rights, does not trigger record events;
  `list(name, { filter: { "title[like]": "süt" }, q: "search", sort, page, perPage })`; writes also reach realtime
  subscribers), `ctx.http.get/post/request` (only `allowedHosts`, redirects not followed, 10 s, 1 MB; response
  `{ status, body, json }`), `ctx.mail.send(to, subject, text[, html])` (if e-mail is on), `ctx.log(...)`, `ctx.env`,
  `ctx.auth` (`{ type: "anonymous" | "user" | "admin" | "key", id, key, admin }`), `ctx.push.send(...)` (if web push is on).
- **Security:** no access to Java classes (`java` and `Packages` are undefined, and a `ClassShutter` rejects every class);
  every call has a time limit (`timeoutMs`; `cronTimeoutSec` for scheduled tasks): a script exceeding it is stopped and
  cannot catch this with `try/catch`; recursion depth is limited. A script error returns `500` (details go to the log, not
  the client: `file:line` and the script stack) and does not stop the server. Scripts can run concurrently (thread-safe objects).

**Background jobs:** run slow or unreliable work (e-mail, external services, reports) in a queue without making the request wait.
The queue is in the database (`_jobs`): jobs are not lost even if the app or phone restarts.

```js
jobAdd("fatura", (data, ctx, job) => {               // job: { id, name, attempt, attempts }
  const o = ctx.db.get("orders", data.order)
  const r = ctx.http.post("https://fatura.example.com/kes", { tutar: o.total })
  if (r.status === 422) fail(422, "geçersiz sipariş")  // 4xx: permanent error, not retried
  ctx.db.update("orders", o.id, { invoice: r.json.no })
  return { no: r.json.no }                             // stored as the result
}, { retries: 5, timeoutSec: 60, concurrency: 1 })

onAfterCreate("orders", (record, ctx) => ctx.jobs.enqueue("fatura", { order: record.id }, { key: "fatura-" + record.id }))
```

- A failed job is retried `retries` more times with exponential backoff (30 s, 1 min, 2 min, … up to 1 hour), then becomes `failed`.
- `ctx.jobs.enqueue(name, data, { delaySec, key, retries })` returns the job ID; if `key` is set and an unfinished job with
  the same key exists, no new one is added. At most `scripts.jobs.concurrency` (2) jobs run at once, `concurrency` per type.
- Jobs run **at least once**: if the app closes while a job is running, it is requeued on startup; running the same job
  twice must be harmless. Finished jobs are deleted after `scripts.jobs.keepDays` (7) days.
- List, rerun, cancel: Server → **Background jobs**, the **Jobs** tab in the panel, or
  `GET /api/_jobs?status=failed`, `POST /api/_jobs` (`{"name":"fatura","data":{…}}`), `POST /api/_jobs/{id}/retry`,
  `POST /api/_jobs/{id}/cancel` (admin; written to the audit log).

**Static site:** with `static.enabled`, the files under `files/www/` are served under `static.path` (the API path always
takes precedence; `/api/...` never falls through to the site). A folder address serves its `index.html`; with `spaFallback`
on, extensionless addresses that are not found (`/urunler/42`) return `index.html` (single-page apps). Paths cannot escape
the folder, files starting with `.` are not served, and responses carry `Cache-Control: no-cache`. The site is uploaded as
a zip from the app: it is extracted to a separate folder and replaces the old one when complete; archives with names that
escape the folder (`../`) or exceeding 500 MB / 20,000 files are rejected and the old site stays in place. If everything in
the zip is inside a single folder, that folder's contents become the root.

### OpenAPI and client examples

`GET /api/_openapi.json` returns an **OpenAPI 3.1** document generated from the configuration: collection endpoints, field
schemas (required fields, date format, relations, files), filter and search parameters, user and e-mail endpoints,
realtime subscription and script endpoints. The document is generated **according to the caller's permissions**: an
anonymous request sees only public operations, a user sees what the rules allow, and an admin sees everything (disabled
operations are not in the document). It can be imported into Postman, Insomnia or Swagger Editor, or used with client code
generators. The web panel's **API** tab shows the same document endpoint by endpoint (parameters, body fields, curl) and
downloads it as `openapi.json`. The in-app API explorer gives examples as **curl**, **JavaScript** (`fetch`, `EventSource`
for streams, `FormData` for files) or **Kotlin** (OkHttp).

**Typed client (SDK):** a single-file client is generated from the configuration — collection types (choice fields as union
types, computed fields read-only), list/filter/sort, create/update/delete, file upload, sign-in (including two-step
verification and e-mail code), automatic token refresh and realtime subscription. Expert-mode paths and field names are
used. In the app: API explorer → Client SDK (share/save), in the panel: the API tab, or:

```bash
curl -H "Authorization: Bearer <admin key>" -o droidserver.ts http://<phone>:8080/api/_sdk/ts
curl -H "Authorization: Bearer <admin key>" -o DroidServerClient.kt "http://<phone>:8080/api/_sdk/kotlin?package=com.ornek.api"
```

```ts
const ds = new DroidServer("https://evim.duckdns.org/api");
await ds.auth.login("ali@example.com", "parola");            // token is stored and refreshed when it expires
const page = await ds.todos.list({ filter: { done: false, qty: { gt: 0 } }, sort: "-created_at" });
await ds.todos.create({ title: "Süt al" }, { image: file }); // if there is a file field
const stop = ds.subscribe(["todos"], (e) => console.log(e.action, e.record));
```

The TypeScript client is dependency-free (`fetch`; browsers, Node 18+, React Native, Deno) and uses only syntax that works
with type stripping. The Kotlin client works on the JVM and Android with `HttpURLConnection` and kotlinx.serialization
(on the JVM the server accepts the `X-HTTP-Method-Override` header for PATCH). Since it contains the schema, `/_sdk` is admin-only.

### E-mail: verification and password reset

With `email.enabled` (Wizard → Users → E-mail sending; presets for Gmail, Outlook, Yandex, iCloud and "Send test"), the
server sends e-mail over SMTP. It is a small dependency-free SMTP client: encrypted connection via SSL (465) or STARTTLS
(587), the server certificate and name are verified, and the password is never sent over an unencrypted connection. Gmail
and Outlook require an **app password**, not the account password.

| Endpoint | Description |
|---|---|
| `POST /api/auth/request-verification` `{email}` (or with a user token) | Verification e-mail; `202` |
| `POST /api/auth/confirm-verification` `{token}` | Verifies the account, returns the user |
| `POST /api/auth/request-reset` `{email}` | Password reset e-mail; the same `202` whether or not the account exists |
| `POST /api/auth/confirm-reset` `{token, password}` | New password; all sessions are closed and the e-mail counts as verified |

E-mails are sent in the background; the response is the same, and equally fast, whether or not the account exists (it
doesn't leak whether the address is registered). Tokens are short-lived and single-purpose: verification 24 hours, reset
1 hour; the verification token is invalidated when the e-mail changes, the reset token when the password changes (the
reset link is single-use). They cannot be used as access tokens, nor vice versa. Limits: 1 per minute / 5 per hour of the
same type to the same address, 200 e-mails per hour server-wide. If `email.publicUrl` (e.g. `https://evim.duckdns.org:8443`;
the wizard suggests one from the access settings) is set, the e-mail contains a link that opens the server's own small
pages: `/_auth/verify` verifies the account, `/_auth/reset` shows the new password form (no JavaScript; the flow works
without writing a client app). Otherwise the e-mail contains a code, and the client app passes it to the `confirm-*`
endpoints. With `requireVerified` on, unverified users cannot sign in (`403`) and sign-up does not create a session.
Scripts can send e-mail with `ctx.mail.send(to, subject, text[, html])` (same limits; 429 when exceeded).

### Named API keys

Wizard → Security → Named keys: a separate key for each client (n8n, a report script, a dashboard, a mobile app's
backend). Access can be **Full access** (same as the admin key), **Read-only** (reads all collections) or **Specific
collections** (read or read-write); optional expiry (30 days, 90 days, 1 year). A restricted key bypasses rules on its
permitted collections (like an admin; including listing, aggregates, export/import, batch operations and realtime
subscription) but cannot access admin endpoints (`/_schema`, `/_metrics`, `/_webhooks`, `/auth/users`, panel); operations
outside its permissions get `403`. Keys start with `dsk_` and are shown **once** on creation: the configuration keeps only
the SHA-256 hash and the first few characters for identification (`auth.keys[]`). Revoking (turning off the switch on the
key's card) or deleting takes effect once deployed; a revoked or expired key gets a `401` stating the reason. The last-used
time is shown in the app. The legacy `auth.apiKeys` keep working with full access.

### Webhooks

Wizard → Security → Webhooks: when a record is created, changed or deleted, a JSON POST is sent to the address you choose
(Zapier, n8n, Make, a Discord bridge, your own service). Optional collection and event filters. Body:

```json
{"id": "<delivery ID>", "event": "todos.create", "collection": "todos", "record": {...}, "timestamp": "2026-10-14T10:00:00Z"}
```

Headers: `X-DroidServer-Event`, `X-DroidServer-Delivery`, `X-DroidServer-Timestamp` (Unix seconds) and, if a signing secret
is defined, `X-DroidServer-Signature: sha256=<HMAC-SHA256(secret, "<timestamp>.<body>")>`. The receiver should compute the
same value, compare in constant time and reject old timestamps (e.g. older than 5 minutes). The secret is generated in the
app, stored in the encrypted store and shown once. 2xx means success; otherwise it is retried after 10 s, 1 min and 5 min
(with the same delivery ID; receivers can deduplicate by ID). Redirects are not followed, 10 s timeout. Pending deliveries
are kept in memory and are lost if the app closes. Scripts' `ctx.db` writes are delivered too. The Server → Webhooks screen
shows the last 200 deliveries and "Test" (a ping event); for admins: `GET /api/_webhooks`, `POST /api/_webhooks/{ad}/test`.

### Automation rules

Server → Automations: "when this happens, do that" without code. A rule has a trigger, an optional condition and
actions that run in order.

| Part | Options |
|---|---|
| Trigger | Record event: collection + `create` / `update` / `delete`. Schedule: a cron expression (`0 8 * * *`, phone's clock); if a collection is chosen, matching records are counted (`{count}`) and the update action applies to all of them (at most 1000) |
| Condition | List filter syntax: `status=new`, `qty[gte]=10&paid=true` (no `like` or `@me`) |
| `email` | `to` (an address or a field like `{email}`), `subject`, `body`; e-mail sending must be on |
| `push` | `to`: `owner` (the record's owner) or a user id / `{field}`; `subject` as title, `body`, `url` |
| `webhook` | The name of a defined webhook; event `automation.<rule>`, signing and retries as for webhooks |
| `update` | `values`: field → value; on an event the triggering record, on a schedule the matching records are updated |
| `create` | `collection` + `values` |
| `log` | `body` is written to the app's log screen |

In texts `{field}` is filled with the record's value (original and expert-mode names), and `{now}` (UTC time),
`{count}`, `{rule}`, `{collection}` and `{event}` with run details. Values are converted to the field's type
(`"yes"` → `true`, `"12,5"` → 12.5; empty text → `null`).

```json
"automations": [{
  "name": "new-order",
  "trigger": {"collection": "orders", "events": ["create"]},
  "condition": "status=new",
  "actions": [
    {"type": "email", "to": "{email}", "subject": "Order received (#{id})", "body": "{qty} x {item}"},
    {"type": "update", "values": {"status": "received"}}
  ]
}]
```

Record events are processed in order in the background; requests do not wait. Writes made by rules also produce events
(realtime subscribers, webhooks, other rules), but the chain stops after 3 steps and an update that changes nothing is
not written, so a rule that triggers itself does not loop. The screen shows the last 100 runs (success/failure and what
was done) and "Try now": a scheduled rule runs immediately, an event rule is tried with the latest matching record.
For the admin: `GET /api/_automations` and `POST /api/_automations/{name}/run`.

### Forms

Server → Forms: a public page that adds records to a collection (contact, application, survey). The URL is
`http://<phone>:8080/forms/<name>`; with a tunnel or direct access it also opens from the internet. The page follows the
light/dark theme and uses no JavaScript or external resources; inputs match the field type (e-mail, number, date-time,
select, checkbox, multi-line text) and browser validation (required, length, range, pattern) comes automatically.
Server validation errors appear under the relevant field and the entered values are kept.

| Setting | Meaning |
|---|---|
| `fields` | Fields on the form and their order (`name`, `label`, `help`, `multiline`); if empty, all writable fields. Computed and file fields cannot be on a form; required fields must be |
| `submitLabel`, `successMessage` | Button and thank-you text |
| `redirect` | Where to go after submitting (`https://…` or `/…`) |
| `maxPerTenMinutes` | At most this many submissions per address in 10 minutes (5) |

Submissions do not depend on the collection's create rule: the collection can stay closed to everyone while only the
form's fields can be written. Spam protection: a honeypot field people don't see (if filled, the submission looks
successful but is not saved), a signed timestamp (at least 2 s to fill, valid for at most 2 hours) and a per-address
limit. Incoming records produce events like any other: for an e-mail or notification on submission, add an "on create"
rule for the collection under Automations.

```json
"forms": [{"name": "contact", "collection": "messages", "title": "Contact us",
  "fields": [{"name": "name", "label": "Your name"}, {"name": "email", "label": "E-mail"}, {"name": "body", "label": "Message", "multiline": true}]}]
```

### GraphQL

With Wizard → General → **GraphQL** on, `POST /api/graphql` (`{"query", "variables", "operationName"}`) works. The
schema is generated from the collections; for each collection:

| Root | What it does |
|---|---|
| `<name>(filter, sort, page, perPage, q)` | List: `{ items { … } totalItems page perPage }`; `filter` uses the list filter syntax (`done=false&points[gte]=3`) |
| `<name>_by_id(id)` | A single record |
| `create_<name>(data)`, `update_<name>(id, data)`, `delete_<name>(id)` | Mutations; `delete` returns `{ id deleted }` |

```graphql
query {
  open: tasks(filter: "done=false", sort: "-points", perPage: 10) { totalItems items { id title project { name } } }
}
mutation Add($d: JSON!) { create_tasks(data: $d) { id title } }
```

A relation field requested with a sub-selection is expanded (like REST `expand`, with the target's view rule); without
one it returns the id. Rules, field visibility, expert-mode names, script hooks, automations, webhooks and realtime
work exactly as in REST; an endpoint disabled in REST is absent from GraphQL too. Aliases, variables, named and inline
fragments, `@include` / `@skip` are supported. Errors follow GraphQL: a failed root field is `null`, and `errors[]`
carries `path`, `extensions.code` (the REST status code) and validation `details`. Instead of introspection
(`__schema`) the schema language (SDL) is at `GET /api/graphql/schema`; code generators and IDEs can use that file.
Limits: depth at most 8, 20 root fields and 1000 fields; use the realtime endpoint instead of subscriptions.

### AI (Claude, OpenAI, Gemini) and semantic search

Server → **AI**: your apps use AI through the server. You pick the provider; the API key stays in the encrypted store
on the phone and never reaches clients, and usage is billed to your own account with that provider.

| Provider (`provider`) | Preset models | Notes |
|---|---|---|
| **Claude** (`claude`, default) | Opus 5.5 (default), Sonnet 5.5, Haiku 5.5 | Official Claude Java SDK. On Opus and Sonnet, server-side fallback is on: if a safety check declines a request, it is retried automatically on a model Anthropic recommends |
| **OpenAI** (`openai`) | GPT-6 Astra, GPT-6.1 Sol (default), GPT-6 Luna | Chat Completions. With `url`, any OpenAI-compatible service: Ollama on the phone or network (`http://127.0.0.1:11434/v1`), LM Studio, OpenRouter; local services need no key |
| **Google Gemini** (`gemini`) | Gemini 4 Argon, Gemini 3.8 Flash (default), Gemini 3.5 Flash-Lite | `generateContent`; the key goes in the `x-goog-api-key` header |

You can also type a model ID that isn't listed. The effort level (low/medium/high) is sent as `effort` to Claude,
`reasoning_effort` to OpenAI and `thinkingLevel` to Gemini, and left out for models that don't support it. When a
request is declined, the response has `refused: true` and `stopReason: "refusal"` (the same for every provider).
OpenAI and Gemini are called over plain HTTP without SDKs, to keep the APK small.

| Endpoint | What it does |
|---|---|
| `POST /api/ai/chat` | `{"prompt": "…"}` or `{"messages": [{"role": "user", "content": "…"}, …]}` → `{text, model, stopReason, refused, usage, remaining}` |
| `POST /api/ai/ask/<collection>` | `{"question": "…", "limit": 20}`: relevant records (closest in meaning if a semantic index exists, otherwise the newest; query parameters apply as filters, `sort`, `q`) are given to the model; the answer cites `[#id]` and comes with a `sources` list |
| `GET /api/ai/search/<collection>?q=…&limit=10` | Semantic search: records close in meaning even without matching words, with a `_score`; other parameters are filters |
| `GET /api/_ai`, `POST /api/_ai/reindex` | Admin: today's requests and tokens, index status; rebuild the index |

Security: anonymous requests are never accepted; **who can use it** is "signed-in users" or "admins only". Every
non-admin identity has a daily request limit (default 50), 429 when exceeded. Records are always read with the
caller's list rule: only records and fields the caller can already see reach the model and the result, and records are
passed to the model as data, not instructions.

**Semantic search** needs an embedding service to turn text into vectors: **Voyage AI** (recommended by Anthropic;
key from dash.voyageai.com) or an **OpenAI-compatible** endpoint (Ollama or LM Studio on the phone or the network,
e.g. `ollama pull nomic-embed-text`, no key). You pick the fields to index per collection (text and select fields;
encrypted and visibility-restricted fields can't be indexed, because the text goes to an external service and results
would leak the content). Vectors are stored in the `_ds_embeddings` table, updated in the background from record
events, and missing ones are filled in at startup (unchanged records are not sent again). They are not included in
backups or data moves and are rebuilt when needed.

```json
"ai": {
  "enabled": true, "provider": "claude", "apiKey": "secret:ai_api_key", "model": "claude-opus-5-5", "dailyLimit": 50,
  "embeddings": { "provider": "voyage", "apiKey": "secret:embeddings_key" },
  "semantic": [ { "collection": "notes", "fields": ["title", "body"] } ]
}
```

### Realtime subscription

With `realtime.enabled` (Wizard → General → Realtime subscription), clients receive record changes instantly:
`GET /api/realtime?collections=todos,notes` returns a **Server-Sent Events** stream. Each event:

```
id: 3f2a9c1b-17
data: {"action":"create","collection":"todos","record":{"id":5,"title":"süt al",...}}
```

`action` is `create`, `update` or `delete` (on delete `record` is only `{"id":..}`). After connecting, `event: ready` arrives.
List permission is checked for each collection on connect (401/403 otherwise), and the view rule is applied to every event:
with the `owner` rule, a user receives events only for their own records. Scripts' `ctx.db` writes are published too; no
separate events are published for records deleted or cleared by cascading relations.

```js
// Browser: EventSource cannot send headers, so the token goes in the URL (accepted only on this endpoint).
const es = new EventSource(`${base}/api/realtime?collections=todos&token=${userToken}`);
es.onmessage = (m) => { const e = JSON.parse(m.data); /* e.action, e.collection, e.record */ };
es.addEventListener("reset", () => yenidenYukle()); // missed events could not be replayed
```

A connection stays open for at most 30 minutes, with an empty line sent every 25 seconds; when `EventSource` disconnects it
reconnects automatically with `Last-Event-ID` and receives what it missed if within the last 1000 events; otherwise (or if
the server was redeployed) a `reset` event arrives. Clients too slow to keep up with events are disconnected (writes are not
held back; the client reconnects and continues). Exceeding `realtime.maxConnections` (default 100) returns 503. Since a
token in the URL can show up in proxy logs, a short-lived user token is recommended; clients that can send headers
(`fetch`, mobile apps, curl) should use the `Authorization` header. The web panel's records table also refreshes live via this stream.

### Expert mode: automatic fields and endpoints

The **Expert** button in the wizard's top bar (an app setting; it doesn't change the configuration) makes everything that
is added automatically visible and editable:

- **Endpoints:** every built-in endpoint can be disabled (404) or have its path changed. Server, admin and web push
  endpoints in the General step, account and social sign-in endpoints in the Users step, record endpoints in the collection
  editor (all at once with a "base path", e.g. `/urunler` → `GET /api/urunler`, `GET /api/urunler/{id}`). Path parameters
  (like `{id}`) must be kept; conflicting paths are rejected. OpenAPI, client examples and the web panel use the new paths.

  ```json
  "api": {"endpoints": {"auth.login": {"path": "/hesap/giris"}, "batch": {"enabled": false}}},
  "collections": [{"name": "urunler", "endpoints": {"list": {"path": "/urunler"}, "delete": {"enabled": false}}, ...}]
  ```
- **System fields:** `id`, `created_at`, `updated_at` on collections; additionally `email`, `password`, `verified`, `role`
  on users. They can be renamed (`"systemFields": {"id": {"name": "kimlik"}}`) or removed from the API
  (`{"removed": true}`). The name applies in responses, in filter/sort/field selection, in bodies, error details, realtime
  subscription, webhooks and export/import; the original name is also accepted in requests. The database column does not
  change (no data migration, easy to undo); scripts see the original names. If `email` or `password` is removed, the
  password sign-up/sign-in/reset endpoints are closed (only social sign-in remains).
- **Settings:** token lifetimes, failed sign-in limit and duration (`auth.users.loginAttempts`, `loginBlockMinutes`), request
  limits (`server.limits`).

### WebSocket channels

Realtime subscription (SSE) reports record changes; for **two-way** messaging such as chat, multiplayer games or live
cursors, use Wizard → General → Realtime subscription → **WebSocket channels** (`realtime.channels`). A channel name is an
exact name (`lobby`) or a pattern ending in `*` (`room:*` → `room:42`); for each channel you choose who can join and who can
send, whether the member list is shared, and how many recent messages are sent to new joiners.

```js
const ws = new WebSocket("wss://evim.duckdns.org:8443/api/realtime/ws?token=" + token);
ws.onopen = () => ws.send(JSON.stringify({ type: "join", channel: "room:42" }));
ws.onmessage = (e) => {
  const m = JSON.parse(e.data); // joined {members, history} · message {from, data, at} · presence {event, member} · error
  if (m.type === "message") console.log(m.from?.user, m.data);
};
ws.send(JSON.stringify({ type: "send", channel: "room:42", data: { text: "selam" } }));
```

Scripts can broadcast from the server: `ctx.channels.publish("lobby", { duyuru: "bakım 22:00" })`. Channels are kept in
memory (use a collection for persistent messages); at most 20 messages per second per connection, at most 16 KB per message.

### Phone features (SMS, location, status)

Wizard → General → **Phone features** (`phone`): each is turned on separately, and the Android permission is requested when it is.

- **Sending SMS**: verification codes, order notifications. Sent only to numbers starting with allowed country codes
  (default `+90`) and with a daily limit (default 20; 429 when exceeded); every send is written to the audit log (number
  masked, text not stored). Your carrier's SMS charges apply.
- **Location**: the phone's last known location (does not start GPS separately).
- **Device status**: battery, temperature, charging, free storage, memory.

```js
routerAdd("POST", "/otp", (req, ctx) => {
  const code = String(Math.floor(100000 + Math.random() * 900000));
  ctx.sms.send(req.body.phone, "Doğrulama kodunuz: " + code);   // {sent, today, remaining}
  return { ok: true };
}, "public");
const where = ctx.device.location();   // {available, latitude, longitude, accuracyM, at}
const status = ctx.device.status();    // {batteryPercent, charging, …}
```

Admin API: `POST /api/_phone/sms {"to":"+90…","text":"…"}`, `GET /api/_phone/location`, `GET /api/_phone/status`.
If you write a public SMS endpoint, add per-client limits (firewall, quota) and verification.

### App lock

**App lock** on the Status screen: the phone's own authentication (fingerprint, face or screen-lock PIN) is required when
DroidServer opens and after it has been in the background longer than the chosen time (immediately, 1 min, 5 min). While
on, screenshots are blocked and content is hidden in the recent apps list; copying secrets such as the API key/token,
encryption key or cloud backup passphrase, and showing the access QR, require extra confirmation. Turning the lock on or
off also requires authentication; it is unavailable if the phone has no screen lock. The server keeps running while locked.

### Quick Settings tile and home screen widget

To manage the server without opening the app:

- **Quick Settings tile:** pull down the notification shade, tap edit (pencil) and add the **DroidServer** tile.
  Tapping it starts or stops the server; while running the tile is active and shows the address underneath
  (Android 10+). If the phone is locked, it asks you to unlock first. Without a configuration it opens the app.
- **Widget:** long-press an empty spot on the home screen → Widgets → **DroidServer**. It shows the status, local
  network address, requests in the last hour and battery; the **Start / Stop** button toggles the server and tapping the
  card opens the app. Values refresh every half minute while the server runs and immediately when the status changes.
  With several server profiles, the title shows the active profile; the tile and widget always control the active
  profile.

### Tasker and automation apps

With Status → ⚙ Settings → **Tasker and automation** on, Tasker, MacroDroid or Automate can control the server and react
to server events. The setting is phone-wide; commands and events apply to the active profile.

**Commands** are sent as explicit broadcasts. Each command must carry the secret key from the settings as the `token`
extra. Android does not reliably tell which app sent a broadcast, so permission comes from this key; commands without
the key, with a wrong key or while the bridge is off are ignored and logged as "rejected".

| Action | What it does |
|---|---|
| `dev.droidserver.action.START` | Starts the server (does nothing without a configuration) |
| `dev.droidserver.action.STOP` | Stops the server |
| `dev.droidserver.action.DEPLOY` | Reloads the configuration and deploys |

In Tasker: Task → System → **Send Intent**. Action one of the above, Extra `token:<key>`, Package
`dev.droidserver.app`, Class `dev.droidserver.app.service.TaskerReceiver`, Target **Broadcast Receiver**.
In MacroDroid: Action → Applications → **Send Intent** (same fields). To try it from a computer:
`adb shell am broadcast -a dev.droidserver.action.STOP -n dev.droidserver.app/.service.TaskerReceiver --es token <key>`.

**Events** are sent only to the apps selected in the settings (Tasker, MacroDroid, Automate, or added by package name):

| Action | Extras |
|---|---|
| `dev.droidserver.event.STARTED` | `profile`, `port`, `url` (local network address) |
| `dev.droidserver.event.STOPPED` | `profile` |
| `dev.droidserver.event.FAILED` | `profile`, `reason` |
| `dev.droidserver.event.ALERT` | `profile`, `kind`, `title`, `message`, `resolved`, `critical` (health alert; while the notification channel is on) |

In Tasker: Profile → Event → System → **Intent Received**, Action one of the above; values arrive as variables such as
`%url` and `%title`. The key is kept in encrypted storage; after **Renew**, commands sent with the old key stop working.

### Command-line tool (CLI)

A single-file tool with no dependencies to manage the server from your computer (Node.js 18+; Windows, macOS, Linux).
The server hands it out without authentication; in the app, Server → **Command line (CLI)** shows copyable commands.

```bash
curl -o droidserver.mjs http://192.168.1.20:8080/api/_cli
node droidserver.mjs login http://192.168.1.20:8080/api          # asks for the admin key (not shown on screen)
node droidserver.mjs status                                     # status, requests, battery, free space
node droidserver.mjs list todos done=false --sort -created_at   # filter: list syntax
node droidserver.mjs create todos '{"title":"Buy milk"}'
node droidserver.mjs export todos --format json -o todos.json
node droidserver.mjs import todos new.csv --dry-run
node droidserver.mjs logs -f                                    # live log
node droidserver.mjs config get -o config.json && node droidserver.mjs deploy config.json
node droidserver.mjs backup create && node droidserver.mjs backup download <name>
node droidserver.mjs api GET /_jobs                              # any endpoint
```

- The key is kept in `~/.droidserver/cli.json` with permissions only you can read (a named, revocable key is
  recommended). `--profile home` for several servers; in CI the `DROIDSERVER_URL` and `DROIDSERVER_KEY` environment
  variables replace the file.
- `login <address> --email <email>` signs in as a user (including two-step verification); rules apply as that user.
  `connect "<droidserver://…>"` connects with the QR text from the phone.
- `logs`, `config`, `deploy` and `backup` use the web panel's endpoints: the panel must be on. `deploy` checks the plan
  first and stops if there are blockers; then it follows the result in the logs.
- For self-signed HTTPS (direct access) provide the certificate: `NODE_EXTRA_CA_CERTS=cert.pem node droidserver.mjs …`.
- Messages are in Turkish or English depending on the system language. File fields are not imported.

### MQTT (IoT)

Sensors, ESP32/Arduino and devices such as Tasmota connect to the phone over MQTT (Server → **MQTT (IoT)**, `mqtt`).
The phone has its own MQTT server; no separate broker is needed.

- **Credentials:** a shared username and password for all devices (if the password is left empty one is generated,
  kept in encrypted storage). Failed attempts count towards the firewall. The connection is plain TCP (default 1883):
  use it only on the local network.
- **Save messages:** a message matching a topic filter is added to the collection as a record; automations, webhooks
  and realtime subscriptions fire too. The fields of a JSON object are written to the collection's fields (unknown ones
  are skipped); a plain value (`23.5`, `on`) goes to the value field. The topic field holds the filter's `+` part
  (device/room name).
- **Record events:** every change in the chosen collections is published as JSON to the
  `droidserver/<collection>/<create|update|delete>` topic.
- **Commands:** send messages to devices from the app or with `POST /_mqtt/publish {"topic":"home/lamp","payload":"on"}`
  (admin). Devices can also publish to each other (`+`, `#` wildcards).
- Messages arriving with QoS 0, 1 and 2 are accepted and forwarded to subscribers with QoS 0; there are no retained
  messages or wills.

```json
"mqtt": { "enabled": true, "port": 1883, "username": "device", "password": "secret:mqtt_password_ab12cd34",
  "routes": [{ "topic": "sensors/+/temp", "collection": "readings", "valueField": "temp", "topicField": "device" }],
  "events": ["readings"] }
```

```bash
mosquitto_pub -h 192.168.1.20 -u device -P <password> -t sensors/living/temp -m 23.5      # → readings record (device=living)
mosquitto_sub -h 192.168.1.20 -u device -P <password> -t 'droidserver/readings/#'         # record events
curl -X POST -H "Authorization: Bearer <key>" -d '{"topic":"home/lamp","payload":"on"}' http://192.168.1.20:8080/api/_mqtt/publish
```

Status: `GET /_mqtt` (connected devices, their subscriptions, recent messages and their results). The app screen shows
the same.

### Network drive (WebDAV)

Server → **Network drive**: opens folders on the phone as a drive on your computer. You can drag and drop files, edit
them in place and create folders. The address is `http://<phone>:8080/dav` (the path can be changed); the username and
password are shown on the screen (if the password is left empty a strong one is generated and kept in encrypted storage).

| Share | Folder | Writable |
|---|---|---|
| `drive` | The drive folder on the phone (always; not included in backups) | Yes (while "Drive is read-only" is off) |
| `site` | Static site (`www/`), optional | Yes; changes go live immediately |
| `scripts` | Scripts, optional | Yes; take effect on deploy |
| `uploads` | Files uploaded to records, optional | No (read-only) |

Connecting:

- **Windows:** This PC → **Add a network location** → the address. If it is not accepted, type `\\<phone>@8080\dav`.
  Windows uses Digest over plain HTTP and only tries Basic over HTTPS (the server also accepts Basic only over HTTPS);
  so if it says the password is wrong, re-enter the username and password, there is no need to switch to HTTPS. The
  **WebClient** service must be running. Windows limits a single file to 50 MB by default (registry:
  `WebClient\Parameters\FileSizeLimitInBytes`).
- **macOS:** Finder → Go → **Connect to Server** → the address.
- **Linux (GNOME):** Files → Other Locations → `dav://<phone>:8080/dav`; or `davfs2`.
- **Linux (KDE/Dolphin):** type `webdav://<phone>:8080/dav` in the location bar; or Network → **Add Network Folder** →
  WebDAV, Server: the IP only (no `http://` or port), Port: `8080`, Folder: `/dav`, encryption off.
- **Phones and tablets:** Solid Explorer, CX File Explorer, iOS Files (add a WebDAV server).

Security:

- Authentication uses **Digest**; the password never crosses the network in clear text, so Windows can connect over
  plain HTTP too. Basic is only accepted over HTTPS (direct access or tunnel); use the HTTPS address from the internet.
- Wrong passwords count toward the firewall's failed-attempt counter (a client's first request without credentials does
  not).
- Paths are only resolved inside the shared folder; `..`, symbolic links and paths leaving the folder are rejected.
- A single file is at most `maxFileMb` (default 2048 MB); uploads go to a temporary file and replace the target when done.
- Locks are not real (clients ask for a lock before writing); don't edit the same file from two computers at once.

```json
"webdav": { "enabled": true, "path": "/dav", "username": "droid", "password": "secret:webdav_password", "site": true }
```

### Troubleshooter

If the server is unreachable, Status → **Troubleshooter** checks on a single screen: the configuration, whether the server
responds, the local network address, internet connectivity, access from the internet (tunnel address, router forwarding,
DuckDNS), certificate validity, components, battery optimization, notification permission, free storage and active health alerts.
Each finding has a fix button next to it (start the server, the relevant Android setting, the wizard's Access step, Logs).

What prevents long-running operation shows up here too: the app's battery setting is **Restricted** (Android stops the
server in the background), Android 12+'s child process restrictions are on while components are in use (see Known Android
limitations), and the system killed and restarted the server service. For the last one Android 11+ also says why the
previous process ended (out of memory, excessive battery usage, stopped by the user…); the same goes to Logs and to a
health alert.

### Environment and secrets (.env)

Server → **Environment and secrets**: shared environment variables for the whole server (`env`). Scripts read them from
`ctx.env` (script-specific `scripts.env` overrides the same name), your own apps receive them as environment variables, and
components with "use shared environment" on receive them too. Each variable is either a **secret** (lock icon; the default)
or plain text:

- Secrets are kept in the store encrypted with an Android Keystore key; `server.json`, history and backups contain only a
  `secret:env_…` reference. They are masked on screen; **Show values**, **.env text** and **Export** require authentication
  when app lock is on.
- **.env text**: `NAME=value` lines, `#` comments, `export` prefix, single/double quotes, `\n` in double quotes and multi-line
  values (certificates). No variable expansion (`${NAME}`). If the same name appears twice, the last one wins.
- **Import** adds the variables from a `.env` file (new ones are treated as secrets); **Export** writes all values as plain
  text (for moving to a new phone; delete it when done). Since backups don't contain secret values, they must be re-entered
  on the new phone; the missing ones are listed on screen.
- Saved changes take effect on the next deploy ("Save and deploy" applies them immediately). An unsaved wizard draft is
  preserved and the same change is applied to it as well.

### Your own apps

Server → **Apps**: run a server you wrote yourself (Node.js, Python, Java/Kotlin or a compiled binary) on the phone.
Example: an API written with Express.

1. Install the runtime from Components (e.g. **nodejs**; see Component catalog).
2. Apps → **+**: name `api`, runtime `nodejs`, command `node server.js`, port `3000`. The app must read its port from the
   `PORT` environment variable (`app.listen(process.env.PORT, "127.0.0.1")`).
3. **Upload zip** (the code with its dependencies, e.g. including `node_modules`) or **Pull from Git**. The deploy starts.
4. The app is served at `https://<server>/apps/api/…`: the HTTPS endpoint, tunnel, firewall and rate limiting apply to it
   too. The path prefix is stripped by default (`/apps/api/users` → `/users` for the app).

```json
"apps": [{
  "name": "api", "runtime": "nodejs", "command": ["node", "server.js"], "build": [["npm", "ci", "--omit=dev"]],
  "port": 3000, "path": "/apps/api", "access": "public", "health": "/health",
  "env": { "LOG_LEVEL": "info" }, "sharedEnv": true, "websockets": true,
  "git": { "provider": "github", "repo": "sahip/depo", "branch": "main", "subdir": "backend",
           "token": "secret:git_token_…", "webhookSecret": "secret:git_hook_…" }
}]
```

| Field | Description |
|---|---|
| `runtime` | An installed runtime component (`nodejs`, `python`, `openjdk`); if empty, the command is a program in the code or `sh` |
| `command` | Start command; the first item is looked up in the runtime's `bin`, then in the code (`node_modules/.bin`, `bin/`). No shell is run |
| `build` | Once per new release, before starting (at most 5 commands, 15 min). Packages that need compiling may not install on the phone: putting dependencies in the zip is safest |
| `port` | 1024-65535; cannot equal the server's or another app's port |
| `path`, `stripPath` | Public path (`/apps/<name>`); cannot conflict with the API path, `/_admin`, `/_auth` or another app's path. With `stripPath: false` the prefix is passed to the app (in the `BASE_PATH` environment variable) |
| `access` | `public`, `auth` (signed-in user), `admin`. Identity is verified by the server and passed to the app in the `X-DroidServer-User`, `X-DroidServer-Role`, `X-DroidServer-Admin` headers (`X-DroidServer-Key` when a key is used); the server's token is not forwarded to the app. `X-DroidServer-*` headers sent by the client are dropped |
| `health`, `startTimeoutSec` | Health check path (2xx/3xx expected; if empty, only the port is checked) and the maximum wait (60 s) |
| `env`, `sharedEnv` | App-specific variables (plain text; keep secrets in Environment and secrets) and whether shared variables are passed (on by default) |
| `restart`, `keepReleases`, `maxBodyMb`, `websockets` | On crash (`on_failure`, `always`, `never`), releases kept (3), request body limit (10 MB, including uploads), WebSocket forwarding |

What the server gives the app: `PORT`, `HOST=127.0.0.1`, `BASE_PATH`, `DATA_DIR` (a folder that persists across releases;
put your SQLite file here), `HOME`, `TMPDIR`, `DROIDSERVER_APP` and the runtime's variables. Requests are proxied as
streams (SSE, long responses and large downloads are not buffered in memory); `X-Forwarded-For/Proto/Host/Prefix` are added,
and the app's redirects such as `/login` are rewritten to `/apps/api/login`.

**Releases and rollback:** every upload or Git pull is a new folder (`apps/<name>/releases/<time>`); the running release
is not touched. On deploy the new release is started and the health check is awaited; if it fails (or the build command
fails), it is dropped, the previous release is restarted and the problem is written to Logs and a notification. You can
roll back to an older release from the **Versions** list. A single top-level folder in the archive (GitHub archives) is
skipped; scripts and ELF binaries are made executable. Extracted archives are limited to 1 GB / 200,000 files, and entries
that write outside the archive are rejected.

**Deploy from Git:** GitHub, GitLab (including self-hosted), Gitea/Forgejo or a plain zip URL. No `git` is needed on the
phone: the branch's latest commit is fetched via the provider's API and that commit's archive is downloaded; if the same
commit is already installed, it isn't downloaded. A read-only token is enough for a private repo (GitHub: fine-grained
token, Contents: Read); the token is kept in the encrypted store and sent only to the repo's server (not to GitHub's archive redirect).
Turning on **Deploy automatically on push** generates a webhook secret; in the repo's webhook settings enter
`https://<server>/api/_apps/<name>/hook` as the address, `application/json` as the content type, and the secret. Verification: GitHub
`X-Hub-Signature-256`, Gitea/Forgejo `X-Gitea-Signature` (HMAC-SHA256), GitLab `X-Gitlab-Token`; to call it from CI, use
the `X-DroidServer-Token` header. Only pushes to the selected branch are deployed; ping events are answered. Admins can pull
and deploy with `POST /api/_apps/<name>/deploy`, and `GET /api/_apps` lists statuses.

**Limitations:** the app should listen only on `127.0.0.1`, but other apps on the phone can still connect to this port
directly (on Android local ports are shared between apps); `auth`/`admin` rules apply only to requests coming through the
server, so use your own authentication for sensitive operations as well. No Docker (requires root). Regular Linux (glibc)
binaries don't run; you need Termux builds or statically compiled programs. Android 12+ limits the number of child
processes (see Known Android limitations). WebSocket subprotocol (Sec-WebSocket-Protocol) negotiation is not forwarded. JVM
apps use 200-300 MB of memory; add a limit such as `-Xmx256m` to the command.

### Server profiles

Server → Profiles: multiple independent servers on the same phone (e.g. "Home", "Test", "Customer A"). Each profile has its
own configuration, port, SQLite database, uploaded files, scripts, static site, backups and history; the existing setup is
the "Default" profile (its folder layout doesn't change; the others live under `files/profiles/<id>/`). One profile runs at
a time: switching stops the running server and, if the new profile is set up, starts its server (otherwise the wizard opens).
Installed components (PostgreSQL, cloudflared), the HTTPS certificate and the encrypted secret store are shared; give
profiles that use PostgreSQL different database names. Deleting a profile also deletes its data (the default and active profiles cannot be deleted).

### Live copy (second phone)

So that data isn't lost and service continues if the primary phone fails, a second phone can keep a **read-only live copy**.

1. On the primary server, Wizard → Database → **This server can be copied** (`replication.source`). A database trigger is
   installed on every replicated table: every write, including API, scripts, imports, account operations and cascading
   deletes, lands in the `_changes` log with a sequence number (`replication.keepDays`, 7 days).
2. On the second phone, Wizard → Database → **Make this phone a copy of another server**: **Search the local network** (mDNS) or
   type the address, enter the primary server's admin key (a named full-access key is recommended), then **Connect and set up as a copy**.
   Collections, rules, user settings and secrets (keys, JWT secret, SMTP…) come from the primary; port, database, access,
   backup and alert settings remain the copy's own. The copy can be PostgreSQL while the primary is SQLite (or vice versa).
3. Deploy: the initial copy fetches tables page by page (including files), then syncs incrementally every `intervalSec` (5)
   seconds. The **Live copy** card on the Status screen shows the number of pending changes, the last contact and the last error;
   a health alert fires if it cannot sync for more than 5 minutes.

The copy is **read-only**: read requests work with the same keys and tokens, writes/sign-in/sign-up get `503`, and scheduled
tasks and the job queue wait. When the primary's configuration changes (new collection, field, rule), the copy notices,
saves the new configuration and rebuilds itself; it applies no changes in the meantime. Sessions and the job queue are not copied.

**Manual takeover:** on the copy go to Status → Live copy → **Take over**: syncing stops and the copy becomes a writable
primary server (ID counters are advanced, it is written to the audit log) that can itself be copied. Keys are the same and
users' access tokens stay valid (refresh tokens were on the primary: users whose tokens expire sign in again). The copy
lags by at most `intervalSec`; writes from the last few seconds may be lost.

**Automatic takeover** (on the copy, Wizard → Database → **Take over automatically**, `replication.failover.auto`): if no
response at all comes from the primary for `afterSec` (120) seconds, the copy takes over by itself and sends a critical
health alert. Any HTTP response (including error codes) counts as the primary being up. So that the disconnected one is not
this phone, before taking over: the initial copy must be complete, the phone must be connected to a network and, if a
**witness address** (`witness`, e.g. the router `http://192.168.1.1`) is set, it must be reachable.

**No split writes:** the server that took over remembers the old primary (`formerPrimary`) and tells it "you have been
replaced" every 15 seconds (`/_replication/fence`). When the old primary gets this, it stops writes immediately, backs up
its own data (a `devir-oncesi` backup) and becomes a copy of the new primary: data is taken from there from scratch. If it
was replaced while it was off, at startup, before opening writes, it asks the copies that were connected to it
(`/_replication/role`); if one of them took over, it starts read-only and becomes a copy.

**Failback** (on the primary, **Take the primary role back on return**, `failback`): an old primary that was replaced and
became a copy, after staying up to date for a minute, briefly stops writes on the new primary (`/_replication/step-down`),
syncs up to the last change and takes the primary role back **without loss**; the other becomes a copy again. If it cannot
catch up in 30 seconds it gives up and writes are reopened (they also reopen by themselves after two minutes).

**How clients find the new primary:**

- **DuckDNS:** set the same DuckDNS name on both phones. A copy does not publish the name; after taking over, the new
  primary does.
- **Fallback address (SDK):** give the client both addresses; if a connection cannot be made or the server returns `503`
  (read-only copy), the next one is tried and the working address is remembered.

```kotlin
val ds = DroidServerClient("http://192.168.1.20:8080/api", apiKey = key, fallbackUrls = listOf("http://192.168.1.21:8080/api"))
```

```ts
const ds = new DroidServer("http://192.168.1.20:8080/api", { apiKey, fallbackUrls: ["http://192.168.1.21:8080/api"] });
```

```bash
# Primary server endpoints (admin): used by copies
curl -H "Authorization: Bearer <key>" "http://<primary>:8080/api/_replication/changes?after=0&limit=100"
curl -H "Authorization: Bearer <key>" "http://<primary>:8080/api/_replication/snapshot?table=todos&after=0"
curl "http://<server>:8080/api/_replication/role"     # {"role":"primary|replica|fenced","id":…}; no auth
```

### Local network discovery and QR

While running, the server is announced on the local network via **mDNS/DNS-SD** (`_droidserver._tcp`; name, port, and in
TXT `path` = API path, the `https` port with direct access, `v`). Keys and tokens are not announced; to turn it off use
Wizard → Access → **Be visible on the local network (mDNS)** (`access.discovery: false`).

```bash
dns-sd -B _droidserver._tcp          # macOS;  Linux: avahi-browse -r _droidserver._tcp
```

Status → **Connect (QR)** generates three kinds of QR codes (the address is selectable: local IP, HTTPS address or a
manually entered tunnel address):

- **Address only:** `droidserver://connect?url=http%3A%2F%2F192.168.1.20%3A8080%2Fapi&name=Evim`.
- **Address + access:** in JWT mode a **short-lived token** valid for 1 hour / 1 day / 7 days (`&token=…&exp=…`); in API key
  mode the main key (no expiry, with a warning; a named key is recommended for restricted access).
- **Sign in to panel:** `http://…/_admin/#pair=<code>`; the browser of the device scanning the QR signs in to the panel
  automatically. The code is single-use and valid for 5 minutes; the `#` part of the address is not sent to the server and
  is removed from the address after sign-in.

The client SDKs read the link text and (Kotlin) search the local network:

```kotlin
val ds = DroidServerClient.fromLink(qrText)            // as a token if JWT, otherwise as an API key
val servers = DroidServerClient.discover(2_000)        // [FoundServer(name, port, addresses, attributes)], .baseUrl
```

```ts
const ds = DroidServer.fromLink(qrText);               // browsers can't do mDNS; parseLink(qrText) is also available
```

On an Android client, discovery may require `WifiManager.MulticastLock`; Android's own `NsdManager` also finds the same
type (`_droidserver._tcp`).

### Language

The **Dil / Language** selector at the bottom of the Status screen: *Phone language* (default; English if the phone is not
in Turkish), **Türkçe** or **English**. The chosen language applies to the app itself, the web panel, API error and
validation messages, the OpenAPI document and outgoing e-mails (a single language, since the app and server run in the same
process). Changing it reopens the app; if the server is running, it is stopped and restarted. Logs and stored data (e.g.
templates' sample records, audit log details) are not translated.

Source strings are Turkish and serve as catalog keys: `tr("{0} kayıt silindi", n)` in Kotlin, `T("Kaydet")` in the panel.
The catalogs are under `core/src/main/resources/i18n/`: `en.json` (app + server) and `panel-en.json` (web panel; loaded
via `/_admin/i18n.js`). A new language: one line in `Lang` and two catalog files. `I18nTest` checks that every source string
is in the catalog and that `{0}` placeholders match; when adding new strings, add them to the catalog too.

## Internet access

Chosen in the wizard's **Access** step. For each option the app does what it can itself and explains the rest step by
step; while the server runs, the Status screen shows the addresses and any detected problems.

| Option | What you need | What the app does | Good to know |
|---|---|---|---|
| **Local network only** | — | `http://<phone-ip>:<port>` | Guest networks may isolate devices from each other |
| **Direct (IP)** | Wi-Fi: port forwarding on the router; mobile: a public IPv6 from the carrier | HTTPS endpoint, certificate (self-signed or Let's Encrypt with a DuckDNS name), automatic port opening on the router (NAT-PMP / UPnP), CGNAT detection, DuckDNS updates | Does not work on Wi-Fi behind CGNAT; IPv6 only on mobile data |
| **Cloudflare Tunnel** | Quick tunnel: nothing; own domain: free account + domain | Installs and runs the `cloudflared` component, shows the quick tunnel address | The quick tunnel address changes on every start; traffic goes through Cloudflare |
| **Tailscale** | The Tailscale app on the phone and on the accessing device | Detects that the app is installed/connected, shows the Tailscale address | The server is not exposed to the internet; Android allows only one VPN at a time |

**Direct access details** (`core/net/`):

- **HTTPS endpoint** (`TlsProxy`): since Ktor CIO does not support TLS, TLS terminates on `access.direct.port` (default
  8443, IPv6 + IPv4) and the request is forwarded to `127.0.0.1:<server.port>`. TLS 1.2/1.3 only; at most 64 concurrent
  connections, and a connection is closed if the headers don't arrive within 15 s or exceed 16 KB (slow-client attack).
  Client-sent `X-Forwarded-For` and similar headers are stripped and the real address is added. Turning access off also
  drops open connections.
- **Certificate** (`TlsIdentity`): an EC P-256 self-signed certificate is generated on first use and stored in the app
  folder (its password in encrypted storage), so the fingerprint does not change. Clients pin the public key:
  `curl --pinnedpubkey "sha256//<pin>" -k https://<address>:8443/api/health` (copyable on the Status screen).
- **Let's Encrypt** (`CertificateManager`, `AcmeClient`; optional, requires a DuckDNS name): when "Get Let's Encrypt
  certificate" is turned on in the wizard, a publicly trusted certificate is obtained for `<name>.duckdns.org`; the browser
  warning goes away and `-k` and pinning are not needed. Validation uses DNS-01: the value is written to DuckDNS's TXT
  record (ports 80/443 not needed, HTTPS works on whatever port it uses) and removed when done. The certificate is
  requested with the HTTPS endpoint's **existing key**, so the public key pin does not change. The 90-day certificate
  renews itself when 30 days remain; if it cannot be obtained, the self-signed certificate is used and it is retried
  6 hours later (to avoid Let's Encrypt's failed validation limit). The ACME account is a separate key generated on the
  phone; no e-mail is required. Turning the option on means accepting the Let's Encrypt terms of service. Browsers trust
  only the name; connecting by IP address shows a warning.
- **Port forwarding** (`PortMapper`): on Wi-Fi, NAT-PMP first, then UPnP IGD; mappings are leased, renewed at half their
  lifetime and removed when the server stops. If that fails, manual forwarding steps are shown (including DHCP
  reservation and "device MAC address").
- **Reachability** (`Reachability`): CGNAT if the router's external address is shared (100.64/10) or private, a double NAT
  warning if it differs from the address seen from the internet (api.ipify.org); on mobile, IPv4 is CGNAT and IPv6 depends
  on the carrier.
- **DuckDNS** (optional): the A/AAAA record is updated when the address changes; the TXT record too during Let's Encrypt
  validation.
- When the network changes (Wi-Fi ↔ mobile, new address) everything is rechecked within a few seconds; also every 10 minutes.

**Cloudflare Tunnel:** the `cloudflared` package is built from the Termux build (`tools/package-cloudflared.sh`): since it
links against bionic libc, it resolves addresses with Android's resolver (`GODEBUG=netdns=cgo`; respects Private DNS and VPN).
The `resolv.conf` path Go reads is rewritten in the binary to `files/dns.conf`, which the app writes (the network's DNS
servers; refreshed when the network changes). The quick tunnel runs with `cloudflared tunnel --url http://127.0.0.1:<port>`,
and the address is read from the log.
With your own domain: create a tunnel under Zero Trust > Networks > Tunnels, set the Public hostname to
`http://localhost:<port>`, and enter the token in the wizard.

## App update

The **App update** card on the Status screen looks for a new version on GitHub Releases (`kasimtmc/droidserver`): once a
day if the setting is on (in the background while the server runs, a new version is notified once; also when the app
opens), and on demand with "Check now". Pre-releases (beta) are offered only if selected; automatic checking can be turned off.

"Download and install": the APK is downloaded, its digest is verified against the release's `.sha256` file (a release
without a digest file is not installed), the package name, version code and **signing certificate** are compared with the
installed app, and then it is installed with Android's `PackageInstaller`; Android asks for confirmation on every install
(the "install unknown apps" permission is requested only once, for this app only). The app closes during installation; if
the server was running, it restarts automatically when the new version opens (`MY_PACKAGE_REPLACED`), and data and
settings are kept.

The **debug** build installed from Android Studio is signed with a different key; release builds cannot be installed over
it (the card says so). To switch to a release build, back up and export from Backups, uninstall the app, install the APK
from the release page and restore the backup.

## Publishing a release

`.github/workflows/release.yml`: when a `v1.2.3` tag is pushed (or manually from Actions, entering a version), tests run,
a signed release APK is built, checked with `apksigner` and uploaded to the release as `droidserver-1.2.3.apk` +
`.apk.sha256` (versions with a suffix like `-beta.1` become pre-releases). If `.github/release-notes/<version>.md` exists
the release notes start with it (template: `TEMPLATE.md`; the in-app update shows this text), followed by notes generated
from commits. The version
name and code come from the tag (`-PappVersion=1.2.3`; code `1.2.3 → 1020399`, pre-releases are lower than the stable
release of the same version).

Create the signing key once and add four secrets (Settings → Secrets and variables → Actions):

```bash
keytool -genkeypair -keystore release.jks -alias droidserver -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks    # output → ANDROID_KEYSTORE_BASE64
# ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS (droidserver), ANDROID_KEY_PASSWORD
git tag v0.9.0 && git push origin v0.9.0
```

Keep `release.jks` and its passwords somewhere safe: if the key is lost, installed apps cannot receive new versions as
updates. Since the app does not authenticate when downloading, releases must be public. Component packages are uploaded
to the `components` release by a separate workflow (`components.yml`); the update check skips it.

## Components (side services)

The app downloads packages from a `components.json` manifest that you host
(example: `docs/components.example.json`; for Postgres, this repository's GitHub Releases, see PostgreSQL). For each package:

1. The zip matching the ABI is downloaded and **its sha256 verified** (not installed on mismatch).
2. It is extracted with zip-slip protection, and the `executables` files are made executable.
3. On first start, the `setup` commands run once (e.g. `initdb` for Postgres).
4. `ProcessSupervisor` starts the process; according to the `restart` policy it restarts it with a 1 s → 60 s
   backoff, and moves to the `FAILED` state after 10 attempts.
5. On deploy, a component restarts only if its definition (command, environment, version …) changed or it has stopped for good.
6. If the package defines `"readiness": { "tcpPort": 5432, "timeoutSec": 30 }`, the server is not deployed until the
   port accepts connections. If the timeout expires or the process crashes, the error is logged and the deploy continues.

7. `setupFiles` are temporary files that exist only during first setup (e.g. the initdb password file); they are readable
   only by the owner and deleted when setup ends (even if it fails). If `stopCommand` is given, it runs first when
   stopping, instead of SIGTERM (`pg_ctl stop -m fast` for Postgres).

8. `tasks` are maintenance jobs the user can run (e.g. Postgres `set-password`): the component is stopped first
   (`stopFirst`), the command runs, and `stdin` is written to the command if given; the next deploy restarts the component.
   Secret values are masked as `***` in the output.

Placeholders: `${PKG}` the package folder, `${DATA}` the component's persistent data folder, `${env:NAME}` a component
environment variable; in `tasks[].stdin` also `${sqlstr:NAME}`: the environment variable's SQL string value (dollar-quoted).

Placeholders also work in the command (e.g. Redis `--requirepass ${env:REDIS_PASSWORD}`). Optional fields:
`kind` (`service` by default; `runtime`: not a process but a runtime used by apps), `paths` (folders added to PATH in the
runtime, e.g. `${PKG}/bin`), `secrets` (secrets the component needs: when run from the catalog, they are generated and
added to encrypted storage if missing from the shared environment).

### Component catalog

This repository's `components` release (the default component repository) also publishes the following; they are listed
when the Components screen opens:

| Package | Type | Usage |
|---|---|---|
| `nodejs` | Runtime | Node.js LTS and npm/npx. App command `node server.js`, setup `npm ci --omit=dev` |
| `python` | Runtime | Python 3 and pip. Command `python3 app.py`; for dependencies, setup `pip install -r requirements.txt --target .deps` and `PYTHONPATH=.deps` in the app environment |
| `openjdk` | Runtime | OpenJDK 21 (Java, Kotlin, Ktor, Spring). Command `java -Xmx256m -jar app.jar` |
| `redis` | Service | `127.0.0.1:6379` only, password-protected. **Install and run** generates the password as `REDIS_PASSWORD` and adds it to Environment and secrets; your apps read it from there |

Services are added to the configuration and deployed with the **Run with the server** switch (they also get the shared
environment); runtimes are only installed. Packages are built from Termux builds with
`tools/package-termux.sh <redis|nodejs|python|openjdk>`: dependencies are resolved, libraries are found relative to the
package, scripts such as `npm`/`pip` are replaced with wrappers that work on the phone (Termux's `#!` path does not exist
there), and root certificates are added for Python and Node.
**Note:** the packages have not been tested on a real device yet; watch the Logs screen on first install.

**Where to find binaries:** the Termux package repository offers ARM64 builds of PostgreSQL, Redis, nginx etc.; they are
compiled against Android's bionic libc. For PostgreSQL, `tools/package-postgres.sh` turns this build into a component
package (see below), `tools/package-cloudflared.sh` does the same for cloudflared, and `tools/package-termux.sh` for the
catalog packages. Static binaries written in Go (PocketBase, Caddy) run directly but look for the nonexistent
`/etc/resolv.conf` for DNS; prefer Termux builds.

## PostgreSQL

When PostgreSQL is chosen in the wizard's **Database** step:

- the app generates a database password and puts it in encrypted storage; the `database` section is set to
  `jdbc:postgresql://127.0.0.1:5432/postgres` with the `droid` user, and the `postgres` component is added to the
  list;
- if the component is not installed, the **Install** button in the same step downloads it from the component repository;
- on first deploy, `initdb` reads the password from a temporary `.pwfile` (deleted afterwards), `postgres` starts
  listening only on `127.0.0.1`, and the server waits until the port is open;
- when the database changes, you are asked **whether to migrate** existing records (you cannot proceed without choosing).
  If migrating, tables are copied with their IDs, the ID sequence is adjusted and row counts are compared;
  on any problem the old database stays in use and old data is not deleted. You also choose:
  - during the migration the server either **stops** or runs **read-only** (reads continue, writes get `503` +
    `Retry-After`; the migration does not start until in-flight writes finish),
  - if the same collection already has records in the new database, the migration either **stops** or the records there
    are **deleted** and these are written;
  - if a field's type is also changing, values are converted according to the type change choice above.
- **Database name** is the last part of the URL (default `postgres`); if **Create if missing** is checked
  (`"createIfMissing": true`), `CREATE DATABASE` runs on the first connection.
- **Password:** the generated password can be shown and copied, regenerated, or the user can enter their own.
  If the password changes while Postgres is running, a method is chosen on save: **change** (connect with the old
  password and `ALTER ROLE`; the server keeps running) or **reset** (if the old password doesn't work: Postgres stops
  briefly and the component's `set-password` task changes the password in single-user mode).
- **Delete PostgreSQL data** (with confirmation, cannot be undone): the data folder is deleted, and on the next start
  `initdb` runs again with the password from the configuration.

If Postgres crashes, the supervisor restarts it; meanwhile the API returns `503`, and requests recover by themselves
once Postgres is back (the connection pool discards dead connections and opens new ones).

**Building the package** (Linux or macOS; the binaries are not run, only packaged):

```bash
tools/package-postgres.sh dist/          # ARCH=aarch64 by default; arm and x86_64 are also supported
# → dist/postgres-18.6-arm64-v8a.zip and .sha256; if patchelf is available, library paths are made package-relative
```

The script resolves and downloads the `postgresql` package and all its dependencies from the Termux repository, verifies
their sha256s, and zips them in a `bin/` (postgres, initdb, pg_ctl, psql, pg_dump …), `lib/` and `share/` layout.
Two Termux-specific things are fixed: symbolic links do not stay links in the zip (Android's extractor breaks them;
libraries are kept as a single copy under their SONAME), and the Termux temp folder embedded in the binaries
(`/data/data/com.termux/files/usr/tmp`; it does not exist on a phone without Termux, and the shared memory library loops
forever there) is rewritten to the app's folder (`tools/patch-termux-paths.py`). The app also applies the same two fixes
once at startup to components installed from older packages.
`tools/make-manifest.sh dist <url-prefix>` generates `components.json` from the zips (template: the `postgres` entry
in `docs/components.example.json`).

**Hosting (GitHub Releases):** `.github/workflows/components.yml` (run manually or when relevant files reach `master`)
builds the PostgreSQL, cloudflared and catalog packages for three ABIs (arm64-v8a, armeabi-v7a, x86_64) (skipped if there
is no Termux package for an architecture), writes the manifest and uploads everything to this repository's `components`
release. The app's **component repository URL** defaults to this project's:
`https://github.com/kasimtmc/droidserver/releases/download/components/components.json` (can be changed to your own URL in
the Components tab; in forks, change the `<owner>/<repo>` part). The app does not authenticate when downloading: if the
repository is private, release files cannot be downloaded; make the repository public or run the workflow in a separate
public repository. The commands in the manifest (`initdb` with a password file, the `set-password` task, `pg_ctl stop`)
are tested end to end with real PostgreSQL binaries (embedded-postgres's Linux build). **Note:** the Termux build has not
been tested on a real device yet; watch the Logs screen on first install.

To use your own PostgreSQL server, change the connection URL and user in the Database step, and set
`"enabled": false` for the `postgres` component in the JSON. SSL that does not verify the certificate
(`sslfactory=...NonValidatingFactory`) is rejected in the URL; use `sslmode=verify-full` for a remote server. (The lint
warning for this class in the pgjdbc jar is suppressed in `app/lint.xml` for that jar only.)

## Why targetSdk 28?

Apps targeting Android 10+ cannot execute binaries from writable folders (`filesDir`) (W^X).
`targetSdk 28` lifts this restriction; Termux uses the same approach. The cost: it cannot be published on the Play Store,
and Android 14+ shows a "built for an older version" warning on install. The app can be installed on Android 8.0
(`minSdk 26`) up to current versions.

## Known Android limitations and workarounds

| Problem | In this project | What you need to do |
|---|---|---|
| Killed in the background | Foreground service + `START_STICKY` + wake/wifi lock | In the app, "exempt from battery optimization" |
| Vendor battery management (Xiaomi, Samsung, Huawei) | — | Autostart / unrestricted battery in settings; see dontkillmyapp.com |
| Phantom process killer (Android 12+) | The supervisor restarts it | The ADB command below or Developer options > "Disable child process restrictions" (14+) |
| Ports below 1024 | Blocked by the validator | Use 8080/8443 |
| No inbound access on mobile data (CGNAT) | Direct access tries IPv6; the Cloudflare quick tunnel works without an account | Cloudflare or Tailscale in the Access step |
| The phone's local IP changes | The Status screen shows the current address; port forwarding is redone | DHCP reservation on the router, "device MAC address" on Wi-Fi |

```bash
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell settings put global settings_enable_monitor_phantom_procs false
```

## Project structure

```
core/src/main/kotlin/dev/droidserver/core/
  config/      ServerConfig, ConfigValidator (errors with field paths), ConfigMigrations (version upgrades),
               ConfigSecrets (secret references), ConfigTemplates, ConfigSummary (access/CORS mode, summary),
               ConfigDiff (history diff, data loss warnings), FieldDefaults, DatabaseChoice (SQLite/Postgres)
  db/          SqlDriver interface, SqlDialect (SqliteDialect, PostgresDialect), JdbcSqlDriver,
               PostgresDriver (connection pool), SchemaMigrator (plan + apply), DataCopier (data migration)
  api/         RecordService (CRUD + validation), ServerModule (Ktor setup, auth, endpoints), RequestGuard (per-client
               limits), ApiExamples (curl/JS/Kotlin, try-it), RealtimeRoutes (SSE), SearchText (search),
               OpenApi (docs), AuthPages (/_auth pages)
  realtime/    RealtimeHub (event fan-out, recent events buffer)
  mail/        SmtpClient (STARTTLS/SSL, AUTH), Mailer (account e-mails, limits)
  net/         DirectAccess (direct access), TlsProxy, TlsIdentity, PortMapper (NAT-PMP/UPnP), Addresses
               (address types, reachability), WanServices (public IP, DuckDNS), AcmeClient + CertificateManager
               (Let's Encrypt, DNS-01), Der (certificate and CSR encoding)
  update/      ReleaseChecker (GitHub Releases, sha256-verified download), Versions (semantic versioning)
  server/      DeployOrchestrator (deploy order), ServerEngine (HTTP + schema lifecycle, schema plan)
  process/     ProcessSupervisor, ReadinessProbe
  components/  ComponentManager
  automation/  AutomationEngine (no-code rules: record events and schedules, actions)
  graphql/     GqlParser, GraphQL (schema, queries and mutations; same rules as REST)
  webdav/      WebDav (network drive: PROPFIND, PUT, MOVE…, Digest auth)
  mqtt/        MqttBroker (MQTT 3.1.1 server: QoS 0-2, wildcards), MqttIngest (message → record)
  replication/ ChangeLog, Replicator (live copy), Failover (automatic takeover, demotion, failback)
  auth/        UserService, TeamService (teams), SmsGuard, Totp, OAuth, ApiKeys
  ai/          AiChat (Claude: official Java SDK; OpenAiChat, GeminiChat: HTTP), SemanticIndex (vector index),
               HttpEmbedder (Voyage AI / OpenAI-compatible embeddings)
core/src/test/  CoreTest, ComponentManagerTest, ConfigMigrationsTest, DeployOrchestratorTest, WizardSupportTest,
                for SQLite + PostgreSQL: DialectContractTest, SchemaPlanTest, DataCopierTest; PostgresResilienceTest
                AccessConfigTest, DirectAccessTest (TLS endpoint, NAT-PMP/UPnP with a fake router), RequestGuardTest
                (93 tests; TestDb manages embedded PostgreSQL)
app/src/main/kotlin/dev/droidserver/app/
  DroidServerApp      dependency container (AppGraph), settings
  data/               AndroidSqlDriver, AndroidNetwork (network types, router address, DNS), ConfigRepository,
                      DraftStore, SecretStore, LogBuffer, NetUtil
  data/AppUpdater     app update (signature check, PackageInstaller)
  service/            ServerService, BootReceiver, UpdateReceiver (install result), QuickControls (Quick Settings
                      tile, home screen widget), TaskerReceiver (automation commands)
  ui/                 MainActivity, AppNavigation (bottom bar + full-screen routes)
    status/ config/ components/ logs/   tabs; config/: Server, JSON, History, API explorer
    wizard/           WizardViewModel (pure Kotlin) and step screens
    common/ theme/    shared components, theme
app/src/test/   WizardViewModelTest (15 tests, including end-to-end wizard → deploy)
.github/workflows/  release.yml (signed APK → GitHub Releases), components.yml (component packages)
tools/          package-postgres.sh, package-cloudflared.sh (component packages from Termux builds), make-manifest.sh
```

## Roadmap

- [x] **Wizard UI:** step-by-step collection/field/auth selection instead of a JSON editor
- [x] **PostgreSQL:** Postgres component + pgjdbc; Postgres dialect, data migration from SQLite
- [x] **Relations:** `relation` field type, `onDelete`, `expand` parameter (rules at the API layer)
- [x] **Advanced filters:** `gt`, `lt`, `like`, `in`, `null` operators and field selection
- [x] **User management:** `_users` collection, register/login/refresh endpoints, per-collection access rules, account management from the app
- [x] **Custom business logic:** user-defined endpoints, record events and scheduled jobs in JavaScript (Rhino)
- [x] **Static files / file uploads:** `file` field type (type detection from content, streaming size limit, thumbnails), static site (upload as zip)
- [x] **Internet access:** direct IP (HTTPS + automatic port forwarding + DuckDNS), Cloudflare quick/named tunnel, Tailscale
- [x] **TLS:** Let's Encrypt certificate for direct access (DNS-01 with a DuckDNS name, automatic renewal)
- [x] **Backups:** scheduled backups (SQLite and PostgreSQL, restorable across engines), copy to a folder, restore
- [x] **Web admin panel:** browser-based management under `/_admin` (sign-in with a pairing code)
- [x] **Metrics:** request count, latency, battery/thermal state (`/_metrics`, Prometheus, Status chart, overheating notification)
- [x] **Self-update:** new version check from GitHub Releases, sha256 + signature verified install; signed release workflow
- [x] Restart components only when their configuration changes, not on every deploy
- [x] **Realtime subscriptions:** `/api/realtime` (SSE), rules on every event, resume where it left off
- [x] **Search and indexes:** `?q=` (insensitive to Turkish characters), field indexes
- [x] **E-mail:** SMTP, account verification, password reset, `ctx.mail`
- [x] **OpenAPI:** permission-aware docs, API tab in the panel, JavaScript/Kotlin examples
- [x] **Webhooks:** signed POST, retries, delivery history
- [x] **Batch operations and aggregates:** `/api/batch` (all or nothing), `/aggregate` (sum, average, grouping)
- [x] **Fine-grained permissions:** conditional rules (`status=published`, `@me`, owner exception), field privacy
- [x] **Import / export:** CSV (Excel-compatible) and JSON, preview and column mapping
- [x] **Named API keys:** read-only / limited to collections, expiring, revocable
- [x] **Social login:** Google and GitHub (OAuth2 + PKCE)
- [x] **Web push:** VAPID, RFC 8291, `ctx.push`, notification on record events
- [x] **Server profiles:** multiple independent servers on the same phone
- [x] **Language:** Turkish and English UI (app, web panel, API messages, e-mails)
- [x] **Custom roles and encrypted fields:** roles such as editor/moderator; field encryption with AES-256-GCM
- [x] **Data lifecycle:** retention period, read-only views and reports (CSV)
- [x] **WebSocket channels:** two-way channels for chat and games, online list
- [x] **Phone features:** SMS (limited), location, device status; app lock; troubleshooter
- [x] **Environment and secrets:** shared environment variables in `.env` format, encrypted secrets, import/export
- [x] **Your own apps:** Node.js/Python/Java/binary apps, deploy from zip or Git, health checks and rollback, push webhook
- [x] **Component catalog:** Node.js, Python, OpenJDK, Redis packages; one-tap install and run (awaiting on-device verification)
- [x] **Automation rules:** no-code "when this, do that": record event or schedule, condition; e-mail, notification, webhook, update/create records
- [x] **Forms:** public pages that add records to a collection, spam protection, thank-you page or redirect
- [x] **GraphQL:** schema generated from collections, queries and mutations, relation expansion, same rules as REST
- [x] **AI:** proxy that keeps the key on the server (Claude, OpenAI or Gemini; OpenAI-compatible local models), asking your records, semantic search (Voyage AI or Ollama), daily limit
- [x] **Sign-in by SMS:** passwordless sign-in with a phone number and a code sent from the server phone, per-number and per-client limits
- [x] **Quick Settings and widget:** start/stop from the notification shade and home screen, status, address, requests and battery
- [x] **Tasker and automation:** key-protected start/stop/deploy commands, server events and health alerts to selected apps
- [x] **Network drive:** mount folders on the phone as a WebDAV drive on your computer (Digest auth, read-only shares)
- [x] **Automatic takeover:** the copy takes over when the primary goes down, the returning old primary becomes a copy and can take its role back without loss; fallback address in the SDK
- [x] **Teams:** users create teams and add members; with the `team` rule each team sees only its own records
- [x] **Command line (CLI):** records, export/import, logs, deploy and backups from your computer (single file, Node.js)
- [x] **MQTT (IoT):** an MQTT server on the phone; device messages into collections, record events to devices, commands from the app
