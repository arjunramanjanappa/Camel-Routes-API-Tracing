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

**1. Build the bundle** — run this from a shell where `npm` works (e.g. IntelliJ's **Terminal**, the same
place `spring-boot:run` works):

```powershell
powershell -ExecutionPolicy Bypass -File packaging\build-bundle.ps1
```

It rebuilds the frontend, jlinks a trimmed Java runtime, and produces:

```
dist\TraceGuard-windows.zip      (~85 MB: app jar + bundled JRE + launcher + icon)
```

**2. Send `dist\TraceGuard-windows.zip`** — email, Teams, or a network share.

**3. The recipient:**
- Unzips it anywhere in their **own** user folder (e.g. `C:\Users\them\Apps\TraceGuard-windows`).
- Double-clicks **`TraceGuard.bat`** → the browser opens at `http://localhost:8080`. Close the console to quit.
- (optional) Desktop icon: `powershell -ExecutionPolicy Bypass -File Create-Shortcut.ps1`
- First run: opens **⚙ Config** and pastes their **own** Bitbucket token (tokens are per-machine — everyone
  sets their own).

> **macOS recipients:** run `./packaging/build-bundle.sh` **on a Mac** (jlink builds a runtime for the OS it
> runs on) to produce `TraceGuard-mac.zip`; they double-click `TraceGuard.command` (right-click ▸ Open the
> first time to clear Gatekeeper).

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

The launcher finds `jre\bin\java`, runs the jar, waits for `http://localhost:8080/`, opens your default
browser, and keeps a small console window open. **Close that window to stop TraceGuard.** macOS/Linux use
`TraceGuard.command` the same way.

The launcher falls back to `JAVA_HOME`/`java` on `PATH` if no bundled `jre` is present — so the same script
also works on a dev box that already has Java.

---

## Building a bundle (on a build machine)

The build machine needs a **full JDK 21** (for `jlink`) and, unless you reuse an existing jar, **npm** on
`PATH` (Maven compiles the React frontend via the exec plugin). The target machine needs neither.

### Windows

```powershell
powershell -ExecutionPolicy Bypass -File packaging\build-bundle.ps1
```

Output: `dist\TraceGuard-windows\` and `dist\TraceGuard-windows.zip`.

Options:
- `-SkipBuild` — reuse the current `target\*.jar` (skip Maven; handy when the jar is already built).
- `-Full` — jlink with `ALL-MODULE-PATH` (bigger, but includes every JDK module) if a trimmed runtime ever
  misses something. The default curated module set is verified to boot Spring Boot + Tomcat + Camel + JGit.
- `-Mvn <path>` / `-JdkHome <path>` — override auto-detection.

### macOS / Linux

```bash
./packaging/build-bundle.sh          # add --skip-build or --full as needed
```

`jlink` builds a runtime for the OS it runs on, so **build the mac bundle on a Mac and the linux bundle on
Linux**. Output: `dist/TraceGuard-<os>/` + zip.

---

## Installing on the target (no admin)

1. Copy the zip to the machine and unzip it anywhere under your user folder
   (e.g. `C:\Users\<you>\Apps\TraceGuard-windows`).
2. Double-click **`TraceGuard.bat`** (Windows) / **`TraceGuard.command`** (macOS — right-click ▸ Open the
   first time to clear Gatekeeper).
3. Optional desktop icon (Windows):
   ```powershell
   powershell -ExecutionPolicy Bypass -File Create-Shortcut.ps1
   ```
   (add `-StartMenu` to also add a Start-Menu entry). It writes a `.lnk` to your Desktop with the TraceGuard
   icon — no admin needed.

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
```
