# TraceGuard

*Scope · Test · Impact* — a standalone **route-tracing, log-verification and release-diff tool** for
enterprise frameworks built on **Spring Boot + Apache Camel 4 (XML DSL)** with a
UFW command/AOP layer. Point it at a framework **source directory** (it never
needs the framework running) and it will, for any REST API:

1. resolve the **operation name** from the controllers,
2. resolve the **route version** with fallback (`R9.4 → R9.3 → … → BASE`),
3. **traverse** the Camel routes (`direct:`/`seda:` calls, recursively),
4. collect every **backend API** invoked (`setProperty name="api"`) **and the
   backend service version** read from its request template,
5. render the whole flow as an interactive **graph**, and
6. tell you — from your **logs or a Splunk export** — which APIs were actually
   exercised for a release, whether they passed **end-to-end**, and whether each
   backend was called at the **right service version**, and
7. **compare two releases** (Release Impact) — for a target client version, what each
   API changed versus its **immediate-lower** version (added / removed / modified
   routes, backend service-version bumps, and **payload key** changes across the
   request-body templates), with optional **git-blame authorship** of who made each
   change.

Every tab exports a **shareable, sectioned PDF** (Release Scope, Release Test /
log Verification, Release Impact) so a release / dev / test team can review and
sign off from one document.

It serves **two independent applications — Mighty and SPL** — from one common entry
point: they share this tool but are traced and analysed separately. The app is a
single Spring Boot jar with a **React + Vite + TypeScript** UI built into it.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **JDK** | 21 | |
| **Maven** | 3.9+ | or IntelliJ's bundled Maven |
| **Node.js + npm** | 18+ (built/tested with **Node 24**) | the Maven build shells out to your **system** `npm` to build the frontend |
| **git** | any | **optional** — only for the Release Impact *"Changed by"* authorship; must be on the **`PATH`** of the host running the jar |

