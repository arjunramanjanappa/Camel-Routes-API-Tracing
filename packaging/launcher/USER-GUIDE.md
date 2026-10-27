# TraceGuard — User Guide

A desktop tool for **release scope, testing and impact analysis** of the Camel routing
framework. Everything runs on your own machine; nothing is installed and no admin rights
are needed.

---

## 1. Start it

- **Windows:** double-click **`TraceGuard.exe`** (or `TraceGuard.bat`).
- **macOS:** double-click **`TraceGuard.command`**.

A console window opens, then your browser opens at **http://localhost:8080**.
**Keep the console open while you work — closing it stops the app.**

> First launch may show *"Windows protected your PC" (SmartScreen)* → **More info ▸ Run
> anyway** (this is an unsigned internal tool). On **macOS** the first launch is blocked by
> Gatekeeper → open **System Settings ▸ Privacy & Security** and click **Open Anyway**
> (older macOS: right-click ▸ **Open**); it's a one-time step. If the browser doesn't open
> on its own, just go to **http://localhost:8080** yourself.

---

## 2. One-time set-up

**Tokens (per user).** Click **⚙ Config** in the header and enter:

- **Bitbucket token** — only if you'll point at a **Bitbucket branch** (not needed for a
  local folder). Takes effect immediately.
- **Splunk base URL** — only for **Release Test**'s result-download links (the address of
  your Splunk Web up to `/services/search/jobs/`). Not a secret, just a URL.

Your **module lists**, **host rules** and **business-area labels** usually come
**pre-loaded** with the app — you only add your own tokens. Everything you set is saved
under your user profile (`~/.traceguard`) and remembered on every run.

**Business-area labels** (⚙ Config) are the names the Release Impact reports use for API
areas and sub-features — e.g. `payment = Funds Transfer & Payments`,
`payment/fundtransfer/quick = Quick Transfer`. One `path-key = Label` per line; a path with
no label shows its own segment.

**Ignored classes** (⚙ Config) are shared Java classes Release Impact must not count as a code
change — a common logger / audit / utility class used by many routes would otherwise mark every API
as impacted. Comma-separated or one per line: a bean name, a class name with wildcards (`*Logger*`) or a path
(`**/common/*.java`). Skipped changes are still listed on the report.

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
- **Summary** view (for managers) and the **Summary PDF** read the same way: the four
  counts (BAU regression · Backward compatibility · New app only · Shared code), then each
  **business area** ("Funds Transfer & Payments"), highest impact first, with one row per API:
  **API · Which App · Change · Jira Details · Commit Message**. Which App is the app to
  test (BAU app, New app, Both apps). Jira Details is the ticket and the developers from the
  release commits, one line per ticket (`SGMB-4588 · Swapna, Deepak`); Commit Message is the
  message after the bracket tokens. A commit without a ticket token shows as `no Jira`.
  Each area has a "Changed by" line, and a **Jira in this release** table closes the report.
  The Jira columns appear only when a commit/app version was entered.
- **Detailed** view (for developers and testers) shows the per-API cards with the full
  route, class and payload diffs, risk and **BC** markers, and the same Jira line under each
  API. **Detailed PDF** — every change in full, grouped by version and area.
- Files are named `TraceGuard-Release-<version>-Impact-Summary.pdf` /
  `…-Impact-Detailed.pdf` (Scope and Test reports follow the same pattern).

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
- **Release Impact Compare is slow** — the console prints one line per Compare
  (`Release Impact compare … git N calls … blame N calls …`) showing where the time went.
  Git answers for the release's commits are remembered while the app runs, so a second
  Compare of the same release (after editing the ignore list, say) is much faster; any
  single git call over 5 s is also logged. Send that line along when reporting a slow run.
- **Your config is safe across updates** — tokens, modules and rules live in
  `~/.traceguard`, not in the app folder, so a new version can replace the folder without
  losing your settings.
