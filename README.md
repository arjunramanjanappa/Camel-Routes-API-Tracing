# Camel Routes API Tracing

A generic **route tracing utility + UI** for enterprise frameworks built on
**Spring Boot + Apache Camel 4 (XML DSL)** with a UFW command/AOP layer.

Point it at a framework **source directory** and, for any REST API, it:

1. resolves the **operation name** from the controllers,
2. resolves the **route version** with fallback (`R9.4 → R9.3 → … → BASE`),
3. **traverses** the Camel routes (`direct:` calls, recursively),
4. collects every **backend API** invoked (`setProperty name="api"`),
5. renders the whole flow as an interactive **graph**.

It is a *standalone* analysis app — it does **not** need the framework to be
running.

---

## Run

Point it at your framework checkout (there is no bundled default):

```bash
TRACER_SOURCE_DIR=/path/to/mty-framework mvn spring-boot:run
```

Then open the UI:

* **UI:**  http://localhost:8080/route-tracer
* **API:** http://localhost:8080/internal/route-graph?api=/payment/v2/fund/submit&version=9.4&sourceDir=/path/to/mty-framework

The source directory can be set globally via `TRACER_SOURCE_DIR` /
`tracer.source-dir`, or per request via the `sourceDir` field / query param. If
none is provided the API returns a `400` explaining what to set.

---

## API

`GET /internal/route-graph` (or `POST` with a JSON body)

| param          | required | meaning                                                        |
|----------------|----------|----------------------------------------------------------------|
| `api`          | no       | REST path, e.g. `/payment/v2/fund/submit` (or a raw operation) |
| `version`      | no       | client release version, e.g. `9.4`; blank ⇒ BASE / all         |
| `transferType` | no       | choice-branch filter (`OWN`/`INTRA`/`INTER`); blank ⇒ all      |
| `sourceDir`    | no       | override the configured source directory                       |

### Input modes

The response carries a `mode` discriminator:

* **`api` + `version`** → single trace of that exact resolution.
* **`api` only** → single trace (blank version ⇒ BASE).
* **no `api`** → **catalog** (`mode: "catalog"`): every discovered API, grouped by
  the client release version its route resolves to (`9.4`, `9.3`, …, `BASE`, and
  a `(no route found)` bucket for APIs with no route). With a `version`, every
  API is resolved (with fallback) for that client release.

Single-trace response (abridged):

```json
{
  "mode": "single",
  "operationName": "fundTransferSubmitV2Api",
  "command": "FundTransferSubmitV2ApiCommand",
  "resolvedRoute": "R9.4_fundTransferSubmitV2Api",
  "resolvedVersion": "9.4",
  "baseFallback": false,
  "flow": ["R9.4_fundTransferSubmitV2Api", "R9.4_masterFundTransferSubmitApi", "..."],
  "backendApis": ["{{baseUrl}}/bfs/ft/inter/submit", "..."],
  "warnings": [],
  "graph": { "nodes": [{ "id": "...", "type": "API|ROUTE|BACKEND" }], "edges": [{ "from": "...", "to": "..." }] }
}
```

Catalog response (abridged):

```json
{
  "mode": "catalog",
  "operationCount": 2,
  "versionsFound": ["9.4", "9.3", "BASE", "(no route found)"],
  "groups": [
    { "version": "9.4", "traces": [{ "operationName": "...", "resolvedRoute": "R9.4_...", "backendApis": ["..."] }] }
  ],
  "graph": { "nodes": ["..."], "edges": ["..."] }
}
```

---

## How it works

```
source dir ──► scan ──► OperationResolver (JavaParser over controllers)
                  └────► RouteRegistry  (route XML, hybrid loader)
                              │
   api + version ──► VersionResolver ──► entry route name
                              │
                       RouteTraverser ──► RouteGraph (+ flow + backends)
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
(filtered by `transferType`, or all branches when omitted), records external
host calls, and collects backend endpoints from `setProperty name="api"`.

### Hybrid route loader
Each route XML is loaded the **Camel 4 way** — via `RoutesLoader` into a
`CamelContext`, then walked over the real
`RouteDefinition`/`ProcessorDefinition` model (`ToDefinition`,
`RecipientListDefinition`, `ChoiceDefinition`, `SetPropertyDefinition`, …). The
context is never started. If a file cannot be loaded that way (custom
components, Spring beans, placeholder validation), it **falls back** to a DOM
parser that produces the same neutral model. Both feed one traverser.

> Note: the in-memory resource is named `inline-route.xml` because Camel derives
> a loader from the extension after the *first* dot — a real name like
> `R9.4_x.xml` would otherwise be misread as extension `4_x.xml`.

---

## Layout

```
src/main/java/com/arjun/tracer/
  api/        DTOs (TraceRequest/Response, GraphNode/Edge, RouteGraph)
  model/      neutral route model shared by both loaders
  loader/     CamelRouteModelLoader, XmlDomRouteModelLoader, RouteRegistry
  resolve/    OperationResolver (JavaParser), VersionResolver
  trace/      RouteTraverser
  service/    RouteTraceService (orchestration + source scan)
  web/        RouteGraphController, WebConfig
src/main/resources/static/route-tracer.html   UI (Cytoscape via CDN)
src/test/resources/sample-framework/          synthetic fixture (test-only)
```

## Test

```bash
mvn test
```

`RouteTraceServiceTest` runs the full pipeline against a synthetic framework
fixture under `src/test/resources/sample-framework` (test-only — not part of the
app or jar) — exact version, fallback to a lower version, BASE fallback,
`transferType` filtering, `<otherwise>` branches and cross-version delegation.
