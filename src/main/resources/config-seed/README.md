# Bundled config seed (one-time setup shipped with the app)

On first run these files are copied into the machine-wide home (`~/.traceguard`)
**only if that config does not exist yet** — later edits via the UI are never
overwritten. See `ConfigSeeder`.

- `log-rules.json`   → seeds `~/.traceguard/log-rules.json` (host response-code rules)
- `app-modules.json` → seeds `~/.traceguard/app-modules.json` (per-app module mappings)

An empty placeholder (`{}`) seeds nothing.

## To ship your team's setup in a build

Copy your configured files over the placeholders **before packaging**:

```
cp ~/.traceguard/log-rules.json    src/main/resources/config-seed/log-rules.json
cp ~/.traceguard/app-modules.json  src/main/resources/config-seed/app-modules.json
```

**Do NOT copy `settings.json`** — that holds the Bitbucket / npm access tokens.
Tokens are never seeded; each user enters their own in the Config (🔑) menu.
The two files above carry no secrets (rules = code fields / success codes;
module mappings = source type / dir / repo / branch), so the seed is safe to share.
