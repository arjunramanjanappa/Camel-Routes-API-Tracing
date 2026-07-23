# TraceGuard — standalone desktop bundle

Make TraceGuard a click-to-launch desktop app that needs **no admin, no install, no PATH change, and no
pre-installed Java** on the machine that runs it. A bundle is a single folder you unzip anywhere in your
user profile; double-clicking the launcher starts the server and opens your browser.

Everything the app stores per-machine (Bitbucket + npm tokens, saved modules) lives in **`~/.traceguard`**
(`%USERPROFILE%\.traceguard` on Windows) — shared by the standalone bundle *and* IntelliJ, so both modes
see the same config.

---

## Sharing it with others (TL;DR)

`mvn spring-boot:run` is **not** shareable — it only runs on your machine and needs the full source, a JDK,
and npm. To hand TraceGuard to someone, build the self-contained bundle and send the zip. The recipient
needs **no admin, no Java, no install**.

**1. Build the bundle with Maven** — one command, from a shell where `npm` works (e.g. IntelliJ's
**Terminal**, the same place `spring-boot:run` works). This is pure Maven, so it works even where group
policy blocks PowerShell / `.bat` scripts:

```
mvn -Pdist clean package
```

It rebuilds the frontend, jlinks a trimmed Java runtime, and produces:

```
target\dist\TraceGuard-windows.zip      (~64 MB: app jar + bundled JRE + launcher + icon)
```

**2. Send `target\dist\TraceGuard-windows.zip`** — email, Teams, or a network share.

**3. The recipient:**
- Unzips it anywhere in their **own** user folder (e.g. `C:\Users\them\Apps\TraceGuard-windows`).
- Double-clicks **`TraceGuard.bat`** → the browser opens at `http://localhost:8080`. Close the console to quit.
- (optional) Desktop icon — no scripts needed: right-click `TraceGuard.bat` ▸ **Send to ▸ Desktop (create
  shortcut)**, then right-click the new shortcut ▸ **Properties ▸ Change Icon** ▸ browse to the bundled
  `traceguard.ico`.
- First run: opens **⚙ Config** and pastes their **own** Bitbucket token (tokens are per-machine — everyone
  sets their own).

> **macOS recipients:** run `mvn -Pdist clean package` **on a Mac** (jlink builds a runtime for the OS it
> runs on) to produce `target/dist/TraceGuard-mac.zip`; they double-click `TraceGuard.command` (right-click ▸
> Open the first time to clear Gatekeeper).

The rest of this document is the detailed reference for each step.

---

## How it runs

```
TraceGuard-windows/
├── TraceGuard.bat        ← double-click this (or its desktop shortcut)
├── app/traceguard.jar    ← the Spring Boot fat jar
├── jre/                  ← a trimmed Java 21 runtime (jlink, ~50 MB) — why no Java is needed on the target
└── traceguard.ico        ← shortcut icon
```

The launcher finds `jre\bin\java` and starts the jar with `-Dtracer.open-browser=true`. **The app itself
opens your default browser** once the server is ready (via `java.awt.Desktop`, falling back to
`rundll32` / `open` / `xdg-open`) — no PowerShell, so auto-open works even where group policy blocks scripts.
The console window stays open; **close it to stop the app.** macOS/Linux use `TraceGuard.command` the same way.

The launcher falls back to `JAVA_HOME`/`java` on `PATH` if no bundled `jre` is present — so the same script
also works on a dev box that already has Java.

### Prefer a real `.exe`?