Install Node from [nodejs.org](https://nodejs.org) (or via `nvm`) and make sure
`node -v` / `npm -v` work on your `PATH`. The build does **not** download a Node —
it uses the one on your machine.

**git is optional.** The Release Impact report can attribute each changed route to
its authors via `git blame`, but only when the scanned **source directory is a git
work tree** and `git` is on the **`PATH`** of the process running the jar. If git
is absent, or the directory isn't a git checkout, that one line is simply omitted —
nothing else is affected.

## Frontend setup — `.npmrc`

If the frontend's npm dependencies come from a **private registry**, create:

```
src/main/frontend/.npmrc
```

**before** building. This file is **deliberately gitignored** because it holds a
registry **auth token** — so it is *not* committed, *not* in a cloned repo, and
*not* in a GitHub ZIP. **Each developer must create it locally.** Example
(replace with your registry and token, ideally via an env var):

```ini
registry=https://<your-registry>/repository/npm/
//<your-registry>/repository/npm/:_authToken=${NPM_TOKEN}
always-auth=true
```

> ⚠️ Never commit `.npmrc` or paste a real token into the repo. If your project
> uses the **public** npm registry, you can skip this step entirely.

Once Node is on your `PATH` (and `.npmrc` is in place if you need it), the
standard `mvn` build handles everything else.

---

## Run

Point it at your framework checkout (there is no bundled default):

```bash
TRACER_SOURCE_DIR=/path/to/mty-framework mvn spring-boot:run
```

Then open **http://localhost:8080/**.

The source directory can be set globally via `TRACER_SOURCE_DIR` /
`tracer.source-dir`, or per request via the **Source directory** field in the UI
(`sourceDir` param). A blank value yields a clear `400` explaining what to set.

Scans are **cached per source dir** and reused across requests, but each Load /
Compare / Trace first fingerprints the source tree (a cheap, parse-free stat) and
**rebuilds only when a file changed on disk** — so edits, or a fresh `git pull` into
the checkout, are picked up on the next action with **no app restart or browser
refresh**; unchanged trees keep serving from the warm cache.

### Build the jar

```bash
mvn clean package
java -jar target/traceguard-1.0.0.jar
```

* **Node/npm are a build prerequisite.** The frontend in `src/main/frontend` is
  built with your system `npm` (`npm install` + `npm run build`) via
  `exec-maven-plugin` and copied into the served static resources.
* `-Dskip.frontend=true` skips the React build for fast Java-only iterations.
* The build wipes `target/classes/static` before re-copying, so old hashed
  bundles never accumulate — a plain `mvn clean package` always serves the
  current UI. **If the UI looks stale, stop the old jar/process and run the
  freshly built one, then hard-refresh (`Ctrl+Shift+R`).**

---

## Applications (Mighty / SPL)

On load, the SPA shows an **application picker** with two cards: **Mighty** and
**SPL**. They are *separate codebases* that use the same tool — only their internal
log markers differ (`MightyMessage`/`MightyHostMessage` vs `SPLMessage`/
`SPLHostMessage`), which the UI never shows. Pick one and you work entirely within
it; the header shows the active app and a **⇄ App** button switches. The **source
directory and country are remembered per application**, so each app keeps its own
context (`tracer.<app>.*` in localStorage) — switching never leaks one app's settings
into the other.

## Source input — local path or Bitbucket branch

Every tab reads the framework from a **Source**, chosen with a **Local path /
Bitbucket branch** toggle (shared by all three tabs, remembered per application):

* **Local path** — a directory on the machine running TraceGuard (the `sourceDir`
  param, or the global `TRACER_SOURCE_DIR` / `tracer.source-dir` default).
* **Bitbucket branch** — a **repo URL + branch/tag**; the server clones/fetches it
  with **JGit** (pure Java — no `git` binary) into a per-repo/-branch cache under
  `<java.io.tmpdir>/traceguard-repos`, checks out the ref, and analyses that checkout.
  It re-**fetches** (throttled) and re-checks-out **only when the ref moved**, so an
  unchanged branch stays on the warm cache. Nothing is installed locally — it all runs
  server-side, so non-developers (release / QA) can point at a branch with no local
  checkout. Sent as `repo` + `branch` params.

  Auth for an internal Bitbucket is an **HTTP access token**, configured **inside the
  utility** (not an env var): set `bitbucket.token` in `application.yml` — JGit sends it
  as a `Bearer` header over HTTPS (`bitbucket.work-dir` optionally overrides the cache
  dir). When a checkout fails (bad URL, wrong branch, missing/expired token) the reason
  is surfaced under **Needs review** and the primary analysis still completes.

### Dependency sources

Some bootstraps `<import>` route XMLs — or dispatch (`direct:`) to host / shared
routes — that ship in a **core/shared library**, not the primary source. Add that
library as one or more **Dependency sources** (each its own Local-path / Bitbucket
toggle). They are scanned and **merged into the same index**, so those imports resolve
and the routes flow into the trace / diff exactly as if they were local. **Dependency
routes are always in scope, independent of country or version** — they're
country-agnostic shared infrastructure. The editor appears next to Source **only when a
review item is outstanding** (a clean run never shows it); add more than one with
**＋ Add dependency**. Sent as repeatable `dep` params (`local:<path>` or
`bit:<repo>|<branch>`).

### Needs review

Anything the analysis couldn't resolve is highlighted in a distinct **Needs review**
box (separate from the general warning banner) and echoed as its own section in every
PDF report, so a reader always knows the analysis may be incomplete. It covers an
unresolved **`<import>` / `<routeContextRef>`**, a **route not found** mid-flow — named,
e.g. `Route not found in source: R9.14_acceptcoreinfo` — a **failed dependency load**,
and a dynamic target with **no readable base**. Warnings are never silently dropped: you
clear them by adding the dependency that provides the missing routes (or fixing the
reference) and re-running.

## UI

The workspace has three tabs — **Release Scope** (trace the impacted APIs),
**Release Test** (verify them against logs), **Release Impact** (what a release
changed) — and a light/dark theme toggle.

### Release Scope

* **Catalog by default** — leave the inputs empty to see every discovered API
  grouped by the client release its route resolves to.
* **Version scoping** — entering a **client release version** (e.g. `9.4`) shows
  only the APIs that release actually impacts (others, which resolve to a lower
  version or BASE, are excluded with a notice). Blank ⇒ whole catalog.
* **`N/A` = latest, else base** — enter `N/A` (or `latest`) as the version to resolve
  **each API to its highest available `R<version>_` route**, falling back to the **base
  route** (no `R<version>_` prefix) when it has none. This is how you view an
  **unversioned repo** — one whose routes never carry an `R<version>_` prefix, so every
  API always uses its base route: `N/A` simply never finds a versioned route and lands on
  base, on all three tabs. In Release Impact, `N/A` diffs each API's newest release against
  its predecessor; a base-only API is shown as an informational *"not versioned — no prior
  version to compare"* row rather than a misleading change.
* **Graph** — React Flow with a layout switcher, minimap, zoom, role-coloured
  nodes, an entry-route ring, **all API entry nodes aligned in one column/row**,
  selected-path highlighting (BFS up + downstream), per-call host instances, node
  search, **Fit**, **PNG** export, and a **PDF** report. Each backend node shows a
  **`svc vX.Y`** chip — its traced service version (or `svc v2.2 / 3.3` when the same
  backend is called at several versions).
* **PDF report** — exports the whole release's **impacted-API catalog**: every API
  grouped by the version it resolves to, each with its resolved route, flow and
  backends (svc versions where known). Always the full catalog — even when a single
  API is on screen — so the report can't under-report a release.

### Release Test

* **APIs to analyse** — a multiselect of every API in the release (single,
  several, or all). This selection drives the Splunk query **and** scopes the log
  analysis.
* **What changed?** *(optional)* — tick changed **routes** / **backends** to find
  the **impacted APIs**; **+ select for analysis** feeds them into the selection
  above. Shared host/terminal routes (e.g. `callUFWDGE`, reused by every API) are
  excluded from the change pickers so they don't falsely impact everything. The
  impacted list — your direct selection vs the **blast radius** (APIs that share a
  changed route/backend) — exports as a **PDF Impact report** (`⤓ Export PDF`).
* **Splunk query — selected APIs** — one combined search over the selected
  front-end paths **and their backends**, with a **time-range** picker
  (`15m / 1h / 4h / 24h / 7d / 30d`, max 30 days). Each backend is paired with its
  traced **service version** (`(uri="…" serviceVersionNumber="2.2")`) so the search
  targets the right version, and a **path-suffix wildcard** (`uri="*…"`) tolerates a
  deployment context prefix. It is **scoped to the selected client version** (a
  bracket field in every log line) so only that release's lines are fetched — far
  fewer events, and no other-version noise. It returns the raw events as a single
  `_raw` column (`| sort 0 _time | table _raw`), so exporting it as CSV yields the
  raw log lines — identical to a raw output log, which uploads straight back for
  correlation. Configurable `index`, front-end / backend / service-version field
  names; copy or download as `.spl`.
* **Verify with logs** — pick **Output log** or **Splunk report**, then upload a raw
  output log or a Splunk export; the shape is **auto-detected from the file content**
  (`.log/.txt/.csv/.json/.gz` accepted under either mode — a `_raw`-only export saved
  as `.txt` verifies exactly like an output log, so both modes give the same result).
  The report,
  **log-type aware** (front-end APIs from front-end log lines, backends from backend
  log lines, both when both are selected), shows a **status donut**, clickable
  **filter chips** (All / Issues / per-status), a **worst-first sort**, per-API
  end-to-end verdict, FE latency, attempts (`n✓/n✗`), an expandable backend
  breakdown, and a **PDF Verification report** (`⤓ Export PDF`). Every backend row
  carries a **service-version chip**: `svc 2.2 ✓` when the logged version matches the
  traced one, `svc 9.9 ✗ (exp 2.2)` on a mismatch (which flags the row even if the
  backend itself succeeded).

### Release Impact

Compare a release against the one before it. Enter a **target client version**
(e.g. `9.18`) and **Compare**: for every API the release touched, TraceGuard traces
the **whole resolved flow** at the target and again at its **immediate-lower**
version (the highest versioned route below the target, else BASE), then structurally
diffs them.

* **Left-hand group nav** — APIs are grouped into **Changed**, **New**, **Unchanged**
  (each with a count and a status colour); pick one to view that group. *Changed* is
  preselected. **Changed** = the resolved Camel flow differs; **New** = first appears
  this release (no earlier version); **Unchanged** = a version bump with no
  behavioural change, *or* an API the release didn't touch (no target-version route,
  so it still resolves to its lower/BAU route — shown with a note).
