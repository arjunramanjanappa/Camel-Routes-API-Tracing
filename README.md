# Camel Routes API Tracing

A standalone **route-tracing, impact-analysis and log-correlation tool** for
enterprise frameworks built on **Spring Boot + Apache Camel 4 (XML DSL)** with a
UFW command/AOP layer. Point it at a framework **source directory** (it never
needs the framework running) and it will, for any REST API:

1. resolve the **operation name** from the controllers,
2. resolve the **route version** with fallback (`R9.4 → R9.3 → … → BASE`),
3. **traverse** the Camel routes (`direct:`/`seda:` calls, recursively),
4. collect every **backend API** invoked (`setProperty name="api"`),
5. render the whole flow as an interactive **graph**, and
6. tell you — from your **logs or a Splunk export** — which APIs were actually
   exercised for a release and whether they passed **end-to-end**.

The app is a single Spring Boot jar with a **React + Vite + TypeScript** UI built
into it.

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

## UI

The SPA at **`/`** has two tabs and a light/dark theme toggle. Nothing
trace-specific is pre-filled — only `country` is remembered across loads.

### Trace

* **Catalog by default** — leave the inputs empty to see every discovered API
  grouped by the client release its route resolves to.
* **Version scoping** — entering a **client release version** (e.g. `9.4`) shows
  only the APIs that release actually impacts (others, which resolve to a lower
  version or BASE, are excluded with a notice). Blank ⇒ whole catalog.
* **Graph** — React Flow with a layout switcher, minimap, zoom, role-coloured
  nodes, an entry-route ring, selected-path highlighting (BFS up + downstream),
  per-call host instances, node search, **Fit**, and **PNG/JSON** export.

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
  (`15m / 1h / 4h / 24h / 7d / 30d`, max 30 days). It returns **raw events**
  (`| table _time, _raw`) so the exported report can be uploaded straight back
  for correlation. Configurable `index` and front-end/backend field names; copy
  or download as `.spl`.
* **Verify with logs** — upload a raw **output log** or a **Splunk export**
  (CSV/JSON); the shape is auto-detected. The report shows a **status donut**,
  clickable **filter chips** (All / Issues / per-status), a **worst-first sort**,
  per-API end-to-end verdict, FE latency, attempts (`n✓/n✗`), an expandable
  backend breakdown, and **CSV export**.

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
`country`, `sourceDir`, plus `transferType` (route-graph) and `apis` (repeatable,
log-analysis — narrow to a subset).

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

* **MARKER** — `[MightyMessage]` = front-end (controller), `[MightyHostMessage]` =
  backend.
* Bracket fields after the marker, in order: app, session, user,
  **client version**, **correlation id**, platform, **time taken** (e.g. `500ms`
  on a front-end Response = total time; `230ms` on a backend Response = that
  backend's time; empty on Request lines).
* **Direction** — front-end uses `-Request -` / `-Response -`; backend uses
  `-[Request]` / `-[Response]`.
* **Path** — front-end matched by suffix to the controller path (the
  `/services/<country>/` segment is tolerated); backend matched **ends-with** to
  the traced backend.
* **Success** — `responseCode` is all zeros (any length: `00000` … `0000000`).

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
| **Partial** | Front-end OK but an *observed* backend failed |
| **Indeterminate** | A response was logged but no parseable code |
| **Not tested** | No entry for this API at the requested client release |

A traced backend that never appears in a transaction is treated as a **not-taken
choice branch** (informational), not a failure.

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
instances, and collects backend endpoints from `setProperty name="api"`.

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
              ImpactIndex/ApiImpact, LogAnalysisReport/ApiLogResult/
              BackendCallResult, LogStatus
  model/      neutral route model shared by both loaders
  loader/     CamelRouteModelLoader, XmlDomRouteModelLoader, RouteRegistry
  resolve/    OperationResolver (JavaParser), VersionResolver
  trace/      RouteTraverser
  service/    RouteTraceService (trace/catalog/impact), LogAnalysisService
  web/        RouteGraphController, WebConfig
src/main/frontend/                React + Vite + TypeScript SPA (built into the jar)
  src/views/      TraceView, ImpactView
  src/components/  RouteGraph, ControlPanel, SplunkPanel, LogAnalysisPanel, …
  src/spl.ts       SPL generation (events query + time presets)
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
  delegation, shared-host-route exclusion).
* `LogAnalysisServiceTest` — end-to-end verdicts (success/latest-wins, not-tested,
  timeout, partial) over synthetic output logs **and** Splunk CSV/JSON exports.

All fixtures under `src/test/resources` are test-only — not part of the app or jar.
