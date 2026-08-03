# Bundled config seed (one-time setup shipped with the app)

These two files let a build ship the team's one-time setup so a fresh install
starts configured instead of empty. They are handled by `ConfigSeeder`.

- `log-rules.json`   → seeds `~/.traceguard/log-rules.json` (host response-code **Rules**)
- `app-modules.json` → seeds `~/.traceguard/app-modules.json` (per-app **module mappings**, from the Config menu)

Both are `{}` placeholders until you populate them. An empty `{}` seeds nothing.

## Step 1 — produce the files by configuring the app

The app writes these files to the machine-wide home the moment you **Save**. It
does **not** create them until then.

1. Launch TraceGuard.
2. **Config menu (🔑 / ⇄ App set-up)** → add each app's module(s) → **Save as default**.
   → writes `~/.traceguard/app-modules.json`
3. **🚦 Rules** → add your host response-code rules → **Save rules**.
   → writes `~/.traceguard/log-rules.json`

Where `~/.traceguard` is (default — unless you set a custom `tracer.home`):

| OS | Folder |
|----|--------|
| Windows | `%USERPROFILE%\.traceguard`  (e.g. `C:\Users\<you>\.traceguard`) |
| macOS / Linux | `~/.traceguard` |

> On Windows, paste `%USERPROFILE%\.traceguard` into the Explorer address bar —
> the folder starts with a dot and is easy to miss.

## Step 2 — copy the saved files over the placeholders (before packaging)

```
cp ~/.traceguard/log-rules.json    src/main/resources/config-seed/log-rules.json
cp ~/.traceguard/app-modules.json  src/main/resources/config-seed/app-modules.json
```

Then build the package. That's it.

**Do NOT copy `settings.json`** — it holds the Bitbucket / npm access tokens.
Tokens are never seeded or merged; each user enters their own in the Config (🔑) menu.
The two files above carry no secrets (rules = code fields / success codes; module
mappings = source type / dir / repo / branch), so the seed is safe to share.

## What each file looks like (if you hand-edit instead)

Keep them **valid, non-empty JSON objects** — a malformed file is ignored (logged
and skipped) and a bare `{}` ships no defaults.

`app-modules.json` — keyed by app; each module is `sourceType` `"local"` (uses
`sourceDir`) or `"bitbucket"` (uses `repo` + `branch`):

```json
{
  "Mighty": [
    { "sourceType": "local", "sourceDir": "C:/repos/mighty-routes", "repo": "", "branch": "" }
  ],
  "SPL": [
    { "sourceType": "bitbucket", "sourceDir": "", "repo": "https://bitbucket.org/team/spl-routes.git", "branch": "release/9.14" }
  ]
}
```

`log-rules.json` — keyed by app (`Mighty` / `SPL` / `SPL-Secure`); `codeFields`
are fallback code keys, each rule has `match` (host-path glob), `codeField`,
`successCodes`, `skip`:

```json
{
  "Mighty": {
    "codeFields": ["resultCode"],
    "rules": [
      { "match": "*/rest/auth/login", "codeField": "resultCode", "successCodes": ["000000"], "skip": false }
    ]
  }
}
```

## How the seed reaches users across releases

On startup `ConfigSeeder` applies each seed:

- **Fresh install** (no config yet) → the bundled seed is copied in.
- **Existing install, seed unchanged** → nothing happens.
- **Existing install, you shipped an updated seed** → a 3-way merge (`SeedMerge`)
  pushes the changed defaults **without losing user edits**: a corrected default
  reaches a key the user never touched, brand-new defaults are added, and any rule
  the user added / edited / deleted during testing is preserved. A key the seed no
  longer ships is left untouched (never removed).

So testers keep their own rule add/deletes across package upgrades, and you can
still push a corrected default in a later build.
