# Camel Routes API Tracing

A standalone **route-tracing, impact-analysis and log-correlation tool** for
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
   backend was called at the **right service version**.

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

Install Node from [nodejs.org](https://nodejs.org) (or via `nvm`) and make sure
`node -v` / `npm -v` work on your `PATH`. The build does **not** download a Node —
it uses the one on your machine.

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

### Build the jar

```bash
mvn clean package
java -jar target/camel-route-tracer-1.0.0.jar
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
directory, country and client version are remembered per application**, so each app
keeps its own context (`tracer.<app>.*` in localStorage) — switching never leaks one
app's settings into the other.

## UI

The workspace has two tabs (Trace / Impact analysis) and a light/dark theme toggle.

### Trace

* **Catalog by default** — leave the inputs empty to see every discovered API
  grouped by the client release its route resolves to.
* **Version scoping** — entering a **client release version** (e.g. `9.4`) shows
  only the APIs that release actually impacts (others, which resolve to a lower
  version or BASE, are excluded with a notice). Blank ⇒ whole catalog.
* **Graph** — React Flow with a layout switcher, minimap, zoom, role-coloured
  nodes, an entry-route ring, selected-path highlighting (BFS up + downstream),
  per-call host instances, node search, **Fit**, and **PNG/JSON** export. Each
  backend node shows a **`svc vX.Y`** chip — its traced service version (or
  `svc v2.2 / 3.3` when the same backend is called at several versions).

### Impact analysis

* **APIs to analyse** — a multiselect of every API in the release (single,
  several, or all). This selection drives the Splunk query **and** scopes the log
  analysis.
* **What changed?** *(optional)* — tick changed **routes** / **backends** to find
  the **impacted APIs**; **+ select for analysis** feeds them into the selection
  above. Shared host/terminal routes (e.g. `callUFWDGE`, reused by every API) are
  excluded from the change pickers so they don't falsely impact everything.
* **Splunk query — selected APIs** — one combined search over the selected
  front-end paths **and their backends**, with a **time-range** picker
  (`15m / 1h / 4h / 24h / 7d / 30d`, max 30 days). Each backend is paired with its
  traced **service version** (`(uri="…" serviceVersionNumber="2.2")`) so the search
  targets the right version, and a **path-suffix wildcard** (`uri="*…"`) tolerates a
  deployment context prefix. It returns **raw events** (`| table _time, _raw`) so
  the exported report uploads straight back for correlation. Configurable `index`,
  front-end / backend / service-version field names; copy or download as `.spl`.
* **Verify with logs** — pick **Output log** or **Splunk report**, then upload a raw
  output log or a Splunk export (CSV/JSON); the shape is auto-detected. The report,
  **log-type aware** (front-end APIs from front-end log lines, backends from backend
  log lines, both when both are selected), shows a **status donut**, clickable
  **filter chips** (All / Issues / per-status), a **worst-first sort**, per-API
  end-to-end verdict, FE latency, attempts (`n✓/n✗`), an expandable backend
  breakdown, and **CSV export**. Every backend row carries a **service-version
  chip**: `svc 2.2 ✓` when the logged version matches the traced one, `svc 9.9 ✗
  (exp 2.2)` on a mismatch (which flags the row even if the backend itself
  succeeded).

---

## HTTP API

| Endpoint | Method | Purpose |
|---|---|---|
| `/internal/route-graph` | GET / POST | Single trace (`api`) or catalog (no `api`), grouped by version |
| `/internal/impact-index` | GET | Per-API footprint (routes / backends / hosts) for a release |
| `/internal/log-analysis` | POST (multipart) | Correlate an uploaded log / Splunk export against the traced APIs |
| `/internal/meta` | GET | Discovered countries, versions and transferType values |
| `/internal/countries` | GET | Bootstrap (country) scopes |

Common params (query or, for `log-analysis`, multipart form fields): `version`,
`country`, `sourceDir`, plus `transferType` (route-graph). `log-analysis` also takes
`apis` / `backends` (repeatable — the front-end APIs and/or backends to report),
`all` (analyse the whole release), and `app` (`Mighty` / `SPL` — selects which log
markers to parse). The impact index exposes a `backendVersions` map (backend URL →
traced service version) per API.

`route-graph` carries a `mode` discriminator:

* **`api` + `version`** → single trace of that exact resolution.
* **`api` only** → single trace (blank version ⇒ BASE).
* **no `api`** → **catalog** (`mode: "catalog"`): every API grouped by the release
  its route resolves to (`9.4`, `9.3`, …, `BASE`, and a `(no route found)`
  bucket). With a `version`, only that release's impacted APIs are shown.

---

## Log / Splunk correlation

The same engine reads three input shapes; a Splunk export simply carries the
original event in its `_raw` field, so all three feed one line parser. The shape
is auto-detected and reported as `uploadType`:

* **`RAW_LOG`** — the raw output log.
* **`SPLUNK_CSV`** — header located, `_raw` column extracted per record.
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
   uploaded log / Splunk ──► LogAnalysisService ──► per-API end-to-end report
```

### Operation resolution
Controllers are parsed with **JavaParser**; an API path maps to the handler
**method name**, which is the operation name the framework routes on. Source is
parsed (not reflection) because the tracer analyses a directory, not a running
classpath.

### Version resolution
`R<version>_<operation>` exact match → otherwise the **highest available lower**
minor `≤` the requested version (`9.4 → 9.3 → 9.2 …`) → otherwise the **BASE**
route (`<operation>`). A blank version goes straight to BASE.

### Route traversal
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
tree is considered.

---

## Layout

```
src/main/java/com/arjun/tracer/
  api/        DTOs — TraceRequest/Response, GraphNode/Edge, RouteGraph,
              ImpactIndex/ApiImpact (+ backendVersions), LogAnalysisReport/
              ApiLogResult/BackendCallResult/BackendLogResult, LogStatus
  model/      neutral route model shared by both loaders
  loader/     CamelRouteModelLoader, XmlDomRouteModelLoader, RouteRegistry
  resolve/    OperationResolver (JavaParser), VersionResolver
  trace/      RouteTraverser (+ template service-version capture)
  service/    RouteTraceService (trace/catalog/impact/template resolution),
              LogAnalysisService (app-aware parse, correlation, version match)
  web/        RouteGraphController, WebConfig
src/main/frontend/                React + Vite + TypeScript SPA (built into the jar)
  src/App.tsx     application picker (Mighty/SPL) + tabbed shell
  src/views/      TraceView, ImpactView   (per-app context: tracer.<app>.*)
  src/components/  AppPicker, RouteGraph, ControlPanel, SplunkPanel, LogAnalysisPanel, …
  src/spl.ts       SPL generation (events query, time presets, service-version filter)
src/test/resources/sample-framework/   synthetic framework fixture (test-only)
src/test/resources/sample-logs/         synthetic logs + Splunk exports (test-only)
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
  **service-version match/mismatch**, and the **SPL vs Mighty** marker switch.

All fixtures under `src/test/resources` are test-only — not part of the app or jar.
