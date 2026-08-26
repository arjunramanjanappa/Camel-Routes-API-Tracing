package com.uob.tracer.web;

import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.ModuleLogReport;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionLogReport;
import com.uob.tracer.service.AppConfigService;
import com.uob.tracer.service.CapabilityService;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.LogRulesService;
import com.uob.tracer.service.RouteTraceService;
import com.uob.tracer.service.SettingsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * Exposes the route trace. Supports both a GET (for the UI / quick links) and a
 * POST (JSON body) form.
 */
@RestController
public class RouteGraphController {

    private final RouteTraceService service;
    private final LogAnalysisService logService;
    private final AppConfigService appConfig;
    private final SettingsService settings;
    private final LogRulesService logRules;
    private final CapabilityService capabilities;

    public RouteGraphController(RouteTraceService service, LogAnalysisService logService,
                                AppConfigService appConfig, SettingsService settings, LogRulesService logRules,
                                CapabilityService capabilities) {
        this.settings = settings;
        this.service = service;
        this.logService = logService;
        this.appConfig = appConfig;
        this.logRules = logRules;
        this.capabilities = capabilities;
    }

    /**
     * Trace or catalog. With {@code api} → a single trace; without {@code api} →
     * a catalog of every API grouped by client release version. The response is
     * a {@code TraceResponse} or {@code CatalogResponse} (distinguished by its
     * {@code mode} field).
     */
    @GetMapping("/internal/route-graph")
    public Object traceGet(
            @RequestParam(required = false) String api,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String transferType,
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) List<String> dep,
            @RequestParam(required = false) String app) {
        return service.analyze(new TraceRequest(api, version, transferType, sourceDir, country, repo, branch, dep, app));
    }

    @PostMapping("/internal/route-graph")
    public Object tracePost(@RequestBody TraceRequest request) {
        return service.analyze(request);
    }

    /** Bootstrap scopes (countries) discovered in the source tree — for the UI dropdown. */
    @GetMapping("/internal/countries")
    public Map<String, Object> countries(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) List<String> dep) {
        return Map.of("countries", service.listCountries(
                new TraceRequest(null, null, null, sourceDir, null, repo, branch, dep)));
    }

    /** Discovery metadata for the UI: countries, versions and transferType values. */
    @GetMapping("/internal/meta")
    public Map<String, Object> meta(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) List<String> dep) {
        return service.meta(new TraceRequest(null, null, null, sourceDir, country, repo, branch, dep));
    }

    /** Impact catalog: every API's routes/backends/hosts at a client version, for impact analysis. */
    @GetMapping("/internal/impact-index")
    public com.uob.tracer.api.ImpactIndex impactIndex(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String transferType,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) List<String> dep,
            @RequestParam(required = false) String app) {
        return service.impactIndex(new TraceRequest(null, version, transferType, sourceDir, country, repo, branch, dep, app));
    }

    /**
     * Release diff: for a target client version, what each impacted API changed
     * relative to its immediate-lower version (per-route structural diff of the
     * whole resolved flow). Pure static analysis — no logs.
     */
    @GetMapping("/internal/version-diff")
    public com.uob.tracer.api.VersionDiffReport versionDiff(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) List<String> dep,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String appVersion) {
        return service.versionDiff(new TraceRequest(null, version, null, sourceDir, country, repo, branch, dep, app, appVersion));
    }

    /**
     * Correlate an uploaded output log against the traced APIs for a client
     * release: which APIs were exercised and whether they passed end-to-end.
     * Multipart upload ({@code file}); {@code apis} optionally narrows to a
     * selected subset (else every API in the impact index is reported).
     */
    @PostMapping("/internal/log-analysis")
    public LogAnalysisReport logAnalysis(
            @RequestParam("file") List<MultipartFile> file,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) List<String> apis,
            @RequestParam(required = false) List<String> backends,
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) List<String> dep) throws IOException {
        try (InputStream in = combined(file)) {
            return logService.analyze(in, firstName(file), version, country, sourceDir,
                    apis, backends, all, app, repo, branch, dep == null ? List.of() : dep);
        }
    }

    /**
     * Multi-module release test: the uploaded log chunk(s) are uploaded ONCE and correlated against
     * every module's APIs in one request. The service parses the log once per distinct marker flavour
     * (Mighty / SPL / SPL-Secure) and reuses it across the modules that share it — so N SPL sub-modules
     * cost one parse, not N. Module specs are parallel lists; a module that fails carries an error.
     */
    @PostMapping("/internal/log-analysis-multi")
    public List<ModuleLogReport> logAnalysisMulti(
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) List<String> dep,
            @RequestParam(required = false) List<String> moduleName,
            @RequestParam(required = false) List<String> moduleSourceDir,
            @RequestParam(required = false) List<String> moduleRepo,
            @RequestParam(required = false) List<String> moduleBranch,
            @RequestParam(required = false) List<String> moduleApp) {
        int n = moduleName == null ? 0 : moduleName.size();
        List<MultipartFile> files = file == null ? List.of() : file;
        List<LogAnalysisService.ModuleSpec> specs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            specs.add(new LogAnalysisService.ModuleSpec(moduleName.get(i), at(moduleSourceDir, i),
                    at(moduleRepo, i), at(moduleBranch, i), at(moduleApp, i)));
        }
        // The upload is spooled by the servlet, so combined(files) can be re-opened once per flavour.
        return logService.analyzeModules(() -> combined(files), firstName(files), version, country,
                specs, dep == null ? List.of() : dep);
    }

    /**
     * Release Impact: upload the log ONCE and correlate it against EVERY requested version (compared version +
     * each API's version + impacted re-test route versions). The servlet spools the upload, so combined(files) is
     * re-opened per version — a large log is not re-uploaded per version (previously the UI POSTed the whole file
     * once per version in parallel). Each version's result carries the per-module reports, like log-analysis-multi.
     */
    @PostMapping("/internal/log-analysis-versions")
    public List<VersionLogReport> logAnalysisVersions(
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(required = false) List<String> version,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) List<String> dep,
            @RequestParam(required = false) List<String> moduleName,
            @RequestParam(required = false) List<String> moduleSourceDir,
            @RequestParam(required = false) List<String> moduleRepo,
            @RequestParam(required = false) List<String> moduleBranch,
            @RequestParam(required = false) List<String> moduleApp) {
        int n = moduleName == null ? 0 : moduleName.size();
        List<MultipartFile> files = file == null ? List.of() : file;
        List<LogAnalysisService.ModuleSpec> specs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            specs.add(new LogAnalysisService.ModuleSpec(moduleName.get(i), at(moduleSourceDir, i),
                    at(moduleRepo, i), at(moduleBranch, i), at(moduleApp, i)));
        }
        List<String> versions = version == null || version.isEmpty()
                ? java.util.Collections.singletonList("BASE") : version;
        List<String> deps = dep == null ? List.of() : dep;
        String filename = firstName(files);
        List<VersionLogReport> out = new ArrayList<>(versions.size());
        for (String v : versions) {
            // "BASE"/blank => whole release (null version); the original label is echoed back for the caller to map.
            String ver = v == null || v.isBlank() || "BASE".equalsIgnoreCase(v) ? null : v;
            List<ModuleLogReport> mods = logService.analyzeModules(() -> combined(files), filename, ver, country, specs, deps);
            out.add(new VersionLogReport(v, mods));
        }
        return out;
    }

    /** The per-app module lists (main repo + sub-modules) the UI prepopulates from. */
    @GetMapping("/internal/app-config")
    public Map<String, List<AppConfigService.ModuleEntry>> appConfig() {
        return appConfig.readAll();
    }

    /** "Save as default": overwrite one app's configured module list with the posted entries. */
    @PostMapping("/internal/app-config/{app}")
    public Map<String, String> saveAppConfig(@PathVariable String app,
                                             @RequestBody List<AppConfigService.ModuleEntry> modules) {
        appConfig.save(app, modules);
        return Map.of("status", "saved");
    }

    /**
     * Config menu state: whether each token is configured and where the machine-wide config lives. The
     * raw token values are never returned — only presence and a masked preview — so the secret can't be
     * read back out of the UI.
     */
    @GetMapping("/internal/settings")
    public Map<String, Object> settings() {
        SettingsService.Settings s = settings.read();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("home", settings.home().toString());
        out.put("bitbucketTokenSet", !s.bitbucketToken().isBlank());
        out.put("bitbucketTokenMasked", mask(s.bitbucketToken()));
        out.put("npmTokenSet", !s.npmToken().isBlank());
        out.put("npmTokenMasked", mask(s.npmToken()));
        out.put("splunkUrl", s.splunkUrl());   // a URL, not a secret — returned in full so it can be edited
        out.put("passThreshold", s.passThreshold());   // front-end pass-rate threshold (blank = use the default)
        return out;
    }

    /**
     * Save the Bitbucket / npm tokens. A field left out of the body (null) is kept as-is, so one token
     * can be updated without clearing the other; an empty string clears that token. Returns the same
     * masked state as {@link #settings()}.
     */
    @PostMapping("/internal/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, String> body) {
        settings.save(
                body.containsKey("bitbucketToken") ? nz(body.get("bitbucketToken")) : null,
                body.containsKey("npmToken") ? nz(body.get("npmToken")) : null,
                body.containsKey("splunkUrl") ? nz(body.get("splunkUrl")) : null,
                body.containsKey("passThreshold") ? nz(body.get("passThreshold")) : null);
        return settings();
    }

    /**
     * Host response-code rules ({@code log-rules.json}) — the per-app map for the Config editor. Tells log
     * analysis which JSON key to read a backend's code from, what counts as success, and which backends to skip.
     */
    @GetMapping("/internal/log-rules")
    public Map<String, LogRulesService.AppRules> logRules() {
        return logRules.readAll();
    }

    /** Replace the whole host response-code rule map (the Config editor saves every app at once). */
    @PostMapping("/internal/log-rules")
    public Map<String, LogRulesService.AppRules> saveLogRules(@RequestBody Map<String, LogRulesService.AppRules> body) {
        return logRules.saveAll(body == null ? Map.of() : body);
    }

    // --- VAL Capability Matrix: map impacted APIs → business capabilities (how to test) ---

    /**
     * Request for the capability lookup: the impacted front-end + backend API paths, scoped to a country.
     * {@code statusByApi} (Release Test only) maps an API path to its log-analysis verdict (Passed / Failed /
     * Partial / Not tested) so the Excel export can add a "Test Status" column — what the tester should focus on.
     */
    public record CapabilityRequest(List<String> feApis, List<String> beApis, String country,
                                    Map<String, String> statusByApi, String sheetName,
                                    List<CapabilityService.ExtraColumn> trailingColumns,
                                    List<ModuleGroup> modules) {}

    /** One module's APIs + log verdicts for a per-module-tab export (a sheet per module). */
    public record ModuleGroup(String name, List<String> feApis, List<String> beApis, Map<String, String> statusByApi) {}

    /** Whether the two VAL reports are configured (a one-time config, like the log rules). */
    @GetMapping("/internal/capability-config")
    public Map<String, Object> capabilityConfig() {
        return Map.of(
                "interfaceSpec", capabilities.hasInterfaceSpec(),
                "capabilityMatrix", capabilities.hasCapabilityMatrix());
    }

    /**
     * Store the VAL reports (one-time config; re-upload when the VAL updates). Either file may be sent alone,
     * so one can be replaced without re-uploading the other.
     */
    @PostMapping("/internal/capability-config")
    public Map<String, Object> saveCapabilityConfig(
            @RequestParam(value = "interfaceSpec", required = false) MultipartFile interfaceSpec,
            @RequestParam(value = "capabilityMatrix", required = false) MultipartFile capabilityMatrix) throws IOException {
        if (interfaceSpec != null && !interfaceSpec.isEmpty()) {
            capabilities.saveInterfaceSpec(interfaceSpec.getBytes());
        }
        if (capabilityMatrix != null && !capabilityMatrix.isEmpty()) {
            capabilities.saveCapabilityMatrix(capabilityMatrix.getBytes());
        }
        return capabilityConfig();
    }

    /** Resolve the impacted APIs to their capabilities (JSON) — drives the summary PDFs' capability columns. */
    @PostMapping("/internal/capabilities")
    public CapabilityService.CapabilityResult resolveCapabilities(@RequestBody CapabilityRequest req) {
        return capabilities.resolve(safe(req.feApis()), safe(req.beApis()), req.country());
    }

    /** The joined Capability export as an .xlsx download (one row per API→capability + an Unmatched sheet). */
    @PostMapping("/internal/capabilities/export")
    public ResponseEntity<byte[]> exportCapabilities(@RequestBody CapabilityRequest req) {
        byte[] xlsx;
        if (req.modules() != null && !req.modules().isEmpty()) {
            // Multi-module: one sheet per module (the tab name IS the module) — each module's APIs resolved on their own.
            List<CapabilityService.ModuleCapabilities> mods = new ArrayList<>();
            for (ModuleGroup g : req.modules()) {
                CapabilityService.CapabilityResult res = capabilities.resolve(safe(g.feApis()), safe(g.beApis()), req.country());
                mods.add(new CapabilityService.ModuleCapabilities(g.name(), res, g.statusByApi() == null ? Map.of() : g.statusByApi()));
            }
            xlsx = capabilities.exportByModule(mods);
        } else {
            String sheet = req.sheetName() == null || req.sheetName().isBlank() ? "Capabilities" : req.sheetName();
            xlsx = capabilities.exportExcel(
                    capabilities.resolve(safe(req.feApis()), safe(req.beApis()), req.country()),
                    sheet,
                    req.statusByApi() == null ? Map.of() : req.statusByApi(),
                    req.trailingColumns() == null ? List.of() : req.trailingColumns());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"capability-matrix.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }

    private static List<String> safe(List<String> l) { return l == null ? List.of() : l; }

    private static String nz(String v) { return v == null ? "" : v; }

    /** Show only the last 4 characters of a token, e.g. {@code ••••abcd}; blank when unset. */
    private static String mask(String token) {
        if (token == null || token.isBlank()) return "";
        String t = token.trim();
        return t.length() <= 4 ? "••••" : "••••" + t.substring(t.length() - 4);
    }

    private static String firstName(List<MultipartFile> files) {
        return files == null || files.isEmpty() ? null : files.get(0).getOriginalFilename();
    }

    /** Blank/missing → null (matches the single-source params); else the value at index i. */
    private static String at(List<String> l, int i) {
        if (l == null || i >= l.size()) return null;
        String v = l.get(i);
        return (v == null || v.isBlank()) ? null : v;
    }

    /**
     * Concatenate the uploaded chunks into one stream so a log split across files (or servers) is
     * analysed as a single dataset. Text chunks get a newline between them (so a missing trailing
     * newline never merges two lines); gzip chunks are concatenated raw as multi-member gzip.
     * Each part is read via {@code getInputStream()}, which the servlet spools re-readably to disk.
     */
    private static InputStream combined(List<MultipartFile> files) throws IOException {
        if (files.size() == 1) return files.get(0).getInputStream();
        String first = firstName(files);
        boolean gz = first != null && first.toLowerCase().endsWith(".gz");
        Vector<InputStream> streams = new Vector<>();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0 && !gz) streams.add(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)));
            streams.add(files.get(i).getInputStream());
        }
        return new SequenceInputStream(streams.elements());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Upload exceeds the server limit. Reduce the number/size of files, "
                        + "or raise spring.servlet.multipart.max-request-size / max-file-size."));
    }
}