* **Per-API card** — a `lower → target` version pill, a plain verdict, *change chips*
  (edited / added / removed routes), any **backend service-version bump**
  (`2.2 → 2.3`, read from the request template — caught even when the route XML is
  otherwise identical), a **payload change** row, and a collapsible **element-level
  diff** (monospace, `-` removed / `+` added) per route. A **View flow** opens the
  route graph; **Copy** copies the API's diff.
* **Payload change** — the JSON **keys added / removed** across the request-body
  templates (`.ftl`/`.vm`) the two flows use. It is **key-based and engine-agnostic**:
  a `.vm → .ftl` migration with the same keys is *not* a change; `serviceVersionNumber`
  is excluded (it's the service-version bump above); a key name that appears under more
  than one object is qualified `Object.key`. A payload key change flips the API to
  **Changed**. Renders below "What changed" in both the card and the PDF.
* **"Changed by" / "Added by"** *(when the source dir is a git work tree)* — each
  changed route lists who authored its lines in the **latest** version (`git blame` of
  the target route; the lower/BAU version's authors are intentionally excluded), and a
  **New** API lists who added its routes. Omitted entirely when git isn't available.
* **Export PDF** — a sectioned **Release Impact report** (Changed + New), independent
  of which group is on screen.

Version comparison handles **multi-part (patch) versions** — `9.18.1 > 9.18`,
`9.18.1 > 9.18.0`, `9.18 == 9.18.0` — and **un-versioned (BASE) routes**, which act
as both the base and the latest when no `R<ver>_` route exists.

### Exports

| Tab | Export | Format |
|---|---|---|
| Release Scope | the route graph (**PNG**) + the impacted-API catalog (**PDF**) | **PNG** / **PDF** |
| Release Test | Release Test report (selected + blast radius) | **PDF** |
| Release Test › *Verify with logs* | Verification / sign-off report (worst-first by status) | **PDF** |
| Release Test › *Splunk query* | the generated query | `.spl` |
| Release Impact | Release Impact report (Changed + New, sectioned) | **PDF** |

The four PDF reports share one design — a cover header, an executive **stat band**,
a *"How to read this report"* legend, colour-coded **sections**, and page-numbered
footers (`pdfReport.ts`). jsPDF is **lazy-loaded** (its own chunk) so it doesn't
weigh down the initial page; built-in fonts are Latin-1, so the UI's unicode glyphs
are mapped to ASCII in the PDF.

---

## HTTP API

| Endpoint | Method | Purpose |
|---|---|---|
| `/internal/route-graph` | GET / POST | Single trace (`api`) or catalog (no `api`), grouped by version |
| `/internal/impact-index` | GET | Per-API footprint (routes / backends / hosts) for a release |
| `/internal/version-diff` | GET | **Release Impact** — per-API changes of a target version vs its immediate-lower version |
| `/internal/log-analysis` | POST (multipart) | Correlate an uploaded log / Splunk export against the traced APIs |
| `/internal/meta` | GET | Discovered countries, versions and transferType values |
| `/internal/countries` | GET | Bootstrap (country) scopes |

Common params (query or, for `log-analysis`, multipart form fields): `version`,
`country`, `sourceDir`, plus `transferType` (route-graph). Source can also be a
**Bitbucket branch** via `repo` + `branch` (the server clones/fetches it), and one or
more **dependency sources** via repeatable `dep` (`local:<path>` or `bit:<repo>|<branch>`)
— both accepted on every endpoint. `log-analysis` also takes `apis` / `backends`
(repeatable — the front-end APIs and/or backends to report), `all` (analyse the whole
release), and `app` (`Mighty` / `SPL` — selects which log markers to parse). The impact
index exposes a `backendVersions` map (backend URL → traced service version) per API.
Every response carries a `needsReview` list (unresolved imports / routes / dependencies).

`route-graph` carries a `mode` discriminator:

* **`api` + `version`** → single trace of that exact resolution.
* **`api` only** → single trace (blank version ⇒ BASE).
* **no `api`** → **catalog** (`mode: "catalog"`): every API grouped by the release
  its route resolves to (`9.4`, `9.3`, …, `BASE`, and a `(no route found)`
  bucket). With a `version`, only that release's impacted APIs are shown.

`version-diff` (`sourceDir`, `country`, `version`) returns a `VersionDiffReport`
(`mode: "version-diff"`): counts and an `apis` list of `ApiDiff` — each with a
`status` (`CHANGED` / `NEW` / `UNCHANGED`), the target & lower route/version,
per-route `routeDiffs` (`added`/`removed` canonical lines + `changedBy` git
authors), whole `addedRoutes`/`removedRoutes`, `backendVersionChanges`, a
`payloadChange` (`addedKeys`/`removedKeys`), and `authors` (who added a NEW API).

---

## Log / Splunk correlation

The same engine reads three input shapes; a Splunk export simply carries the
original event in its `_raw` field, so all three feed one line parser. The shape
is auto-detected and reported as `uploadType`:

* **`RAW_LOG`** — the raw output log.
* **`SPLUNK_CSV`** — header located, `_raw` column extracted per record. Streamed one
  record at a time (bounded memory for large 30-day / 200MB+ exports); a multi-line
  `_raw` event and a leading UTF-8 BOM are handled, and the header / any non-event row
  is skipped generically.
* **`SPLUNK_JSON`** — array / `{results:[]}` / NDJSON; `_raw` per event.

### Log line format

```
2026-06-11 18.43.45.102 [thread] INFO [MARKER][app][session][user][9.14][corrId][platform][500ms]-/.../services/sg/<api> -Response - {json}
```

* **MARKER** — `[<App>Message]` = front-end (controller), `[<App>HostMessage]` =
  backend, where `<App>` is the selected application (`Mighty` / `SPL`); lines from
  the other application are ignored.
* **Bracket fields are located by pattern, not fixed position** (environments vary in
  how many fields precede them): the **client version** is the first `9.18`-shaped
  field, the **correlation id** is the field right after it, the **time taken** is
  the `500ms`-shaped field (FE Response = total time, backend Response = that
  backend's time; empty on Request lines).
* **Direction** — front-end uses `-Request -` / `-Response -`; backend uses
  `-[Request]` / `-[Response]`.
* **Path** — front-end matched by suffix to the controller path; backend matched by
  its path tail. Both tolerate a deployment **context prefix** (e.g.
  `/mty-banking-01/services/sg/…`).
* **Success** — `responseCode` is all zeros (any length: `00000` … `0000000`).
* **Backend service version** — the host-message JSON payload carries
  `"serviceVersionNumber":"2.0"`, compared against the version the tracer expects for
  that backend (see *Backend service version* below).

A **transaction** is every line sharing one **correlation id**, printed
front-end-Request → backend-Request → backend-Response → front-end-Response. When
an API is exercised many times, the **latest by timestamp** drives its verdict and
all attempts are counted.

### End-to-end verdicts

| Status | Meaning |
|---|---|
| **Success** | Front-end response all-zeros and every observed backend all-zeros |
| **Failed** | Front-end response present but its code is not all-zeros |
| **Timeout** | Front-end request seen but no response — timeout / server down |
| **Partial** | Front-end OK but an *observed* backend failed **or was called at the wrong service version** |
| **Indeterminate** | A response was logged but no parseable code |
| **Not tested** | No entry for this API at the requested client release |

A traced backend that never appears in a transaction is treated as a **not-taken
choice branch** (informational), not a failure. When an API is *not tested*, the note
explains why — no log line matched the path, or lines matched but none at the
requested release (listing the versions actually seen).

---

## Backend service version

Before a backend call, a route evaluates a request-body **template** —
`<to uri="freemarker:META-INF/templates/…/precapture.ftl"/>` (or `velocity:`/`.vm`,
etc.). That template's JSON `ServiceContext` carries `"serviceVersionNumber":"2.2"` —
the version of the backend to call. The tracer:

1. **Captures it during tracing** — it resolves the template file under the source
   root (suffix match, tolerating a nested `classpath:`/`file:` prefix), reads the
   version, and attaches it to the backend on the graph and in
   `ApiImpact.backendVersions`. The pairing is one consistent rule (works for the
   template before/after the api, inside/outside `<choice>` branches, per-branch or
   shared) and a reused backend URL keeps **all** its versions (`2.2 / 3.3`).
2. **Filters the Splunk query** by it — `(uri="…" serviceVersionNumber="2.2")` — so
   the search targets that api's version and ignores others.
3. **Checks it in the log** — the same `serviceVersionNumber` appears in the
   host-message payload; if the logged version differs from the expected one, the
   backend row is flagged (`svc 9.9 ✗ (exp 2.2)`) and the verdict drops to *Partial*.

Recognises the common template schemes (`freemarker`/`velocity`/`mvel`/`mustache`/
`thymeleaf`/`framework`/…) and extensions (`.ftl`/`.vm`/`.vtl`/…), and reads
`serviceVersionNumber` with single or double quotes.

---

## How it works

```
source dir ──► scan ──► OperationResolver (JavaParser over controllers)
                  └────► RouteRegistry  (route XML, hybrid loader)
                              │
   api + version ──► VersionResolver ──► entry route name
                              │
                       RouteTraverser ──► RouteGraph (+ flow + backends)
                              │
   target version ──► versionDiff: trace(target) vs trace(immediate-lower)
                          └─ RouteXmlDiff (raw-XML diff) + GitBlameService ──► VersionDiffReport
                              │
   uploaded log / Splunk ──► LogAnalysisService ──► per-API end-to-end report
```

### Operation resolution
Controllers are parsed with **JavaParser**; an API path maps to the handler
**method name**, which is the operation name the framework routes on. Source is
parsed (not reflection) because the tracer analyses a directory, not a running
classpath. Because the framework is now **UFW**, only endpoints carrying
**`@CommandHandler`** are considered — the legacy / JAX-RS controllers a migrated API
was converted from (no `@CommandHandler`) are ignored, so the same API never shows
twice. A source tree with **no** `@CommandHandler` anywhere (a pre-UFW codebase) keeps
every endpoint, so the tool still works there.

**Command-dispatch flavour.** Some frameworks intercept every UFW call through a fixed
`redirectRoute` that dispatches **by command** —
`<toD uri="direct:send${header.operationName}Route"/>` — to a route named
`send<command>Route`. There the operation is identified by the `@CommandHandler`
**command** (e.g. `command="ValidateNotificationCommand"`), not the method name, so the
entry route is `sendValidateNotificationCommandRoute`. The tracer auto-detects this: when
an operation carries a command **and** `send<command>Route` exists in the scoped source, it
resolves there; otherwise it falls back to the method-name rule above. Because it fires only
when `send<command>Route` actually exists, repos without that convention are unaffected.

### Version resolution
`R<version>_<operation>` exact match → otherwise the **highest available lower**
minor `≤` the requested version (`9.4 → 9.3 → 9.2 …`) → otherwise the **BASE**
route (`<operation>`). A blank version goes straight to BASE. Versions are compared
**component-by-component**, so multi-part / patch versions sort correctly
(`9.18.1 > 9.18 > 9.14`, `9.18 == 9.18.0`). An **un-versioned route** (`getFundTransfer`,
no `R<ver>_`) is both the base and — when it's the only route — the latest, so every
client resolves to it. The same machinery powers Release Impact's **immediate-lower**
pick (highest versioned route strictly below the target, else BASE).

### Dynamic route derivation
Besides the `direct:${exchangeProperty[operationName]}` redirect, a route may dispatch
to a route whose name a bean builds from **client version + a base name** — e.g.
`<setProperty name="DEST_ROUTE"><constant>acceptcoreinfo</constant></setProperty>` then
`<toD uri="direct:${exchangeProperty[FINAL_ROUTE_NAME]}"/>`, where `FINAL_ROUTE_NAME`
= `R<version>_<DEST_ROUTE>` (a de-duplication pattern). The traverser reproduces this
statically: it tracks the most recent route-resolving `<constant>` in scope (property
names vary by module, so it keys on *"a constant that resolves to a route"*, not a fixed
name) and resolves it through the **same VersionResolver** used for entry routes. The
version is the **requested client version** when given, else the **calling route's own**
`R<ver>_` version — so it resolves with no user input, on every tab. Each `<when>` that
sets its own base **fans out** to its own resolved route. If the derived route isn't in
scope it's flagged **by name** (`Route not found: R<version>_<base>`) for review, not a
generic message; only a base set entirely by a bean (no XML `<constant>` to read) stays a
plain *"unresolved dynamic target"*. It's a purely additive flavour — active only when a
route contains `direct:${exchangeProperty[FINAL_ROUTE_NAME]}`.

### Release impact
For each API whose entry resolves to exactly the target version, the whole flow is
traced at the **target** and at the **immediate-lower** version; the two flows'
routes are paired by **base name** (strip `R<ver>_`). Each route's **raw XML** is
canonicalised (normalised whitespace, sorted attributes, one element per line) and
the pair is **LCS-diffed** — version-bearing tokens (`direct:R9.18_x`) are collapsed
to `R{v}_` so a pure re-stamp isn't reported as a change. Backend **service-version
bumps** (which live in the request template, not the route XML) are caught by
comparing the two flows' traced backend versions, and **payload key** changes by
diffing the JSON keys of the two flows' request-body templates (engine-agnostic,
`serviceVersionNumber` excluded). When the source dir is a git work tree, each changed
route's target-version lines are `git blame`d for the **authors**.
Starts at the resolved entry route and follows `direct:` calls recursively
(loop-guarded). It resolves the dynamic redirect
`direct:${exchangeProperty[operationName]}`, honours `<choice>` branches
(filtered by `transferType`, or all branches when omitted), follows async
`seda:`/`vm:` calls, records external `CamelHttpUri` host calls as per-call
instances, and collects backend endpoints from `setProperty name="api"`. A
framework template `<to>` (`.ftl`/`.vm`) near a backend contributes its **service
version** to that backend.

### Hybrid route loader
Each route XML is loaded the **Camel 4 way** — via `RoutesLoader` into a
`CamelContext`, then walked over the real
`RouteDefinition`/`ProcessorDefinition` model. The context is never started.
Camel's `xml-io-dsl` only recognises a top-level `<routes>`/`<route>`; real
frameworks wrap routes in Spring `<beans>` → `<camelContext>` / `<routeContext>`,
so the loader tries the file as-is, then **unwraps** the `<route>` elements into a
synthetic Camel-namespaced document, and finally **falls back** to a DOM parser
producing the same neutral model. Both feed one traverser.

> The in-memory resource is named `inline-route.xml` because Camel derives a
> loader from the extension after the *first* dot — `R9.4_x.xml` would otherwise
> be misread as extension `4_x.xml`.

### Country scoping
A code base often has one bootstrap per country (`SG.xml`, `MY.xml`, …), each a
`<camelContext>` that pulls in route files via `<import>` / `<routeContextRef>`.
Selecting a `country` restricts analysis to that bootstrap's **assembly closure**:
starting from `<country>.xml`, the tracer follows its imports and context refs
transitively and loads only those routes. With no `country`, every route in the
tree is considered. Routes from **Dependency sources** are always included regardless
of the country closure — they're shared/core infrastructure, not country-specific.

**Bootstrap discovery has two ways** (filename first, YAML as a fallback — never both):

1. **Filename** — a `<country>.xml` file (`SG.xml`, `MY.xml`) containing a `<camelContext>`.
2. **`application.yml` `routes-include-pattern`** — when no filename bootstrap exists, the
   countries and their route files come from the camel `routes-include-pattern` (under
   `camel: main:`). The config is the **source of truth** for what loads; the tracer resolves
   the pattern to files and loads them, using `<country>` only to fill in dynamic filenames:
   * **`${country}` placeholder** — `classpath:routes-${country}.xml` (or `routes/${country}/*.xml`):
     each file the placeholder matches becomes that country's scope (`routes-MY.xml` → `MY`).
   * **`application-<country>.yml` profiles** — the profile name is the country; its own
     `routes-include-pattern` lists that country's files.
   * **Country-less entries** — a shared `routes.xml`, a glob (`routes/*.xml`), or a literal
     list are **always loaded for every country** (a shared file listed alongside a
     `${country}` placeholder loads too). Route XMLs may use Camel's native `<routes>` DSL,
     Spring `<camelContext>`, or `<routeContext>` — all are parsed.

  When the config carries no country dimension (a plain glob or literal list), every listed
  file loads and the **controller-country** rule (`@RequestMapping("/services/<country>")` or a
  `.<country>` package) scopes which APIs are *shown* per country.

---

## Layout

```
src/main/java/com/arjun/tracer/
  api/        DTOs — TraceRequest/Response, GraphNode/Edge, RouteGraph,
              ImpactIndex/ApiImpact (+ backendVersions), LogAnalysisReport/
              ApiLogResult/BackendCallResult/BackendLogResult, LogStatus,
              VersionDiffReport/ApiDiff/RouteStepDiff/BackendVersionChange (Release Impact)
  model/      neutral route model shared by both loaders
  loader/     CamelRouteModelLoader, XmlDomRouteModelLoader, RouteRegistry
  resolve/    OperationResolver (JavaParser), VersionResolver (+ immediateLowerVersion)
  trace/      RouteTraverser (+ template service-version capture)
  service/    RouteTraceService (trace/catalog/impact/versionDiff/template resolution),
              LogAnalysisService (app-aware parse, correlation, version match),
              RouteXmlDiff (raw-XML canonicalise + LCS diff + route line ranges),
              GitBlameService (changed-route authorship)
  web/        RouteGraphController, WebConfig
src/main/frontend/                React + Vite + TypeScript SPA (built into the jar)
  src/App.tsx       application picker (Mighty/SPL) + tabbed shell
  src/views/        TraceView, ImpactView, ReleaseDiffView   (per-app context: tracer.<app>.*)
  src/components/    AppPicker, RouteGraph, SplunkPanel, LogAnalysisPanel, ApiFlowModal, …
  src/spl.ts         SPL generation (events query, time presets, service-version + client-version filter)
  src/pdfReport.ts   shared PDF kit (header, stat band, sections, legend, footers)
  src/apiTracePdf.ts / impactPdf.ts / logPdf.ts / diffPdf.ts   the four PDF reports (jsPDF, lazy-loaded)
src/test/resources/sample-framework/    synthetic framework fixture (test-only)
src/test/resources/svc-diff-framework/  Release-Diff fixture: svc bump + base-only (test-only)
src/test/resources/sample-logs/          synthetic logs + Splunk exports (test-only)
```

The old plain-HTML + Cytoscape UI (`static/route-tracer.html`) was replaced by the
React SPA in **v2.0**; the project is single-branch (`main`) with version tags.

---

## Test

```bash
mvn test          # or:  mvn -Dskip.frontend=true test   (Java only)
```

* `RouteTraceServiceTest` / `ImpactIndexTest` — the trace pipeline and impact
  index against the synthetic framework fixture (exact/fallback/BASE version
  resolution, `transferType` filtering, `<otherwise>` branches, cross-version
  delegation, shared-host-route exclusion, **release scoping**, and **backend
  service-version capture** across every template/route shape incl. reused URLs).
* `LogAnalysisServiceTest` — end-to-end verdicts (success/latest-wins, not-tested,
  timeout, partial) over synthetic output logs **and** Splunk CSV/JSON exports,
  plus **pattern-based field detection**, **backend-only analysis**,
  **service-version match/mismatch**, the **SPL vs Mighty** marker switch, and the
  Splunk-CSV robustness set: **multi-line `_raw`**, **single `_raw` column**,
  **raw-log ↔ `_raw`-CSV parity** (Mighty and SPL), and **header / junk-row skipping**.
* `VersionDiffTest` — Release Impact: flow-vs-immediate-lower comparison, NEW / CHANGED
  / UNCHANGED, fallback-to-lower as unchanged, and **backend service-version bumps**
  (`.ftl` → `.vm`) surfaced even when the route XML matches.
* `PayloadKeysTest` — the Release Impact **payload key** diff: engine-agnostic
  (`.vm`/`.ftl` same keys ⇒ no change), added/removed keys, `serviceVersionNumber`
  excluded, same-name-under-different-objects qualified `Object.key`, nested keys.
* `StaleSourceInvalidationTest` — editing a template between two compares is picked
  up on the next run (source-fingerprint cache invalidation), no restart.
* `VersionResolverTest` — version ordering: multi-part / patch (`9.18.1 > 9.18`,
  `9.18 == 9.18.0`) and **un-versioned (BASE) routes** as both base and latest.
* `OperationResolverTest` — de-duplicates the interface/impl `@RequestMapping` split.

All fixtures under `src/test/resources` are test-only — not part of the app or jar.