`mvn -Pexe package` builds a native **`TraceGuard.exe`** (via `jpackage`) with the shield icon and an
embedded runtime — same no-admin, unzip-and-run model, just a double-click `.exe` instead of a `.bat`. It
bakes in `-Dtracer.open-browser=true`, so it starts the server and opens the browser by itself. See
[Building a bundle](#building-a-bundle-on-a-build-machine) below.

---

## Building a bundle (on a build machine)

### Prerequisites (build machine only — the target needs none of this)

| Need | Why | Check |
|------|-----|-------|
| **Full JDK 21** (not a JRE — must have `jlink`) | Builds the trimmed runtime | `java -version` → 21.x, and `jlink --version` works |
| **Node + npm** on `PATH` | Maven compiles the React frontend | `npm -v` prints a version |
| **Maven** (or IntelliJ's bundled one) | Runs the build | `mvn -v` — or use IntelliJ ▸ **Terminal** |

> Easiest: run the build from **IntelliJ's Terminal**, the same place `mvn spring-boot:run` works — it
> already has `npm`, `mvn`, and a JDK on `PATH`.

### Recommended: Maven (`-Pdist`)

```
mvn -Pdist clean package
```

Pure Maven — no PowerShell or `.bat` execution, so it works on locked-down machines where group policy
blocks scripts. The `dist` profile does the normal build, then jlinks a trimmed JRE and assembles + zips the
bundle. `jlink` builds a runtime for the OS Maven runs on, so **build the Windows bundle on Windows and the
mac bundle on a Mac**. Expect a final `[echo]` banner ending in `BUILD SUCCESS`, and:

```
target\dist\TraceGuard-<os>\        the bundle folder
target\dist\TraceGuard-<os>.zip     ship this
```

Normal `mvn package` and `spring-boot:run` are unaffected — the bundling only runs under `-Pdist`.

### Native `.exe` (`-Pexe`, Windows)

```
mvn -Pexe clean package
```

Uses `jpackage` (bundled in the JDK — nothing extra to install) to build a native **app-image**: a folder
with `TraceGuard.exe` + an embedded runtime, carrying the shield icon. No admin, no MSI/WiX installer —
unzip-and-run, like the `.bat` bundle but a real `.exe`. Output:

```
target\dist-exe\TraceGuard\TraceGuard.exe   the app (+ runtime\, app\)
target\dist-exe\TraceGuard-windows-exe.zip  ship this
```

The `.exe` bakes in `-Dtracer.open-browser=true`, so double-clicking it starts the server and opens the
browser by itself; a console window stays open — close it to stop. Windows-only (run it on Windows). Build
both at once with `mvn -Pdist,exe clean package` if you want to offer the `.bat` bundle and the `.exe`.

### Verify before sharing (optional but recommended)

Unzip the output somewhere and double-click the launcher yourself — the browser should open at
`http://localhost:8080` within a few seconds. That confirms the bundled JRE runs the jar on a clean path
before you send it. Close the console window to stop.

### Alternative: standalone scripts

If you'd rather not use the profile (and scripts aren't blocked), the same result comes from
`packaging\build-bundle.bat` (pure cmd — no PowerShell), `packaging\build-bundle.ps1`
(`-SkipBuild` / `-Full` / `-Mvn` / `-JdkHome` options), or `packaging/build-bundle.sh` on macOS/Linux.
These write to `dist\` (repo root) rather than `target\dist\`. If a trimmed runtime ever misses a class,
their `full` / `-Full` option jlinks every JDK module (the default curated set is verified to boot Spring
Boot + Tomcat + Camel + JGit).

---

## Installing on the target (no admin)

1. Copy the zip to the machine and unzip it anywhere under your user folder
   (e.g. `C:\Users\<you>\Apps\TraceGuard-windows`).
2. Double-click **`TraceGuard.bat`** — or **`TraceGuard.exe`** if you built the `-Pexe` variant — on Windows;
   **`TraceGuard.command`** on macOS (right-click ▸ Open the first time to clear Gatekeeper).
3. Optional desktop icon — **no scripts needed:** right-click `TraceGuard.bat` ▸ **Send to ▸ Desktop (create
   shortcut)**. To brand it, right-click the shortcut ▸ **Properties ▸ Change Icon** ▸ browse to the bundled
   `traceguard.ico`. (Where scripts are allowed, `Create-Shortcut.ps1` does this automatically.)

---

## First-run config (the ⚙ Config menu)

Open TraceGuard, click **⚙ Config** in the header, and paste your **Bitbucket** and **npm** tokens.
They are saved to `~/.traceguard/settings.json` on that machine and remembered on every run:

- **Bitbucket token** — used to clone repos in *Bitbucket branch* mode. Takes effect immediately (no restart).
- **npm token** — used by the build script for the private npm registry (build-time only).

Modules you save with **“Save as default”** are stored in `~/.traceguard/app-modules.json`, so your module
lists come back automatically — in both the standalone app and IntelliJ.

> Tokens are stored in plaintext under your user profile (the same model as `~/.npmrc` / a git credential
> store), locked to your user on POSIX filesystems. Keep `~/.traceguard` private.

---

## IntelliJ mode is unchanged

`mvn spring-boot:run` (or the IntelliJ run config) still works exactly as before. It now also reads
`~/.traceguard` for tokens/modules, so config is shared with the bundle. The old `bitbucket.token` in
`application.yml` / the `BITBUCKET_TOKEN` env var still work as a fallback when the Config menu hasn't been
used.

---

## Updating / shipping a new version

There's no auto-update. To push a new build: rebuild (`mvn -Pdist clean package`) and send the new zip. The
recipient deletes their old `TraceGuard-<os>` folder and unzips the new one in its place. Their config
(tokens, saved modules) lives in `~/.traceguard`, **not** in the bundle folder, so it survives the swap —
nothing to re-enter.

---

## Troubleshooting

**Build side**

- **`jlink` not found / “not a JDK”** — you're on a JRE. Point at a full JDK 21: set `JAVA_HOME` to it (or,
  for the scripts, pass `-JdkHome`). Verify with `jlink --version`.
- **`npm` / frontend build fails** — Node/npm isn't on `PATH`. Run from IntelliJ's Terminal, or install Node.
  For a quick jar-only rebuild during dev use `-Dskip.frontend=true` (not for a shared build — it skips the UI).
- **`maven-antrun-plugin ... could not be resolved` (offline)** — the first `-Pdist` build fetches the plugin;
  run once with network access (your normal Maven repo/Nexus is enough).

**Recipient side**

- **“Windows protected your PC” (SmartScreen)** — click **More info ▸ Run anyway**. Unsigned internal tool;
  expected. (Some mail/AV gateways strip `.bat` from zips — share via a file share or an inner zip if so.)
- **Browser doesn't open but the console is running** — open `http://localhost:8080` manually. If another app
  owns port 8080, close it (the app is fixed to 8080).
- **“Could not find the application jar”** — the launcher was moved out of the bundle. Keep `TraceGuard.bat`
  next to the `app\` and `jre\` folders (run it from inside the unzipped folder).
- **A class is missing at runtime** (rare, only if the module set was trimmed too far) — rebuild with the full
  module set: `packaging\build-bundle.bat full` (or `build-bundle.ps1 -Full`). Bigger, includes every JDK module.
- **macOS “can't be opened” (Gatekeeper)** — right-click `TraceGuard.command` ▸ **Open** once; thereafter it
  double-clicks normally.
- **First launch is slow** — the JVM warms up on first start; subsequent launches are quicker.
