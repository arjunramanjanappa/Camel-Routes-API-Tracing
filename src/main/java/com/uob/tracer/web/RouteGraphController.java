package com.uob.tracer.web;

import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.ModuleLogReport;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.service.LogAnalysisService;
import com.uob.tracer.service.RouteTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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
            @RequestParam(required = false) String app) {
        return service.versionDiff(new TraceRequest(null, version, null, sourceDir, country, repo, branch, dep, app));
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
     * every module's APIs in one request. Each module is re-read from the spooled upload (so a
     * 200&nbsp;MB+ log is not re-uploaded per module) with its own marker flavour ({@code moduleApp}).
     * Module specs are parallel lists indexed together; a module that fails carries an error.
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
            @RequestParam(required = false) List<String> moduleApp,
            @RequestParam(required = false) List<Integer> moduleFileCount) {
        int n = moduleName == null ? 0 : moduleName.size();
        List<String> deps = dep == null ? List.of() : dep;
        List<MultipartFile> files = file == null ? List.of() : file;
        // Per-module mode: the flat `file` list is partitioned by moduleFileCount, so each module is
        // scoped to its own upload. Shared mode (no counts): every module re-reads the whole upload.
        boolean perModule = moduleFileCount != null && !moduleFileCount.isEmpty();
        List<ModuleLogReport> out = new ArrayList<>(n);
        int offset = 0;
        for (int i = 0; i < n; i++) {
            String name = moduleName.get(i);
            List<MultipartFile> mfiles;
            if (perModule) {
                int c = i < moduleFileCount.size() ? Math.max(0, moduleFileCount.get(i)) : 0;
                int end = Math.min(offset + c, files.size());
                mfiles = c > 0 && offset < end ? new ArrayList<>(files.subList(offset, end)) : List.of();
                offset += c;
            } else {
                mfiles = files;   // shared: re-read the whole upload for this module
            }
            try (InputStream in = mfiles.isEmpty() ? InputStream.nullInputStream() : combined(mfiles)) {
                LogAnalysisReport rep = logService.analyze(in, firstName(mfiles), version, country,
                        at(moduleSourceDir, i), List.of(), List.of(), true, at(moduleApp, i),
                        at(moduleRepo, i), at(moduleBranch, i), deps);
                out.add(new ModuleLogReport(name, rep, null));
            } catch (Exception e) {
                out.add(new ModuleLogReport(name, null, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return out;
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
