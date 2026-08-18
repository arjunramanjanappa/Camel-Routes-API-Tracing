# TraceGuard — User Guide

A desktop tool for **release scope, testing and impact analysis** of the Camel routing
framework. Everything runs on your own machine; nothing is installed and no admin rights
are needed.

---

## 1. Start it

- **Windows:** double-click **`TraceGuard.exe`** (or `TraceGuard.bat`).
- **macOS:** double-click **`TraceGuard.command`** (first time: right-click ▸ **Open**).

A console window opens, then your browser opens at **http://localhost:8080**.
**Keep the console open while you work — closing it stops the app.**

> First launch may show *"Windows protected your PC" (SmartScreen)* → **More info ▸ Run
> anyway** (this is an unsigned internal tool). If the browser doesn't open on its own,
> just go to **http://localhost:8080** yourself.

---

## 2. One-time set-up

**Tokens (per user).** Click **⚙ Config** in the header and enter:

- **Bitbucket token** — only if you'll point at a **Bitbucket branch** (not needed for a
  local folder). Takes effect immediately.
- **Splunk base URL** — only for **Release Test**'s result-download links (the address of
  your Splunk Web up to `/services/search/jobs/`). Not a secret, just a URL.

Your **module lists** and **host rules** usually come **pre-loaded** with the app — you
only add your own tokens. Everything you set is saved under your user profile
(`~/.traceguard`) and remembered on every run.

**One requirement on your machine: `git`.** The **Release Impact** tab uses the `git`
command to detect what a release changed. If `git` isn't installed, Release **Scope** and
**Test** still work fully — only Release Impact's change detection is skipped. (Install any
git client and make sure `git --version` works in a terminal.)

---

## 3. Pick your app and source

- **App** — choose **Mighty** or **SPL** on the start screen (the header's **⇄ App**
  switches later). Each app remembers its own source and country.
- **Source** — toggle **Local path** or **Bitbucket branch**:
  - **Local path** — a folder on this machine that holds the routes.
  - **Bitbucket branch** — a repo URL + branch; the app fetches it for you (needs the
    Bitbucket token above). No local checkout required.

---

## 4. The three tabs

### Release Scope
Trace one API end-to-end: its resolved Camel route, the backends it calls, and their
service versions — as a **flow graph** (zoom, fit, PNG) and a per-API catalog you can
**export to PDF**.

### Release Test
Verify that a release actually ran correctly, against logs.
1. **Select the APIs** to analyse (and optionally tick changed routes/backends to pull in
   the impacted ones).
2. **Get the logs** — either copy the generated **Splunk query** and run it, then paste the
   **Job SID** to download the results, **or** upload a raw **output log**.
3. **Verify with logs** — a readiness verdict, a status donut, filter chips (All / Issues /
   per-status), per-API pass/fail with attempts, and a backend breakdown. Export a **PDF
   verification report**.
4. **Release Test - Capability Matrix** *(needs the VAL reports attached in **⚙ Config**)* —
   exports the impacted APIs joined to their **capabilities** (how to test each) as an
   **Excel** workbook for the testing team. When you run several **modules**, you get **one
   sheet per module** (the tab is named after the module) plus a leading **ALL** tab that
   lists every module's rows together, with a **Module** column and the test verdict, so you
   can read the whole release without switching tabs.

**🚦 Rules** — if a backend reports its result under a non-standard key (e.g.
`"resultCode":"000000"`) or a code that shouldn't count, open **🚦 Rules**, add a rule for
that host, and **Save**. A "skip" rule marks a backend as neutral **Skipped** so it never
fails the API.

### Release Impact
See what a release changed versus the version before it.
1. Enter the **target client version** (e.g. `9.18`) → **Compare**.
2. *(optional)* enter the **commit/app version** (e.g. `19.18.0`) to also detect, from git
   history, **shared code changes** and **in-place edits to production (BAU) routes** — the
   actual `.ftl`/route diff, whitespace-ignored, attributed to the commit author.
- APIs are grouped, **BAU-route changes lead** (⚑), and each card shows risk + a **BC**
  (backward-compatibility) marker. **Summary** view is a one-line-per-API overview for
  leads; **Detailed** view shows the full diffs. Export **Summary** and **Detailed** PDFs.

---

## 5. Tips & troubleshooting

- **Stale UI / nothing updates** — the app rebuilds only when the source changed; use the
  Load/Compare button again. To quit fully, close the console window.
- **Browser didn't open** — go to **http://localhost:8080** manually. If another program
  uses port 8080, close it (the app is fixed to 8080).
- **"Could not find the application jar"** — keep the launcher next to the `app` and
  `jre`/`runtime` folders; run it from inside the unzipped folder.
- **Release Impact shows nothing under code/BAU** — check `git` is installed and on your
  PATH, and that the release's commits are tagged with the version token
  (e.g. `[jira][country][19.18.0]`).
- **Your config is safe across updates** — tokens, modules and rules live in
  `~/.traceguard`, not in the app folder, so a new version can replace the folder without
  losing your settings.
