package com.arjun.tracer.web;

import com.arjun.tracer.api.LogAnalysisReport;
import com.arjun.tracer.api.TraceRequest;
import com.arjun.tracer.service.LogAnalysisService;
import com.arjun.tracer.service.RouteTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Exposes the route trace. Supports both a GET (for the UI / quick links) and a
 * POST (JSON body) form.
 */
@RestController
public class RouteGraphController {

    private final RouteTraceService service;
    private final LogAnalysisService logService;

    public RouteGraphController(RouteTraceService service, LogAnalysisService logService) {
        this.service = service;
        this.logService = logService;
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
            @RequestParam(required = false) String branch) {
        return service.analyze(new TraceRequest(api, version, transferType, sourceDir, country, repo, branch));
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
            @RequestParam(required = false) String branch) {
        return Map.of("countries", service.listCountries(
                new TraceRequest(null, null, null, sourceDir, null, repo, branch)));
    }

    /** Discovery metadata for the UI: countries, versions and transferType values. */
    @GetMapping("/internal/meta")
    public Map<String, Object> meta(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch) {
        return service.meta(new TraceRequest(null, null, null, sourceDir, country, repo, branch));
    }

    /** Impact catalog: every API's routes/backends/hosts at a client version, for impact analysis. */
    @GetMapping("/internal/impact-index")
    public com.arjun.tracer.api.ImpactIndex impactIndex(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String transferType,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch) {
        return service.impactIndex(new TraceRequest(null, version, transferType, sourceDir, country, repo, branch));
    }

    /**
     * Release diff: for a target client version, what each impacted API changed
     * relative to its immediate-lower version (per-route structural diff of the
     * whole resolved flow). Pure static analysis — no logs.
     */
    @GetMapping("/internal/version-diff")
    public com.arjun.tracer.api.VersionDiffReport versionDiff(
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch) {
        return service.versionDiff(new TraceRequest(null, version, null, sourceDir, country, repo, branch));
    }

    /**
     * Correlate an uploaded output log against the traced APIs for a client
     * release: which APIs were exercised and whether they passed end-to-end.
     * Multipart upload ({@code file}); {@code apis} optionally narrows to a
     * selected subset (else every API in the impact index is reported).
     */
    @PostMapping("/internal/log-analysis")
    public LogAnalysisReport logAnalysis(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String sourceDir,
            @RequestParam(required = false) List<String> apis,
            @RequestParam(required = false) List<String> backends,
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return logService.analyze(in, file.getOriginalFilename(), version, country, sourceDir,
                    apis, backends, all, app, repo, branch);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
