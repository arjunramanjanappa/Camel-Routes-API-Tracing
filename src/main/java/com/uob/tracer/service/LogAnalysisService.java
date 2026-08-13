package com.uob.tracer.service;

import com.uob.tracer.api.ApiImpact;
import com.uob.tracer.api.ApiLogResult;
import com.uob.tracer.api.BackendCallResult;
import com.uob.tracer.api.BackendLogResult;
import com.uob.tracer.api.ImpactIndex;
import com.uob.tracer.api.LogAnalysisReport;
import com.uob.tracer.api.LogStatus;
import com.uob.tracer.api.ModuleLogReport;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.resolve.VersionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Correlates an uploaded application log (or Splunk export) against the traced
 * API footprint to tell, per client release, which APIs were actually exercised
 * and whether they passed end-to-end.
 *
 * <p>Reads the file as a single streaming pass: a cheap substring pre-filter
 * skips the (vast majority) of non-matching lines before any regex runs, and
 * only matched lines are retained — so memory stays proportional to the number
 * of MightyMessage/MightyHostMessage lines, not the file size.
 *
 * <p>Log shape (see {@code sample-logs/}):
 * <pre>
 * 2026-06-11 18.43.45.102 [thread] INFO [MightyMessage][app][sess][user][9.14][corrId][platform][500ms]-/.../services/sg/&lt;api&gt; -Response - {json}
 * </pre>
 * {@code [MightyMessage]} = front-end (controller), {@code [MightyHostMessage]}
 * = backend. A transaction is all lines sharing one correlation id, printed as
 * FE-Request → BE-Request → BE-Response → FE-Response. Success = responseCode is
 * all zeros (any length).
 */
@Service
public class LogAnalysisService {

    private final RouteTraceService traceService;
    private final LogRulesService logRules;
    private final SettingsService settings;   // nullable (test constructors) — runtime pass-threshold override

    /**
     * Default front-end pass-rate threshold: an API with many transactions is SUCCESS when at least this
     * fraction of its front-end calls passed, else FAILED — so a handful of failures among many passes isn't a
     * blanket fail, and one late failure doesn't dominate. Default 0.95 (95%); set an application-wide default
     * with {@code tracer.log.pass-threshold}, or override at runtime in the Config menu (settings.json's
     * {@code passThreshold}, which wins when present).
     */
    @org.springframework.beans.factory.annotation.Value("${tracer.log.pass-threshold:0.95}")
    private double passThreshold = 0.95;

    @org.springframework.beans.factory.annotation.Autowired
    public LogAnalysisService(RouteTraceService traceService, LogRulesService logRules, SettingsService settings) {
        this.traceService = traceService;
        this.logRules = logRules;
        this.settings = settings;
    }

    /** Test constructor: rules configured, no runtime settings override (uses the default threshold). */
    public LogAnalysisService(RouteTraceService traceService, LogRulesService logRules) {
        this(traceService, logRules, null);
    }

    /** Back-compat / test constructor: no host response-code rules configured (points at an empty store). */
    public LogAnalysisService(RouteTraceService traceService) {
        this(traceService, new LogRulesService(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "traceguard-no-rules").toString(),
                new com.fasterxml.jackson.databind.ObjectMapper()), null);
    }

    /** The active pass threshold: the Config-menu override (settings.json) when set & valid, else the default. */
    private double effectiveThreshold() {
        if (settings != null) {
            Double override = settings.read().passThresholdFraction();
            if (override != null) {
                return override;
            }
        }
        return passThreshold;
    }

    // timestamp [thread] LEVEL [marker][...fields...]-/path -Dir - json
    // The bracket fields after the marker are located by PATTERN, not by fixed
    // position — environments differ in how many/which fields they emit, so the
    // version is found by its 9.18 shape and the latency by its 500ms shape.
    // ts [thread] LEVEL [marker][...fields...] -<path> - [Request|Response] (- OR :) json
    // Bracket fields are located by PATTERN not position. A host line may carry a
    // "[jwt]: true,  -" prefix before the backend URL, and the JSON may follow the
    // direction with a ":" instead of a "-" — both shapes are tolerated, as is varying
    // whitespace around every separator.
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}[.:]\\d{2}[.:]\\d{2}[.:]\\d{1,3})\\s+"
                    + "\\[[^\\]]*\\]\\s+\\S+\\s+"
                    + "\\[([A-Za-z0-9_]+Message)\\]"             // app marker, e.g. MightyMessage / SPLHostMessage
                    + "((?:\\[[^\\]]*\\])+?)\\s*-\\s*"           // bracket meta fields, then a separator dash
                    + "(?:\\[jwt\\][^-]*-\\s*)?"                 // optional "[jwt]: true,  -" prefix before the URL
                    + "(\\S+)\\s*-\\s*\\[?(Request|Response)\\]?\\s*[-:]\\s*(.*)$");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern TOOK = Pattern.compile("(\\d+)\\s*ms");
    // A client release version: 9.18, 9.4, R9.14, 9.4.1 — at least one dot so plain
    // numeric fields (session ids etc.) are never mistaken for a version.
    private static final Pattern VERSION_FIELD = Pattern.compile("R?(\\d+(?:\\.\\d+)+)");
    private static final Pattern ALL_ZEROS = Pattern.compile("0+");
    // The correlation id is a trace id: a long hex string (16+ hex chars, no dashes —
    // OpenTelemetry/Sleuth style). Matched by shape so it's found regardless of position.
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{16,}");
    // Regex fallbacks (case-insensitive, quote/number tolerant) used only when the
    // payload isn't valid JSON — the primary path parses the JSON and searches the tree.
    private static final Pattern CODE = Pattern.compile("[\"']?responseCode[\"']?\\s*:\\s*[\"']?([0-9A-Za-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC = Pattern.compile("[\"']?responseDescription[\"']?\\s*:\\s*[\"']?([^\"',}]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVC_VERSION = Pattern.compile("[\"']?serviceVersionNumber[\"']?\\s*:\\s*[\"']?([0-9][0-9.]*)", Pattern.CASE_INSENSITIVE);
    // A log timestamp: yyyy-MM-dd{space|T}HH{:|.}mm{:|.}ss with optional {:|.}millis — same shape the
    // LINE / SECURE_FE patterns capture as group 1. Used to compute the span of the analysed log.
    private static final Pattern TS_PARTS = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2})[.:](\\d{2})[.:](\\d{2})(?:[.:](\\d{1,3}))?");

    // --- SPL-Secure (intercepted-UFW) front-end shapes; auto-detected, never used for Mighty/SPL ---
    // Request:  <ts> <corrId>|<spanId>| [thread] [LEVEL] [SPLAppLog]   - <path> - Request  - {json}
    // Response: <ts> ||                 [thread] [LEVEL] [SPLWSAppLog] -          Response : status=200, body={json}, headers={…,TRACE-ID=<corrId>,…}
    // The path is OPTIONAL: the response usually omits it and is tied to its request by corrId
    // alone. The backend stays SPLHostMessage and is parsed by the standard LINE pattern above.
    private static final Pattern SECURE_FE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}[.:]\\d{2}[.:]\\d{2}[.:]\\d{1,3})\\s+"
                    + "(?:([^\\[\\s]\\S*)\\s+)?"                     // optional "corrId|spanId|" / "||" prefix (never starts with '[')
                    + "\\[[^\\]]*\\]\\s+\\[[^\\]]*\\]\\s+"           // [thread] [LEVEL]
                    + "\\[(?:SPLAppLog|SPLWSAppLog)\\]\\s*-\\s*"     // the secure front-end logger
                    + "(?:(\\S+)\\s*-\\s*)?"                         // optional path (the response identifies by corrId)
                    + "(Request|Response)\\s*[-:]\\s*(.*)$");
    // Correlation id: a 32-hex trace id (the span id is 16-hex, so a {32} match never picks it up).
    private static final Pattern SECURE_CORR = Pattern.compile("\\b([0-9a-fA-F]{32})\\b");
    private static final Pattern TRACE_ID_HDR = Pattern.compile("TRACE-ID\\s*[=:]\\s*([0-9a-fA-F]{32})", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Shown when a response IS present but its payload format isn't recognised (no readable responseCode). */
    private static final String UNRECOGNISED_RESPONSE =
            "Response received but its format was not recognised — responseCode could not be read; needs checking.";

    /**
     * Analyse an uploaded log / Splunk export. Caller owns the stream.
     *
     * <p>What is reported is driven by the selection, log-type aware:
     * <ul>
     *   <li>{@code all=true} → every API (front-end) and every backend of the release.</li>
     *   <li>front-end APIs selected → per-API report from the MightyMessage lines.</li>
     *   <li>backends selected → per-backend report from the MightyHostMessage lines.</li>
     *   <li>both selected → both sections.</li>
     * </ul>
     */
    /** Back-compat entry (local-path / no Bitbucket source). */
    public LogAnalysisReport analyze(InputStream raw, String filename, String version,
                                     String country, String sourceDir,
                                     List<String> selectedApis, List<String> selectedBackends, boolean all,
                                     String app)
            throws IOException {
        return analyze(raw, filename, version, country, sourceDir, selectedApis, selectedBackends, all, app, null, null);
    }

    /** Back-compat entry (Bitbucket source, no dependency roots). */
    public LogAnalysisReport analyze(InputStream raw, String filename, String version,
                                     String country, String sourceDir,
                                     List<String> selectedApis, List<String> selectedBackends, boolean all,
                                     String app, String repo, String branch)
            throws IOException {
        return analyze(raw, filename, version, country, sourceDir, selectedApis, selectedBackends, all, app,
                repo, branch, List.of());
    }

    public LogAnalysisReport analyze(InputStream raw, String filename, String version,
                                     String country, String sourceDir,
                                     List<String> selectedApis, List<String> selectedBackends, boolean all,
                                     String app, String repo, String branch, List<String> dependencies)
            throws IOException {
        // Footprint (controller path + traced backends per API) for this release. Computed up
        // front because its auto-detection also tells us whether this is the SPL-Secure flavour
        // (intercepted-UFW command dispatch), which logs its front-end lines differently — that
        // choice drives the marker set and the FE parser.
        ImpactIndex idx = traceService.impactIndex(
                new TraceRequest(null, version, null, sourceDir, country, repo, branch, dependencies));
        boolean secure = idx.isCommandDispatch();
        String application = (app == null || app.isBlank()) ? "Mighty" : app.trim();
        Parsed parsed = parseLog(raw, filename, markersFor(application, secure), application);
        return buildReport(idx, parsed, version, secure, all, selectedApis, selectedBackends,
                logRules.rulesFor(application, secure));
    }

    /**
     * Marker set for an app flavour: {@code <app>Message} / {@code <app>HostMessage} (Mighty →
     * MightyMessage/MightyHostMessage, SPL → SPLMessage/SPLHostMessage). For an auto-detected
     * SPL-Secure source it ADDITIONALLY recognises the SPLAppLog / SPLWSAppLog front-end loggers.
     */
    private Markers markersFor(String app, boolean secure) {
        String application = (app == null || app.isBlank()) ? "Mighty" : app.trim();
        // Host response-code fields to try for this app/marker (config-driven; always includes responseCode).
        List<String> beCodeFields = logRules.rulesFor(application, secure).effectiveCodeFields();
        return new Markers(application + "Message", application + "HostMessage", secure, beCodeFields);
    }

    /** A log parsed into correlation-id transactions — independent of any module's scope, so it is
     *  reusable across every module that shares the same marker flavour. */
    private record Parsed(List<Txn> txns, String detected, int recordsScanned, int matchedLines,
                          int unparsed, List<String> warnings, TimeRange range) {}

    /** The wall-clock span of the analysed marker lines: earliest/latest raw timestamp + seconds between
     *  (seconds = -1 when it can't be determined — no parseable timestamps). */
    private record TimeRange(String start, String end, long seconds) {
        static final TimeRange NONE = new TimeRange(null, null, -1);
    }

    /** Parse a log timestamp ({@code yyyy-MM-dd{ |T}HH{:|.}mm{:|.}ss[{:|.}SSS]}) to a LocalDateTime, or null. */
    private static java.time.LocalDateTime parseTs(String ts) {
        if (ts == null) {
            return null;
        }
        Matcher m = TS_PARTS.matcher(ts);
        if (!m.find()) {
            return null;
        }
        try {
            int ms = m.group(7) == null ? 0 : Integer.parseInt(m.group(7));
            return java.time.LocalDateTime.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                    ms * 1_000_000);
        } catch (RuntimeException e) {
            return null;   // out-of-range component (e.g. a malformed line) → treat as no timestamp
        }
    }

    /** The earliest→latest timestamp span across the parsed lines, keeping the original raw strings. */
    private static TimeRange rangeOf(List<LogLine> lines) {
        java.time.LocalDateTime lo = null, hi = null;
        String loTs = null, hiTs = null;
        for (LogLine l : lines) {
            java.time.LocalDateTime t = parseTs(l.ts());
            if (t == null) {
                continue;
            }
            if (lo == null || t.isBefore(lo)) { lo = t; loTs = l.ts(); }
            if (hi == null || t.isAfter(hi)) { hi = t; hiTs = l.ts(); }
        }
        long secs = (lo != null && hi != null) ? java.time.Duration.between(lo, hi).getSeconds() : -1;
        return new TimeRange(loTs, hiTs, secs);
    }

    /**
     * Parse the upload (raw log / Splunk CSV / Splunk JSON) into transactions for one marker flavour.
     * This is the expensive pass — it reads the whole file — and does NOT depend on any module's API
     * scope, so a single parse per distinct flavour serves every module of that flavour.
     */
    private Parsed parseLog(InputStream raw, String filename, Markers markers, String appLabel) throws IOException {
        InputStream in = (filename != null && filename.toLowerCase().endsWith(".gz"))
                ? new GZIPInputStream(raw) : raw;

        int[] counters = new int[2];   // [0] = records scanned, [1] = marked-but-unparsed
        List<LogLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String detected = "RAW_LOG";

        // The upload is one of three shapes — a raw output log, or a Splunk export
        // (CSV or JSON) of the generated query. A Splunk export carries the original
        // log line in its _raw field, so every shape ultimately yields the same
        // marker lines, which feed the one line parser.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String firstNonBlank = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) { firstNonBlank = line; break; }
                counters[0]++;
            }
            if (firstNonBlank != null && !firstNonBlank.isEmpty() && firstNonBlank.charAt(0) == 0xFEFF) {
                firstNonBlank = firstNonBlank.substring(1);   // strip a UTF-8 BOM (common in exported CSV)
            }
            if (firstNonBlank == null) {
                detected = "EMPTY";
            } else {
                detected = detectFormat(firstNonBlank, markers);
                switch (detected) {
                    case "SPLUNK_CSV" -> {
                        int rawIdx = csvRawIndex(firstNonBlank);   // header row consumed
                        // Stream one CSV record at a time: keep appending physical lines until the
                        // double-quotes balance (a _raw event may span several lines inside its
                        // quoted field — common in real Splunk exports), then parse just that
                        // record. Bounds memory to one record even for large 30-day exports.
                        StringBuilder rec = new StringBuilder();
                        while ((line = r.readLine()) != null) {
                            if (rec.length() > 0) {
                                rec.append('\n');
                            }
                            rec.append(line);
                            if (countChar(rec, '"') % 2 == 0) {   // not inside a quoted field → record complete
                                emitCsvRecord(rec.toString(), rawIdx, lines, counters, markers);
                                rec.setLength(0);
                            }
                        }
                        if (rec.length() > 0) {
                            emitCsvRecord(rec.toString(), rawIdx, lines, counters, markers);   // trailing record
                        }
                    }
                    case "SPLUNK_JSON" -> {
                        StringBuilder sb = new StringBuilder(firstNonBlank);
                        while ((line = r.readLine()) != null) {
                            sb.append('\n').append(line);
                        }
                        for (String event : extractJsonRaw(sb.toString(), warnings)) {
                            counters[0]++;
                            ingest(event, lines, counters, markers);
                        }
                    }
                    default -> parseRawParallel(r, firstNonBlank, Map.of("", markers), Map.of("", lines), Map.of("", counters));
                }
            }
        }

        if (lines.isEmpty()) {
            warnings.add("No log events found for the " + appLabel + " application (detected " + detected
                    + "). Check the file is the raw output log or a Splunk export of the query.");
        }

        return new Parsed(toTxns(lines), detected, counters[0], lines.size(), counters[1], warnings, rangeOf(lines));
    }

    /** Group parsed lines into correlation-id transactions (FE + BE share the id). */
    private List<Txn> toTxns(List<LogLine> lines) {
        Map<String, List<LogLine>> byCorr = new LinkedHashMap<>();
        for (LogLine l : lines) {
            byCorr.computeIfAbsent(l.correlationId(), k -> new ArrayList<>()).add(l);
        }
        List<Txn> txns = new ArrayList<>(byCorr.size());
        for (List<LogLine> group : byCorr.values()) {
            txns.add(buildTxn(group));
        }
        return txns;
    }

    /**
     * Parse the upload ONCE into a {@link Parsed} per flavour, bucketing each record into every flavour in a
     * single read. A mixed Mighty + SPL upload is therefore decoded and scanned once, not once per flavour —
     * each line's cheap marker pre-filter still routes it to only the flavour(s) it belongs to, so no extra
     * regex work is done. Keyed by the caller's flavour key ({@code app|secure}).
     */
    private Map<String, Parsed> parseLogAll(InputStream raw, String filename, Map<String, Markers> flavours)
            throws IOException {
        InputStream in = (filename != null && filename.toLowerCase().endsWith(".gz"))
                ? new GZIPInputStream(raw) : raw;
        Map<String, List<LogLine>> linesByKey = new LinkedHashMap<>();
        Map<String, int[]> countersByKey = new LinkedHashMap<>();   // [0]=records scanned, [1]=marked-but-unparsed
        for (String k : flavours.keySet()) {
            linesByKey.put(k, new ArrayList<>());
            countersByKey.put(k, new int[2]);
        }
        List<String> warnings = new ArrayList<>();
        String detected = "RAW_LOG";

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String firstNonBlank = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) { firstNonBlank = line; break; }
                for (int[] c : countersByKey.values()) { c[0]++; }
            }
            if (firstNonBlank != null && !firstNonBlank.isEmpty() && firstNonBlank.charAt(0) == 0xFEFF) {
                firstNonBlank = firstNonBlank.substring(1);
            }
            if (firstNonBlank == null) {
                detected = "EMPTY";
            } else {
                detected = detectFormatAny(firstNonBlank, flavours.values());
                switch (detected) {
                    case "SPLUNK_CSV" -> {
                        int rawIdx = csvRawIndex(firstNonBlank);
                        StringBuilder rec = new StringBuilder();
                        while ((line = r.readLine()) != null) {
                            if (rec.length() > 0) { rec.append('\n'); }
                            rec.append(line);
                            if (countChar(rec, '"') % 2 == 0) {
                                for (var e : flavours.entrySet()) {
                                    emitCsvRecord(rec.toString(), rawIdx, linesByKey.get(e.getKey()), countersByKey.get(e.getKey()), e.getValue());
                                }
                                rec.setLength(0);
                            }
                        }
                        if (rec.length() > 0) {
                            for (var e : flavours.entrySet()) {
                                emitCsvRecord(rec.toString(), rawIdx, linesByKey.get(e.getKey()), countersByKey.get(e.getKey()), e.getValue());
                            }
                        }
                    }
                    case "SPLUNK_JSON" -> {
                        StringBuilder sb = new StringBuilder(firstNonBlank);
                        while ((line = r.readLine()) != null) { sb.append('\n').append(line); }
                        for (String event : extractJsonRaw(sb.toString(), warnings)) {
                            for (var e : flavours.entrySet()) {
                                countersByKey.get(e.getKey())[0]++;
                                ingest(event, linesByKey.get(e.getKey()), countersByKey.get(e.getKey()), e.getValue());
                            }
                        }
                    }
                    default -> parseRawParallel(r, firstNonBlank, flavours, linesByKey, countersByKey);
                }
            }
        }

        Map<String, Parsed> out = new LinkedHashMap<>();
        for (var e : flavours.entrySet()) {
            String k = e.getKey();
            List<LogLine> lines = linesByKey.get(k);
            int[] c = countersByKey.get(k);
            List<String> w = new ArrayList<>(warnings);
            if (lines.isEmpty()) {
                w.add("No log events found for this application (detected " + detected
                        + "). Check the file is the raw output log or a Splunk export of the query.");
            }
            out.put(k, new Parsed(toTxns(lines), detected, c[0], lines.size(), c[1], w, rangeOf(lines)));
        }
        return out;
    }

    /**
     * Parse the RAW log lines across CPU cores. A single reader thread streams the lines (I/O stays sequential —
     * gzip/decode can't be split) and hands fixed-size batches to a bounded worker pool; each worker does the
     * expensive per-line work (marker pre-filter + regex + JSON) into a worker-LOCAL partial, then merges it under
     * one lock. Order-independent and equal to the sequential parse — correlation groups by id and sorts by ts, so
     * the collected lines can arrive in any order. A bounded queue + CallerRuns backpressure keeps memory in check.
     */
    private void parseRawParallel(BufferedReader r, String firstLine, Map<String, Markers> flavours,
                                  Map<String, List<LogLine>> linesByKey, Map<String, int[]> countersByKey) throws IOException {
        List<String> keys = new ArrayList<>(flavours.keySet());
        int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        if (workers == 1) {   // single core — no point paying for threads
            ingestAll(firstLine, flavours, linesByKey, countersByKey);
            String line;
            while ((line = r.readLine()) != null) {
                ingestAll(line, flavours, linesByKey, countersByKey);
            }
            return;
        }
        final int batchSize = 2000;
        ExecutorService pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers * 2), new ThreadPoolExecutor.CallerRunsPolicy());
        Object mergeLock = new Object();
        try {
            List<String> batch = new ArrayList<>(batchSize);
            batch.add(firstLine);
            String line;
            while ((line = r.readLine()) != null) {
                batch.add(line);
                if (batch.size() >= batchSize) {
                    submitBatch(pool, batch, flavours, keys, linesByKey, countersByKey, mergeLock);
                    batch = new ArrayList<>(batchSize);
                }
            }
            if (!batch.isEmpty()) {
                submitBatch(pool, batch, flavours, keys, linesByKey, countersByKey, mergeLock);
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Parse one batch into worker-local per-flavour partials, then merge them into the shared maps under lock. */
    private void submitBatch(ExecutorService pool, List<String> batch, Map<String, Markers> flavours, List<String> keys,
                             Map<String, List<LogLine>> linesByKey, Map<String, int[]> countersByKey, Object mergeLock) {
        pool.execute(() -> {
            Map<String, List<LogLine>> local = new HashMap<>();
            Map<String, int[]> localCounts = new HashMap<>();
            for (String k : keys) {
                local.put(k, new ArrayList<>());
                localCounts.put(k, new int[2]);
            }
            for (String s : batch) {
                for (String k : keys) {
                    localCounts.get(k)[0]++;
                    ingest(s, local.get(k), localCounts.get(k), flavours.get(k));
                }
            }
            synchronized (mergeLock) {
                for (String k : keys) {
                    linesByKey.get(k).addAll(local.get(k));
                    int[] c = countersByKey.get(k);
                    int[] lc = localCounts.get(k);
                    c[0] += lc[0];
                    c[1] += lc[1];
                }
            }
        });
    }

    /** Feed one raw log line to every flavour (each does its own cheap marker pre-filter). */
    private void ingestAll(String line, Map<String, Markers> flavours,
                           Map<String, List<LogLine>> linesByKey, Map<String, int[]> countersByKey) {
        for (var e : flavours.entrySet()) {
            countersByKey.get(e.getKey())[0]++;
            ingest(line, linesByKey.get(e.getKey()), countersByKey.get(e.getKey()), e.getValue());
        }
    }

    /** Format is a property of the file, not the flavour: return the first non-RAW detection, else RAW_LOG. */
    private String detectFormatAny(String firstNonBlank, java.util.Collection<Markers> flavours) {
        for (Markers m : flavours) {
            String d = detectFormat(firstNonBlank, m);
            if (!"RAW_LOG".equals(d)) { return d; }
            if (m.present(firstNonBlank)) { return "RAW_LOG"; }
        }
        return "RAW_LOG";
    }

    /**
     * Correlate a module's API/backend scope against an already-parsed log → its verification report.
     * Cheap (in-memory matching), so it runs per module while the parse is shared across a flavour.
     */
    private LogAnalysisReport buildReport(ImpactIndex idx, Parsed parsed, String version, boolean secure,
                                          boolean all, List<String> selectedApis, List<String> selectedBackends,
                                          LogRulesService.AppRules rules) {
        List<Txn> txns = parsed.txns();
        boolean apiSel = selectedApis != null && !selectedApis.isEmpty();
        boolean beSel = selectedBackends != null && !selectedBackends.isEmpty();

        // Backend URL → expected service version(s), aggregated across the release.
        // And backend api → its "hosturl" (what the host actually logs) — the log path is
        // matched against the hosturl when present, since the api value isn't what's logged.
        Map<String, String> expectedVersions = new LinkedHashMap<>();   // the release change only
        Map<String, String> fullBackendVersions = new LinkedHashMap<>(); // change + BAU (to split BAU rows out)
        Map<String, String> hosturls = new LinkedHashMap<>();
        for (ApiImpact api : idx.getApis()) {
            // Expected service version = the release's OWN change (routes at this version), NOT lower/BAU routes
            // reusing the same backend — those are unchanged and shown as separate BAU rows.
            api.changeBackendVersions().forEach((url, ver) ->
                    expectedVersions.merge(url, ver, LogAnalysisService::joinVersions));
            api.backendVersions().forEach((url, ver) ->
                    fullBackendVersions.merge(url, ver, LogAnalysisService::joinVersions));
            api.backendHosturls().forEach(hosturls::putIfAbsent);
        }

        // Front-end section: the whole release when all=true, the selected APIs when chosen.
        List<ApiLogResult> apiResults = new ArrayList<>();
        double threshold = effectiveThreshold();   // resolved once per report (Config override else default)
        if (all || apiSel) {
            for (ApiImpact api : idx.getApis()) {
                if (!all && !selectedApis.contains(api.api())) {
                    continue;
                }
                apiResults.add(correlate(api, txns, version, hosturls, secure, rules, threshold));
            }
        }

        // Backend section: every release backend when all=true, the selected backends when chosen.
        List<BackendLogResult> backendResults = new ArrayList<>();
        List<String> beTargets = all ? idx.getAllBackends() : (beSel ? selectedBackends : List.of());
        // Disambiguate against the whole backend universe (release backends + the chosen
        // ones) so /bfs/… never steals a /bp/bfs/… call when both exist.
        Set<String> backendUniverse = new java.util.LinkedHashSet<>(idx.getAllBackends());
        backendUniverse.addAll(beTargets);
        for (String backend : beTargets) {
            String changeVer = expectedVersions.get(backend);
            List<String> change = splitList(changeVer);
            List<String> bau = bauVersions(fullBackendVersions.get(backend), changeVer);
            if (change.size() + bau.size() <= 1) {
                boolean isBau = change.isEmpty() && !bau.isEmpty();
                String ver = !change.isEmpty() ? change.get(0) : (!bau.isEmpty() ? bau.get(0) : changeVer);
                backendResults.add(correlateBackend(backend, txns, version, ver, backendUniverse, hosturls, secure, false, isBau, rules));
            } else {
                // Several service-version behaviours on the same backend → one version-strict row each: a
                // verified row per release version, a labelled BAU row per reused version.
                for (String cv : change) {
                    backendResults.add(correlateBackend(backend, txns, version, cv, backendUniverse, hosturls, secure, true, false, rules));
                }
                for (String bv : bau) {
                    backendResults.add(correlateBackend(backend, txns, version, bv, backendUniverse, hosturls, secure, true, true, rules));
                }
            }
        }

        return new LogAnalysisReport(parsed.detected(), version, idx.getCountry(),
                parsed.recordsScanned(), parsed.matchedLines(), txns.size(), parsed.unparsed(),
                apiResults, backendResults, new ArrayList<>(parsed.warnings()),
                parsed.range().start(), parsed.range().end(), parsed.range().seconds());
    }

    /** One module for a multi-module scan: its source coordinates + marker app. */
    public record ModuleSpec(String name, String sourceDir, String repo, String branch, String app) {}

    /** A re-openable source of the uploaded log — the servlet spools it, so it can be read once per flavour. */
    @FunctionalInterface
    public interface LogSource { InputStream open() throws IOException; }

    /**
     * Multi-module release test: correlate ONE uploaded log against every module. The expensive parse
     * runs once per distinct marker flavour (Mighty / SPL / SPL-Secure) and is reused across the
     * modules that share it — so N SPL sub-modules cost one parse, not N. Each API is still attributed
     * only to the module whose scope owns it. A module that fails to resolve carries an error.
     */
    public List<ModuleLogReport> analyzeModules(LogSource source, String filename, String version,
                                                String country, List<ModuleSpec> modules, List<String> dependencies) {
        List<String> deps = dependencies == null ? List.of() : dependencies;
        // 1. Resolve each module's index + flavour (the impact index is cached — cheap after Load).
        record Resolved(ModuleSpec spec, ImpactIndex idx, String app, boolean secure, String error) {}
        List<Resolved> resolved = new ArrayList<>(modules.size());
        for (ModuleSpec m : modules) {
            try {
                ImpactIndex idx = traceService.impactIndex(
                        new TraceRequest(null, version, null, m.sourceDir(), country, m.repo(), m.branch(), deps));
                String app = (m.app() == null || m.app().isBlank()) ? "Mighty" : m.app().trim();
                resolved.add(new Resolved(m, idx, app, idx.isCommandDispatch(), null));
            } catch (Exception e) {
                resolved.add(new Resolved(m, null, null, false, msg(e)));
            }
        }
        // 2. Parse the upload ONCE for ALL distinct flavours (app|secure) in a single read — a mixed
        //    Mighty + SPL upload is decoded and scanned once, not once per flavour.
        Map<String, Markers> flavourMarkers = new LinkedHashMap<>();
        for (Resolved r : resolved) {
            if (r.idx() == null) {
                continue;
            }
            flavourMarkers.putIfAbsent(r.app() + "|" + r.secure(), markersFor(r.app(), r.secure()));
        }
        Map<String, Parsed> byFlavour = new LinkedHashMap<>();
        Map<String, String> flavourError = new LinkedHashMap<>();
        if (!flavourMarkers.isEmpty()) {
            try (InputStream in = source.open()) {
                byFlavour = parseLogAll(in, filename, flavourMarkers);
            } catch (Exception e) {
                for (String key : flavourMarkers.keySet()) {
                    flavourError.put(key, msg(e));   // a whole-parse failure (e.g. bad gzip) fails every flavour
                }
            }
        }
        // 3. Correlate each module against its flavour's parsed log (cheap, in-memory).
        List<ModuleLogReport> out = new ArrayList<>(modules.size());
        for (Resolved r : resolved) {
            if (r.error() != null) {
                out.add(new ModuleLogReport(r.spec().name(), null, r.error()));
                continue;
            }
            String key = r.app() + "|" + r.secure();
            if (flavourError.containsKey(key)) {
                out.add(new ModuleLogReport(r.spec().name(), null, flavourError.get(key)));
                continue;
            }
            try {
                LogAnalysisReport rep = buildReport(r.idx(), byFlavour.get(key), version, r.secure(), true, List.of(), List.of(),
                        logRules.rulesFor(r.app(), r.secure()));
                out.add(new ModuleLogReport(r.spec().name(), rep, null));
            } catch (Exception e) {
                out.add(new ModuleLogReport(r.spec().name(), null, msg(e)));
            }
        }
        return out;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // --- input shape detection + extraction ---

    /** A single candidate log line (raw, or a Splunk _raw value) → parse + collect. */
    private void ingest(String s, List<LogLine> lines, int[] counters, Markers markers) {
        if (s == null) {
            return;
        }
        if (!markers.present(s)) {
            return;   // cheap pre-filter: skip the noise without touching the regex
        }
        LogLine parsed = null;
        try {
            parsed = parseLine(s, markers);
        } catch (RuntimeException ignore) {
            // a single malformed line must never abort the scan
        }
        if (parsed != null && parsed.correlationId() != null && !parsed.correlationId().isBlank()) {
            lines.add(parsed);
        } else {
            counters[1]++;
        }
    }

    private String detectFormat(String firstNonBlank, Markers markers) {
        String t = firstNonBlank.trim();
        if (t.startsWith("[") || t.startsWith("{")) {
            return "SPLUNK_JSON";
        }
        if (markers.present(t)) {
            return "RAW_LOG";   // the very first line is already an event
        }
        String low = t.toLowerCase();
        if (low.contains("_raw") || low.contains("_time")) {
            return "SPLUNK_CSV";   // a Splunk CSV header row
        }
        if (t.contains(",") && !t.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
            return "SPLUNK_CSV";   // comma-separated header that isn't an event timestamp
        }
        return "RAW_LOG";
    }

    private int csvRawIndex(String header) {
        List<String> cols = parseCsvLine(header);
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).trim().equalsIgnoreCase("_raw")) {
                return i;
            }
        }
        return -1;   // no _raw column — fall back to "any cell with a marker"
    }

    /** The _raw cell of a parsed CSV record, or any cell carrying a marker when there's no _raw column. */
    private String cellFrom(List<String> cells, int rawIdx, Markers markers) {
        String cell = null;
        if (rawIdx >= 0 && rawIdx < cells.size()) {
            cell = cells.get(rawIdx);
        } else {
            for (String c : cells) {
                if (markers.present(c)) {
                    cell = c;
                    break;
                }
            }
        }
        // A _raw event is one logical log line; flatten any embedded newlines so the
        // single-line parser sees the whole event (marker, path, ids, JSON) at once.
        return cell == null ? null : cell.replace('\n', ' ').replace('\r', ' ');
    }

    /** Parse one complete CSV record and ingest its _raw cell; blank separator lines are skipped. */
    private void emitCsvRecord(String record, int rawIdx, List<LogLine> lines, int[] counters, Markers markers) {
        // Fast path: a single-physical-line record (the norm for a _raw export) is split
        // directly, skipping the multi-line record normalisation and its extra allocations.
        List<String> cells;
        if (record.indexOf('\n') < 0) {
            cells = parseCsvLine(record);
        } else {
            List<List<String>> parsed = parseCsvRecords(record);
            if (parsed.isEmpty()) {
                return;
            }
            cells = parsed.get(0);
        }
        if (cells.size() == 1 && cells.get(0).isBlank()) {
            return;   // blank separator line
        }
        counters[0]++;
        ingest(cellFrom(cells, rawIdx, markers), lines, counters, markers);
    }

    private static int countChar(CharSequence s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) {
                n++;
            }
        }
        return n;
    }

    /**
     * RFC-4180 split of a CSV chunk into records (each a list of fields), honouring quoted
     * fields that contain commas, escaped {@code ""} quotes and embedded newlines — so a
     * multi-line Splunk {@code _raw} event stays a single field instead of being torn across
     * records. Blank lines between records are skipped.
     */
    private static List<List<String>> parseCsvRecords(String body) {
        String norm = body.replace("\r\n", "\n").replace('\r', '\n');
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean started = false;   // this record has some content (guards trailing blank lines)
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < norm.length() && norm.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                started = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                started = true;
            } else if (c == '\n') {
                if (started || field.length() > 0 || !row.isEmpty()) {
                    row.add(field.toString());
                    records.add(row);
                }
                row = new ArrayList<>();
                field.setLength(0);
                started = false;
            } else {
                field.append(c);
                started = true;
            }
        }
        if (started || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            records.add(row);
        }
        return records;
    }

    /** RFC-4180 CSV field split for one record: handles quoted fields and "" escapes. */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Pull the _raw event string out of a Splunk JSON export (array, {results:[]}, or NDJSON). */
    private List<String> extractJsonRaw(String content, List<String> warnings) {
        List<String> out = new ArrayList<>();
        ObjectMapper om = new ObjectMapper();
        try {
            collectRaw(om.readTree(content), out);
        } catch (Exception e) {
            for (String l : content.split("\n")) {   // fall back to NDJSON (one object per line)
                String t = l.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    collectRaw(om.readTree(t), out);
                } catch (Exception ignore) {
                    // skip an unparseable line
                }
            }
            if (out.isEmpty()) {
                warnings.add("Could not parse the JSON export: " + e.getMessage());
            }
        }
        return out;
    }

    private void collectRaw(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collectRaw(n, out));
            return;
        }
        if (node.isObject()) {
            if (node.get("results") != null && node.get("results").isArray()) {
                collectRaw(node.get("results"), out);
                return;
            }
            JsonNode raw = node.get("_raw");
            if (raw == null && node.get("result") != null) {
                raw = node.get("result").get("_raw");
            }
            if (raw != null && raw.isTextual()) {
                out.add(raw.asText());
            }
        }
    }

    // --- parsing ---

    private LogLine parseLine(String line, Markers markers) {
        if (markers.secure() && (line.indexOf("SPLAppLog") >= 0 || line.indexOf("SPLWSAppLog") >= 0)) {
            // SPL-Secure front-end line. The backend (SPLHostMessage) and any standard marker
            // fall through to the parser below — their shapes match LINE unchanged.
            return parseSecureFe(line);
        }
        Matcher m = LINE.matcher(line);
        if (!m.find()) {
            return null;
        }
        String marker = m.group(2);
        boolean fe;
        if (marker.equals(markers.fe())) {
            fe = true;
        } else if (marker.equals(markers.be())) {
            fe = false;
        } else {
            return null;   // a different application's marker — ignore
        }
        String ts = m.group(1);
        List<String> fields = new ArrayList<>();
        Matcher b = BRACKET.matcher(m.group(3));
        while (b.find()) {
            fields.add(b.group(1).trim());
        }
        // Locate the client version by its 9.x shape. An EMPTY version field means the
        // BASE release (default 0.0) — it must NOT drop the line. The correlation id is
        // the field right after the version; when the version is blank there's no pattern
        // to anchor on, so it's read from the trailing meta layout
        // [version][correlationId][platform][latency]. Tracing guarantees the front-end
        // and host lines carry the same correlation id. Latency is the "500ms"-shaped field.
        int n = fields.size();
        int vi = -1;
        String version = null;
        for (int i = 0; i < n; i++) {
            Matcher vm = VERSION_FIELD.matcher(fields.get(i));
            if (vm.matches()) {
                version = vm.group(1);
                vi = i;
                break;
            }
        }
        if (vi < 0) {
            version = "0.0";   // empty version field → base release
        }
        // The correlation id is a trace id (long hex). Match it by that shape when exactly
        // one field has it — robust regardless of position or a missing version. Otherwise
        // fall back to the field right after the version, or (for an empty base version)
        // the trailing [version][correlationId][platform][latency] layout.
        String corr = null;
        int hexCount = 0;
        for (String f : fields) {
            if (TRACE_ID.matcher(f).matches()) {
                hexCount++;
                corr = f;
            }
        }
        if (hexCount != 1) {
            corr = (vi >= 0)
                    ? (vi + 1 < n ? blankToNull(fields.get(vi + 1)) : null)
                    : (n >= 3 ? blankToNull(fields.get(n - 3)) : null);
        }
        String platform = (vi >= 0)
                ? (vi + 2 < n ? blankToNull(fields.get(vi + 2)) : null)
                : (n >= 2 ? blankToNull(fields.get(n - 2)) : null);
        Integer took = null;
        for (String f : fields) {
            Matcher tm = TOOK.matcher(f);
            if (tm.matches()) {
                took = Integer.valueOf(tm.group(1));
                break;
            }
        }
        boolean request = "Request".equalsIgnoreCase(m.group(5));
        String path = m.group(4);
        if (path == null || path.indexOf('/') < 0) {
            return null;   // defensive: the URL token must look like a path
        }
        String json = m.group(6);
        // Parse the payload as a JSON object and search the tree (any depth, any shape,
        // numeric or quoted, case-insensitive key) so it works for any API regardless of
        // where these fields nest. Falls back to a regex only if the JSON won't parse
        // (e.g. a truncated line).
        String code;
        String desc;
        String svc;
        if (request) {
            // A request carries no responseCode/description — only the service version is
            // needed, and the regex reads it reliably. Skip the (expensive) JSON parse; on a
            // 200MB export this halves the Jackson work since ~half the lines are requests.
            code = null;
            desc = null;
            svc = firstGroup(SVC_VERSION, json);
        } else {
            // Parse the response payload as JSON and search the tree (any depth/shape, numeric
            // or quoted, case-insensitive key) so it works for any API; fall back to a regex
            // only if the JSON won't parse (e.g. a truncated line).
            JsonNode tree = tryParseJson(json);
            if (tree != null) {
                // Backend/host lines may report the code under a config-declared key (e.g. resultCode /
                // errorcode); front-end lines always use responseCode. Rules are host/backend only.
                code = fe ? jsonFind(tree, "responseCode") : jsonFindAny(tree, markers.beCodeFields());
                desc = jsonFind(tree, "responseDescription");
                svc = jsonFind(tree, "serviceVersionNumber");
            } else {
                code = fe ? firstGroup(CODE, json) : firstCodeByFields(json, markers.beCodeFields());
                desc = firstGroup(DESC, json);
                svc = firstGroup(SVC_VERSION, json);
            }
        }
        // Retain the raw payload only for BACKEND RESPONSE lines, and only when custom code-field rules exist
        // (beCodeFields carries more than responseCode) — so a matching rule's field can win over a stray
        // responseCode later, without paying the memory for every line when no such rules are configured.
        String respJson = (!fe && !request && markers.beCodeFields().size() > 1) ? json : null;
        return new LogLine(ts, fe, request, version, corr, platform, took, path, code, desc, svc, respJson);
    }

    /**
     * Parse an SPL-Secure front-end line (SPLAppLog request / SPLWSAppLog response). These
     * carry no version field — the release is read from the SPLHostMessage (backend) line in
     * the same transaction — and put the correlation id in a "corrId|spanId|" prefix on the
     * request but in a TRACE-ID header on the response. The response usually carries no path (it
     * is tied to its request by corrId), so the front-end path is taken from the request line.
     * The response wraps the payload as
     * "status=…, body={json}, headers={…}"; the verdict is read from the body JSON's responseCode
     * (the same contract as the other apps). The HTTP status is deliberately ignored — a 200 can
     * wrap a business failure — so a body without a responseCode reads as indeterminate.
     */
    private LogLine parseSecureFe(String line) {
        Matcher m = SECURE_FE.matcher(line);
        if (!m.find()) {
            return null;
        }
        String ts = m.group(1);
        String prefix = m.group(2);
        String path = m.group(3);
        boolean request = "Request".equalsIgnoreCase(m.group(4));
        String rest = m.group(5);
        if (path != null && path.indexOf('/') < 0) {
            return null;   // a present path token must look like a path (absent → identified by corrId)
        }
        // corrId: request → the leading "corrId|spanId|" prefix; response → the TRACE-ID header.
        String corr = firstGroup(SECURE_CORR, prefix);
        if (corr == null) {
            corr = firstGroup(TRACE_ID_HDR, rest);
        }
        if (corr == null) {
            corr = firstGroup(SECURE_CORR, rest);   // last resort: the only 32-hex on the line
        }
        String code = null;
        String desc = null;
        String svc = null;
        if (request) {
            svc = firstGroup(SVC_VERSION, rest);
        } else {
            // "status=…, body={json}, headers={…}" — the body is the same response payload as the
            // other apps, so the verdict is read from it. The HTTP status is not consulted (a 200
            // can carry a failing responseCode); a body without a responseCode → indeterminate.
            String body = extractBraced(rest, "body");
            JsonNode tree = tryParseJson(body);
            if (tree != null) {
                code = jsonFind(tree, "responseCode");
                desc = jsonFind(tree, "responseDescription");
                svc = jsonFind(tree, "serviceVersionNumber");
            } else if (body != null) {
                code = firstGroup(CODE, body);
                desc = firstGroup(DESC, body);
                svc = firstGroup(SVC_VERSION, body);
            }
        }
        return new LogLine(ts, true, request, null, corr, null, null, path, code, desc, svc, null);
    }

    /**
     * The balanced <code>{…}</code> value of <code>key=…</code> in a string, tolerant of nested
     * braces and of braces inside JSON string values. Null if the key or its brace is absent.
     */
    private static String extractBraced(String s, String key) {
        if (s == null) {
            return null;
        }
        int k = s.indexOf(key + "=");
        if (k < 0) {
            return null;
        }
        int open = s.indexOf('{', k);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '"' && prev != '\\') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return s.substring(open, i + 1);
                }
            }
            prev = c;
        }
        return s.substring(open);   // truncated line — return what we have
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Parse the payload as JSON; null if it isn't a JSON object/array (e.g. truncated). */
    private static JsonNode tryParseJson(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return null;
        }
        try {
            return MAPPER.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Find a scalar field anywhere in the JSON tree (breadth-first so the shallowest
     * match wins), matching the key case-insensitively. Returns its text value (works
     * for {@code "0000"} and numeric {@code 0} alike), or null if absent.
     */
    private static String jsonFind(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            var direct = node.fields();
            while (direct.hasNext()) {
                var e = direct.next();
                if (e.getKey().equalsIgnoreCase(key) && e.getValue().isValueNode() && !e.getValue().isNull()) {
                    return e.getValue().asText();
                }
            }
            var nested = node.fields();
            while (nested.hasNext()) {
                String found = jsonFind(nested.next().getValue(), key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = jsonFind(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** First non-null value across the candidate code keys (host lines may use resultCode etc., not responseCode). */
    private static String jsonFindAny(JsonNode tree, List<String> fields) {
        List<String> keys = (fields == null || fields.isEmpty()) ? List.of("responseCode") : fields;
        for (String k : keys) {
            String v = jsonFind(tree, k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Regex fallback (truncated JSON): first candidate code key that matches, else null. */
    private static String firstCodeByFields(String json, List<String> fields) {
        List<String> keys = (fields == null || fields.isEmpty()) ? List.of("responseCode") : fields;
        for (String k : keys) {
            Pattern p = Pattern.compile("[\"']?" + Pattern.quote(k) + "[\"']?\\s*:\\s*[\"']?([0-9A-Za-z]+)",
                    Pattern.CASE_INSENSITIVE);
            String v = firstGroup(p, json);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * The bucket a non-success attempt is counted under in the failure breakdown: the actual
     * responseCode for an outright failure, or a readable label for the other failure modes.
     */
    private static String failureKey(LogStatus status, String code) {
        return switch (status) {
            case FAILED -> (code != null && !code.isBlank()) ? code.trim() : "FAILED (no code)";
            case PARTIAL -> "Partial (a backend failed)";
            case TIMEOUT -> "No response (timeout)";
            case INDETERMINATE -> "Unrecognised response";
            default -> status.name();
        };
    }

    /** Re-order a code→count map most-frequent first (stable for equal counts). */
    private static Map<String, Integer> sortByCountDesc(Map<String, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * Front-end success: an all-zeros business responseCode OR a business responseCode of "200" — some SPL
     * modules log success as {@code "responseCode": "200"} instead of all-zeros. This reads the BUSINESS
     * responseCode field only (not the HTTP status, per the design). Applies to both apps and the secure flavour.
     */
    private static boolean isSuccessCode(String code) {
        return code != null && (ALL_ZEROS.matcher(code).matches() || "200".equals(code.trim()));
    }

    /**
     * Backend success. Mighty/SPL backends log an all-zeros business responseCode, or "200" for the modules
     * that use that as their success code. The SPL-Secure backend is a downstream HTTP call whose responseCode
     * is "200" for OK — anything else (an all-zeros value included) is an error.
     */
    private static boolean isBackendSuccess(String code, boolean secure) {
        if (code == null) {
            return false;
        }
        String c = code.trim();
        return secure ? "200".equals(c) : (ALL_ZEROS.matcher(code).matches() || "200".equals(c));
    }

    // --- transaction assembly ---

    private Txn buildTxn(List<LogLine> group) {
        LogLine feReq = null;
        LogLine feResp = null;
        List<LogLine> beReq = new ArrayList<>();
        List<LogLine> beResp = new ArrayList<>();
        for (LogLine l : group) {
            if (l.fe()) {
                if (l.request()) {
                    feReq = l;
                } else {
                    feResp = l;
                }
            } else if (l.request()) {
                beReq.add(l);
            } else {
                beResp.add(l);
            }
        }
        // Pair each backend request with the next response on the same path; a
        // request with no response is a backend timeout, an orphan response still
        // contributes its outcome.
        Map<String, Deque<LogLine>> respByPath = new HashMap<>();
        for (LogLine rp : beResp) {
            respByPath.computeIfAbsent(rp.path(), k -> new ArrayDeque<>()).add(rp);
        }
        List<BackendCall> calls = new ArrayList<>();
        for (LogLine req : beReq) {
            Deque<LogLine> dq = respByPath.get(req.path());
            LogLine rp = dq != null ? dq.poll() : null;
            // serviceVersionNumber is in both Request and Response payloads — prefer the request.
            String svc = req.serviceVersion() != null ? req.serviceVersion()
                    : (rp != null ? rp.serviceVersion() : null);
            calls.add(rp != null
                    ? new BackendCall(req.path(), rp.tookMs(), rp.code(), rp.desc(), true, svc, rp.respJson())
                    : new BackendCall(req.path(), null, null, null, false, svc, null));
        }
        for (Deque<LogLine> leftover : respByPath.values()) {
            for (LogLine rp : leftover) {
                calls.add(new BackendCall(rp.path(), rp.tookMs(), rp.code(), rp.desc(), true, rp.serviceVersion(), rp.respJson()));
            }
        }

        LogLine anchor = feReq != null ? feReq : (feResp != null ? feResp
                : (!beReq.isEmpty() ? beReq.get(0) : (!beResp.isEmpty() ? beResp.get(0) : null)));
        String corr = anchor != null ? anchor.correlationId() : null;
        String ts = group.stream().map(LogLine::ts).min(Comparator.naturalOrder()).orElse(null);
        // Version normally comes from the anchor (front-end). SPL-Secure front-end lines carry
        // no version field, so fall back to any line that has one — the SPLHostMessage (backend)
        // lines carry the client release (e.g. 9.14). Mighty/SPL front-end lines always set a
        // version (base = 0.0), so this fallback never changes their behaviour.
        String version = anchor != null ? anchor.version() : null;
        if (version == null) {
            for (LogLine l : group) {
                if (l.version() != null) {
                    version = l.version();
                    break;
                }
            }
        }
        String platform = anchor != null ? anchor.platform() : null;
        String fePath = feReq != null ? feReq.path() : (feResp != null ? feResp.path() : null);
        return new Txn(corr, ts, version, platform, fePath, feReq, feResp, calls);
    }

    // --- per-API correlation ---

    private ApiLogResult correlate(ApiImpact api, List<Txn> txns, String version, Map<String, String> hosturls,
                                   boolean secure, LogRulesService.AppRules rules, double passThreshold) {
        List<Txn> matched = new ArrayList<>();
        for (Txn t : txns) {
            if (t.fePath() != null && feMatches(t.fePath(), api.api())) {
                matched.add(t);
            }
        }
        // N/A ("latest per API, else base") is not a concrete release — like the impact catalog,
        // it means "every release in scope", so the log lines are not restricted by version.
        boolean versionScoped = version != null && !version.isBlank() && !VersionResolver.isLatest(version);
        List<Txn> forVersion = new ArrayList<>();
        for (Txn t : matched) {
            if (!versionScoped || version.trim().equals(t.version())) {
                forVersion.add(t);
            }
        }

        if (forVersion.isEmpty()) {
            // Explain WHY it's not tested so the cause is visible: either no log
            // line's path matched this API at all, or lines matched but none carried
            // the requested release version (points at a path vs version-field issue).
            String note;
            if (matched.isEmpty()) {
                note = "No log line's front-end path matched this API — never tested. "
                        + "Looked for a path ending with '" + api.api() + "'.";
            } else if (versionScoped) {
                Set<String> seen = new TreeSet<>();
                for (Txn t : matched) {
                    seen.add(t.version() == null || t.version().isBlank() ? "(no version field read)" : t.version());
                }
                note = "Matched " + matched.size() + " log transaction(s) for this API, but none at client release "
                        + version.trim() + " — versions seen in the log: " + String.join(", ", seen) + ".";
            } else {
                note = "No log entry for this API — never tested.";
            }
            return new ApiLogResult(api.api(), api.operation(), api.resolvedRoute(), version,
                    LogStatus.NOT_TESTED, false, null, null, null, 0, 0, 0, null, null, note, List.of(), Map.of());
        }

        Txn latest = forVersion.stream().max(Comparator.comparing(Txn::ts)).orElseThrow();

        // Per-run front-end tally (attempts / passed / failed) across all runs — this drives the pass-rate
        // verdict below (not just the latest run), plus the failure breakdown and the dominant failing code.
        int success = 0;
        Map<String, Integer> failuresByCode = new LinkedHashMap<>();
        Map<String, Integer> failCodeTally = new LinkedHashMap<>();   // raw responseCode → count (for the headline)
        java.util.EnumSet<LogStatus> failModes = java.util.EnumSet.noneOf(LogStatus.class);
        for (Txn t : forVersion) {
            LogStatus fe = feStatus(t);
            if (fe == LogStatus.SUCCESS) {
                success++;
            } else {
                failModes.add(fe);
                String code = t.feResp() != null ? t.feResp().code() : null;
                failuresByCode.merge(failureKey(fe, code), 1, Integer::sum);
                if (code != null) {
                    failCodeTally.merge(code, 1, Integer::sum);
                }
            }
        }

        // One row per CHANGE / BAU flow (backend + service version), each resolved across ALL runs to the
        // latest run that covered it (so a choice branch exercised in an earlier run still counts).
        List<BackendCallResult> rows = coverageRows(api, forVersion, hosturls, secure, rules);

        // Verdict order (coverage first, then pass rate):
        //   1. A change flow was exercised but FAILED           → Failed
        //   2. Some change flow was never exercised (uncovered) → Partial (can't judge pass/fail until every
        //                                                          flow is tested at least once)
        //   3. Every change flow tested & passed → the FRONT-END PASS RATE decides: >= passThreshold → Success,
        //      else Failed (a few failures among many passes, or one late failure, doesn't blanket-fail it;
        //      a clean single failure mode — all timeouts / unreadable — keeps its specific status).
        // BAU rows never move the verdict.
        int feTotal = forVersion.size();
        int feFail = feTotal - success;
        boolean fePassed = feTotal == 0 || (double) success / feTotal >= passThreshold;
        String topFailCode = failCodeTally.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        List<String> failed = new ArrayList<>();
        List<String> notTested = new ArrayList<>();
        for (BackendCallResult b : rows) {
            if (b.bau() || b.status() == LogStatus.SKIPPED) {
                continue;   // BAU reuse, or skipped-by-config — never part of the change verdict
            }
            if (b.status() == LogStatus.NOT_TESTED) {
                notTested.add(flowLabel(b));
            } else if (Boolean.FALSE.equals(b.serviceVersionOk())) {
                failed.add(flowLabel(b) + " — called svc " + b.loggedServiceVersion());
            } else if (b.attempts() > 0 && (double) b.passed() / b.attempts() < passThreshold) {
                // Pass-rate tolerance, SAME as the front end (fePassed above): a backend flow fails the verdict
                // only when its pass-rate is below the bar — a few failures among many passes, or a single flaky
                // latest call, no longer fails an otherwise-passing flow. The row's own status pill still shows
                // the latest call's outcome, and its failing codes are always listed in the breakdown.
                failed.add(flowLabel(b) + " — " + (b.attempts() - b.passed()) + "/" + b.attempts()
                        + " failed (below " + (int) Math.round(passThreshold * 100) + "%)");
            }
        }

        LogStatus status;
        String note;
        if (success == 0 && feTotal > 0 && failModes.size() == 1
                && (failModes.contains(LogStatus.TIMEOUT) || failModes.contains(LogStatus.INDETERMINATE))) {
            // The front end itself never produced a usable response — every call the same clean way (all
            // timeouts / all unreadable). That's an FE-level failure, kept as its specific signal, ahead of
            // any backend-coverage judgement (the backends were never reached to be judged).
            status = failModes.iterator().next();
            note = status == LogStatus.TIMEOUT
                    ? "Front-end request logged but no response — timeout or server down."
                    : "Front-end " + UNRECOGNISED_RESPONSE + ".";
        } else if (!failed.isEmpty()) {
            status = LogStatus.FAILED;    // a change backend was exercised but failed / wrong version
            note = "Change flow failed: " + String.join("; ", failed) + ".";
        } else if (!notTested.isEmpty()) {
            status = LogStatus.PARTIAL;   // a required change flow was never exercised — coverage incomplete
            note = "Change flow not tested: " + String.join("; ", notTested) + ".";
        } else if (fePassed) {
            status = LogStatus.SUCCESS;   // all flows covered & passed, and the front end cleared the bar
            note = null;
        } else {
            status = LogStatus.FAILED;    // all flows covered, but the front-end pass rate is below the bar
            int passPct = (int) Math.round((double) success / feTotal * 100);
            int thresholdPct = (int) Math.round(passThreshold * 100);
            note = feFail + " of " + feTotal + " front-end call(s) failed — " + passPct
                    + "% passed, below the " + thresholdPct + "% pass threshold"
                    + (topFailCode != null ? "; most common code " + topFailCode : "") + ".";
        }

        // "Latest Result" column = the latest run's front-end response; the Status column carries the
        // aggregate verdict above, so the two can legitimately differ.
        String feCode = latest.feResp() != null ? latest.feResp().code() : null;
        String feDesc = latest.feResp() != null ? latest.feResp().desc() : null;
        Integer feTook = latest.feResp() != null ? latest.feResp().tookMs() : null;

        return new ApiLogResult(api.api(), api.operation(), api.resolvedRoute(), version,
                status, true, feTook, feCode, feDesc,
                forVersion.size(), success, forVersion.size() - success,
                latest.ts(), latest.correlationId(), note, rows, sortByCountDesc(failuresByCode));
    }

    /** Front-end outcome for one transaction (the API's own request/response). */
    private LogStatus feStatus(Txn t) {
        if (t.feResp() == null) {
            return LogStatus.TIMEOUT;
        }
        String code = t.feResp().code();
        if (code == null) {
            return LogStatus.INDETERMINATE;
        }
        return isSuccessCode(code) ? LogStatus.SUCCESS : LogStatus.FAILED;
    }

    private static String flowLabel(BackendCallResult b) {
        String route = b.flowRoute() != null && !b.flowRoute().isBlank() ? b.flowRoute() + " → " : "";
        return route + backendPathPart(b.backend())
                + (b.expectedServiceVersion() != null ? " (svc " + b.expectedServiceVersion() + ")" : "");
    }

    /**
     * One row per traced FLOW — each release route that calls a backend at a service version, keyed so two
     * routes on the SAME backend+version stay two flows (both must be covered). Coverage is by MATCHING-CALL
     * COUNT: a (backend, version) shared by K release flows needs K calls (across choice trace ids, or in one
     * unconditional trace) — otherwise the uncovered flows show Not Tested. Each flow carries its own failure
     * distribution (its bar); twins on one indistinguishable backend share a single pooled bar. BAU reuse is a
     * separate labelled row that never moves the verdict.
     */
    private List<BackendCallResult> coverageRows(ApiImpact api, List<Txn> forVersion,
                                                 Map<String, String> hosturls, boolean secure,
                                                 LogRulesService.AppRules rules) {
        List<BackendCallResult> out = new ArrayList<>();
        Collection<String> universe = api.backends();
        // Change flows grouped by backend → service version ("" = no version) → the routes that own that flow.
        Map<String, Map<String, List<String>>> flowsByBackend = new LinkedHashMap<>();
        for (com.uob.tracer.api.ChangeFlow f : api.changeFlows()) {
            flowsByBackend
                    .computeIfAbsent(f.backend(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(f.serviceVersion() == null ? "" : f.serviceVersion(), k -> new ArrayList<>())
                    .add(f.routeId());
        }
        for (String tb : api.backends()) {
            Map<String, List<String>> byVer = flowsByBackend.getOrDefault(tb, Map.of());
            List<String> bau = bauVersions(api.backendVersions().get(tb), api.changeBackendVersions().get(tb));
            // Host response-code rule for this backend (matched on its hosturl): custom code field / success, or skip.
            LogRulesService.Rule rule = rules == null ? null : rules.ruleFor(matchPath(tb, hosturls));
            // Match a specific version strictly only when the URL carries several distinct version behaviours
            // (to keep them apart); with one version the log line may omit it, so match by URL alone.
            Set<String> distinct = new java.util.LinkedHashSet<>();
            byVer.keySet().forEach(v -> { if (!v.isEmpty()) distinct.add(v); });
            distinct.addAll(bau);
            boolean strict = distinct.size() > 1;
            for (var e : byVer.entrySet()) {
                String ver = e.getKey().isEmpty() ? null : e.getKey();
                out.addAll(flowRows(tb, ver, e.getValue(), forVersion, universe, hosturls, secure, strict, rule));
            }
            if (byVer.isEmpty() && bau.isEmpty()) {
                out.addAll(flowRows(tb, null, List.of("(flow)"), forVersion, universe, hosturls, secure, false, rule));
            }
            for (String bv : bau) {
                out.add(bauRow(tb, bv, forVersion, universe, hosturls, secure, strict, rule));
            }
        }
        return out;
    }

    /** All calls matching (backend, version) across every transaction, latest first. */
    private List<CallHit> flowHits(String tb, String ver, List<Txn> forVersion,
                                   Collection<String> universe, Map<String, String> hosturls, boolean strict) {
        List<CallHit> hits = new ArrayList<>();
        for (Txn t : forVersion) {
            for (BackendCall c : matchesInTxn(t.calls(), tb, ver, universe, hosturls, strict)) {
                hits.add(new CallHit(t.ts(), c));
            }
        }
        hits.sort(Comparator.comparing(CallHit::ts).reversed());
        return hits;
    }

    /**
     * K flow rows for one (backend, version) group. K = the number of release routes on it; N = matching calls
     * seen. The first min(N,K) rows are covered (badged by the latest calls), the rest Not Tested. The pooled
     * failure distribution rides the first row — for a solo flow that's just its own bar; for twins it's the one
     * shared bar (we can't attribute an indistinguishable call to route A vs B).
     */
    private List<BackendCallResult> flowRows(String tb, String ver, List<String> routeIds, List<Txn> forVersion,
                                             Collection<String> universe, Map<String, String> hosturls,
                                             boolean secure, boolean strict, LogRulesService.Rule rule) {
        // The expected service version for this flow. A rule's explicit svcVersion (user-asserted) overrides the
        // scan-derived one — for backends whose version is set in Java, where the scan derives none — but only
        // when NOT strict (strict already keeps several distinct versions apart per group). A call is attributed
        // to this flow only if its logged version matches expVer (or it logged none) — see matchesInTxn — so a
        // different release's call at a different version in the same (version-agnostic) log doesn't cover it.
        String expVer = strict ? ver : expectedSvc(ver, rule);
        List<CallHit> hits = applyRuleCode(flowHits(tb, expVer, forVersion, universe, hosturls, strict), rule);
        int n = hits.size();
        boolean skip = rule != null && rule.skip();
        int passed = 0;
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (CallHit h : hits) {
            LogStatus st = beStatus(h.call(), secure, rule);
            if (st == LogStatus.SUCCESS) {
                passed++;
            } else if (st != LogStatus.SKIPPED) {
                failures.merge(failureKey(st, h.call().code()), 1, Integer::sum);
            }
        }
        int failedTotal = skip ? 0 : n - passed;   // a skipped backend contributes no pass/fail
        Map<String, Integer> dist = sortByCountDesc(failures);
        // The version the flow was actually called at: any attributed call that logged one. Post-enforcement
        // those are all the expected version (mismatches are excluded), so the first non-null is representative
        // — this way a version-less latest call (e.g. an error response that omits serviceVersionNumber) doesn't
        // hide the fact that the flow WAS exercised at the expected version.
        String flowVer = hits.stream().map(h -> h.call().serviceVersion())
                .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
        List<BackendCallResult> rows = new ArrayList<>();
        int k = routeIds.size();
        for (int i = 0; i < k; i++) {
            String route = routeIds.get(i);
            int a = i == 0 ? n : 0;
            int p = i == 0 ? passed : 0;
            int f = i == 0 ? failedTotal : 0;
            Map<String, Integer> fbc = i == 0 ? dist : Map.of();
            if (i < n) {
                BackendCall c = hits.get(i).call();   // assign the latest calls to the flow rows
                LogStatus st = beStatus(c, secure, rule);
                boolean timedOut = st == LogStatus.TIMEOUT;
                String desc = timedOut ? null : c.desc();
                if (st == LogStatus.INDETERMINATE && (desc == null || desc.isBlank())) {
                    desc = UNRECOGNISED_RESPONSE;
                }
                // Use this call's version if it logged one, else the flow's representative version.
                String loggedVer = c.serviceVersion() != null && !c.serviceVersion().isBlank()
                        ? c.serviceVersion() : flowVer;
                rows.add(new BackendCallResult(tb, c.path(), st,
                        timedOut ? null : c.tookMs(), timedOut ? null : c.code(), desc,
                        expVer, loggedVer, versionOk(expVer, loggedVer), false, route, a, p, f, fbc));
            } else {
                // A skipped backend is never "not tested" (it must not turn the API Partial) — mark it Skipped.
                rows.add(new BackendCallResult(tb, null, skip ? LogStatus.SKIPPED : LogStatus.NOT_TESTED, null, null, null,
                        expVer, null, null, false, route, a, p, f, fbc));
            }
        }
        return rows;
    }

    /** A single BAU-reuse row (latest call + its distribution), labelled BAU and never in the verdict. */
    private BackendCallResult bauRow(String tb, String ver, List<Txn> forVersion, Collection<String> universe,
                                     Map<String, String> hosturls, boolean secure, boolean strict, LogRulesService.Rule rule) {
        List<CallHit> hits = applyRuleCode(flowHits(tb, ver, forVersion, universe, hosturls, strict), rule);
        int n = hits.size();
        if (n == 0) {
            return new BackendCallResult(tb, null, LogStatus.NOT_TESTED, null, null, null,
                    ver, null, null, true, null, 0, 0, 0, Map.of());
        }
        int passed = 0;
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (CallHit h : hits) {
            LogStatus st = beStatus(h.call(), secure, rule);
            if (st == LogStatus.SUCCESS) {
                passed++;
            } else if (st != LogStatus.SKIPPED) {
                failures.merge(failureKey(st, h.call().code()), 1, Integer::sum);
            }
        }
        BackendCall c = hits.get(0).call();
        LogStatus st = beStatus(c, secure, rule);
        boolean timedOut = st == LogStatus.TIMEOUT;
        String desc = timedOut ? null : c.desc();
        if (st == LogStatus.INDETERMINATE && (desc == null || desc.isBlank())) {
            desc = UNRECOGNISED_RESPONSE;
        }
        return new BackendCallResult(tb, c.path(), st, timedOut ? null : c.tookMs(), timedOut ? null : c.code(), desc,
                ver, c.serviceVersion(), null, true, null, n, passed, n - passed, sortByCountDesc(failures));
    }

    /** Every call in a transaction matching (backend, version) — all of them (a trace may hit a backend twice). */
    private List<BackendCall> matchesInTxn(List<BackendCall> calls, String tracedBackend, String expectedVersion,
                                           Collection<String> candidates, Map<String, String> hosturls, boolean strict) {
        String matchKey = matchPath(tracedBackend, hosturls);
        List<BackendCall> out = new ArrayList<>();
        for (BackendCall c : calls) {
            if (!backendMatches(matchKey, c.path())) {
                continue;
            }
            if (moreSpecificMatch(candidates, tracedBackend, c.path(), hosturls)) {
                continue;   // a longer traced backend also ends this path — it owns the call
            }
            if (strict) {
                if (expectedVersion != null && Boolean.TRUE.equals(versionOk(expectedVersion, c.serviceVersion()))) {
                    out.add(c);   // strict: only this exact version behaviour
                }
            } else if (expectedVersion == null || c.serviceVersion() == null || c.serviceVersion().isBlank()
                    || Boolean.TRUE.equals(versionOk(expectedVersion, c.serviceVersion()))) {
                // One expected version: match by path. But a call that logged a DIFFERENT version belongs to
                // ANOTHER release captured in the same (version-agnostic) log — attributing it here would falsely
                // "cover" this flow. So drop a version-mismatched call; the flow then reads Not Tested (its own
                // version was never exercised), not a misleading Success on a foreign-version call. A call that
                // logged no version is kept (older lines may omit it, and there's nothing to contradict).
                out.add(c);
            }
        }
        return out;
    }

    /** Backend-only correlation: read the MightyHostMessage calls that hit this backend. */
    private BackendLogResult correlateBackend(String backend, List<Txn> txns, String version, String expectedVersion,
                                              Collection<String> candidates, Map<String, String> hosturls, boolean secure,
                                              boolean strict, boolean bau, LogRulesService.AppRules rules) {
        LogRulesService.Rule rule = rules == null ? null : rules.ruleFor(matchPath(backend, hosturls));
        // A rule may assert the expected version (backend versioned in Java the scan can't read), else the scan's.
        String expVer = strict ? expectedVersion : expectedSvc(expectedVersion, rule);
        // N/A means "every release in scope" — don't restrict the host lines by a concrete version.
        boolean versionScoped = version != null && !version.isBlank() && !VersionResolver.isLatest(version);
        List<BackendHit> hits = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        boolean anyPathMatch = false;
        for (Txn t : txns) {
            // Match by URL AND service version together (prefer the svc-matching call),
            // letting "longest match wins" keep /bfs/… and /bp/bfs/… apart. When strict (a backend with
            // several service-version behaviours), match this row's version only — never a different one.
            BackendCall c = pickCall(t.calls(), backend, expVer, candidates, hosturls, strict);
            if (c == null) {
                continue;
            }
            anyPathMatch = true;
            seen.add(t.version() == null || t.version().isBlank() ? "(no version field read)" : t.version());
            if (!versionScoped || version.trim().equals(t.version())) {
                hits.add(new BackendHit(t, applyRuleCode(c, rule)));   // rule field wins over a stray responseCode
            }
        }

        if (hits.isEmpty()) {
            String note;
            if (!anyPathMatch) {
                note = "No host-message (backend) line matched this backend — never tested. "
                        + "Looked for a path ending with '" + backendPathPart(matchPath(backend, hosturls)) + "'.";
            } else if (versionScoped) {
                note = "Matched backend call(s), but none at client release " + version.trim()
                        + " — versions seen in the log: " + String.join(", ", seen) + ".";
            } else {
                note = "No backend call observed — never tested.";
            }
            if (bau) {
                note = "BAU – no logs found"
                        + (expectedVersion != null ? " for service version " + expectedVersion : "")
                        + " (unchanged route — not part of this release).";
            }
            LogStatus emptyStatus = (rule != null && rule.skip()) ? LogStatus.SKIPPED : LogStatus.NOT_TESTED;
            return new BackendLogResult(backend, emptyStatus, false, null, null, null, 0, 0, 0, null, null, note,
                    expVer, null, null, Map.of(), bau);
        }

        BackendHit latest = hits.stream().max(Comparator.comparing(h -> h.txn().ts())).orElseThrow();
        int success = 0;
        Map<String, Integer> failuresByCode = new LinkedHashMap<>();
        for (BackendHit h : hits) {
            LogStatus st = beStatus(h.call(), secure, rule);
            if (st == LogStatus.SUCCESS) {
                success++;
            } else if (st != LogStatus.SKIPPED) {
                failuresByCode.merge(failureKey(st, h.call().code()), 1, Integer::sum);
            }
        }
        LogStatus status = beStatus(latest.call(), secure, rule);
        // The version the backend was called at: the latest call's if it logged one, else any call that did
        // (an error response may omit serviceVersionNumber, but a sibling success logged the real version).
        String logged = latest.call().serviceVersion() != null && !latest.call().serviceVersion().isBlank()
                ? latest.call().serviceVersion()
                : hits.stream().map(h -> h.call().serviceVersion())
                    .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
        Boolean svcOk = versionOk(expVer, logged);
        String note = switch (status) {
            case SUCCESS -> null;
            case SKIPPED -> "Skipped by config — excluded from the verdict (host response-code rule).";
            case TIMEOUT -> "Backend request logged but no response.";
            case INDETERMINATE -> "Backend " + UNRECOGNISED_RESPONSE;
            default -> "Backend responseCode " + latest.call().code()
                    + (latest.call().desc() != null ? " (" + latest.call().desc() + ")." : ".");
        };
        if (bau) {
            note = "BAU – " + (latest.call().code() != null ? latest.call().code() : status.name())
                    + (latest.call().desc() != null ? " (" + latest.call().desc() + ")" : "")
                    + " (unchanged route — not part of this release).";
        } else if (status != LogStatus.SKIPPED && Boolean.FALSE.equals(svcOk)) {
            note = (note == null ? "" : note + " ") + "Service version mismatch: called " + logged
                    + ", expected " + expVer + ".";
        }
        int failureCount = status == LogStatus.SKIPPED ? 0 : hits.size() - success;   // skipped = neither pass nor fail
        return new BackendLogResult(backend, status, true, latest.call().tookMs(),
                latest.call().code(), latest.call().desc(),
                hits.size(), success, failureCount, latest.txn().ts(), latest.txn().correlationId(), note,
                expVer, logged, (bau || status == LogStatus.SKIPPED) ? null : svcOk,
                sortByCountDesc(failuresByCode), bau);
    }

    /** true if the logged version is one of the expected (possibly "2.2 / 3.3"); null if either is absent. */
    /** The expected service version to validate/match against: a rule's explicit {@code svcVersion} (user-asserted,
     *  for backends whose version is set in Java the scan can't read) overrides the scan-derived version; else it. */
    private static String expectedSvc(String derived, LogRulesService.Rule rule) {
        return (rule != null && !rule.svcVersion().isBlank()) ? rule.svcVersion() : derived;
    }

    private static Boolean versionOk(String expected, String logged) {
        if (expected == null || expected.isBlank() || logged == null || logged.isBlank()) {
            return null;
        }
        for (String v : expected.split(" / ")) {
            if (v.trim().equals(logged.trim())) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    private static String joinVersions(String existing, String add) {
        for (String v : existing.split(" / ")) {
            if (v.equals(add)) {
                return existing;
            }
        }
        return existing + " / " + add;
    }

    /** The value of one JSON field in a response payload (any depth), or null. Regex fallback if JSON won't parse. */
    private static String jsonFieldOf(String json, String field) {
        if (json == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode tree = tryParseJson(json);
        return tree != null ? jsonFind(tree, field.trim()) : firstCodeByFields(json, List.of(field.trim()));
    }

    /**
     * When a rule matches this backend host and names a {@code codeField}, that field is the AUTHORITATIVE code
     * for the host and must win over the generic {@code responseCode} — which is resolved first at parse time and
     * could otherwise be a stray/nested {@code responseCode} elsewhere in the payload. Returns a copy of the call
     * with the rule field's value when it is present; otherwise the call is unchanged.
     */
    private static BackendCall applyRuleCode(BackendCall c, LogRulesService.Rule rule) {
        if (c == null || rule == null || rule.codeField().isBlank() || c.respJson() == null) {
            return c;
        }
        String v = jsonFieldOf(c.respJson(), rule.codeField());
        return v == null ? c
                : new BackendCall(c.path(), c.tookMs(), v, c.desc(), c.hasResponse(), c.serviceVersion(), c.respJson());
    }

    /** Re-code every hit's call from the matched rule's field (precedence over responseCode) — no-op without a rule field. */
    private static List<CallHit> applyRuleCode(List<CallHit> hits, LogRulesService.Rule rule) {
        if (rule == null || rule.codeField().isBlank()) {
            return hits;
        }
        List<CallHit> out = new ArrayList<>(hits.size());
        for (CallHit h : hits) {
            out.add(new CallHit(h.ts(), applyRuleCode(h.call(), rule)));
        }
        return out;
    }

    private LogStatus beStatus(BackendCall c, boolean secure, LogRulesService.Rule rule) {
        if (rule != null && rule.skip()) {
            return LogStatus.SKIPPED;   // configured out of the verdict — outcome not evaluated
        }
        if (!c.hasResponse()) {
            return LogStatus.TIMEOUT;
        }
        if (c.code() == null) {
            return LogStatus.INDETERMINATE;
        }
        boolean ok = (rule != null && !rule.successCodes().isEmpty())
                ? rule.successCodes().stream().anyMatch(v -> v != null && v.trim().equalsIgnoreCase(c.code().trim()))
                : isBackendSuccess(c.code(), secure);
        return ok ? LogStatus.SUCCESS : LogStatus.FAILED;
    }

    /** Front-end path match: the log path ends with (or contains) the traced controller path. */
    private boolean feMatches(String logPath, String apiPath) {
        if (apiPath == null || apiPath.isBlank()) {
            return false;
        }
        String a = apiPath.trim();
        return logPath.endsWith(a) || logPath.contains(a);
    }

    /**
     * Backend match: compare the path portions. The traced backend may carry a
     * {{baseUrl}}/scheme+host prefix and the log path a deployment context prefix
     * (e.g. /mty-banking-01/...), so reduce both to their path tail and check that
     * the observed path ends with the traced backend path.
     */
    private boolean backendMatches(String tracedBackend, String observedPath) {
        if (tracedBackend == null || observedPath == null) {
            return false;
        }
        String tbPath = backendPathPart(tracedBackend.trim());
        String op = observedPath.trim();
        if (tbPath.isEmpty() || op.isEmpty()) {
            return false;
        }
        // The traced backend keeps a {{placeholder}} (e.g. {{dge.bfs.XX}}) that is
        // stripped to the path tail; in the log that placeholder is resolved to a host
        // + context of ANY length, so the observed path simply ENDS WITH the traced tail
        // (segment-aligned by the leading '/'). Telling apart a short path from a longer
        // one that shares the suffix (/bfs/… vs /bp/bfs/…) is handled by "longest match
        // wins" in pickCall, so no fixed segment-count assumption is needed here.
        return op.endsWith(tbPath);
    }

    /**
     * Pick the backend call that matches a traced backend by URL <b>and</b> service
     * version together: among the path-matching calls, prefer the one whose
     * serviceVersionNumber equals the expected version (so similar paths like
     * {@code /bfs/…} vs {@code /bp/bfs/…}, called at different versions, aren't
     * confused). Falls back to the first path match (its svc, if different, is flagged).
     */
    private BackendCall pickCall(List<BackendCall> calls, String tracedBackend, String expectedVersion,
                                 Collection<String> candidates, Map<String, String> hosturls) {
        return pickCall(calls, tracedBackend, expectedVersion, candidates, hosturls, false);
    }

    /**
     * {@code strictVersion}: when a backend has more than one service-version behaviour (a release change AND
     * a BAU reuse of the same URL), each row must match ITS version — so a 2.0 (BAU) call is never mistaken for
     * the 4.0 (change) row. Strict rows return null (→ not tested / no logs) rather than falling back to a
     * path-only match at the wrong version.
     */
    private BackendCall pickCall(List<BackendCall> calls, String tracedBackend, String expectedVersion,
                                 Collection<String> candidates, Map<String, String> hosturls, boolean strictVersion) {
        String matchKey = matchPath(tracedBackend, hosturls);
        BackendCall pathMatch = null;
        for (BackendCall c : calls) {
            if (!backendMatches(matchKey, c.path())) {
                continue;
            }
            if (moreSpecificMatch(candidates, tracedBackend, c.path(), hosturls)) {
                continue;   // a longer traced backend also ends this path — it owns the call
            }
            if (expectedVersion != null && Boolean.TRUE.equals(versionOk(expectedVersion, c.serviceVersion()))) {
                return c;   // URL and svc both match — the precise call
            }
            if (pathMatch == null && !strictVersion) {
                // Backend-only report: the user picked THIS backend, so a path match at a different version is
                // still worth surfacing (flagged as a version mismatch) rather than hidden as "not tested".
                pathMatch = c;
            }
        }
        return pathMatch;
    }

    /** True if some OTHER candidate backend has a longer path tail that also ends the observed path. */
    private boolean moreSpecificMatch(Collection<String> candidates, String tracedBackend, String observedPath,
                                      Map<String, String> hosturls) {
        if (candidates == null) {
            return false;
        }
        int myLen = backendPathPart(matchPath(tracedBackend, hosturls)).length();
        for (String other : candidates) {
            if (other.equals(tracedBackend)) {
                continue;
            }
            String otherPath = matchPath(other, hosturls);
            if (backendPathPart(otherPath).length() > myLen && backendMatches(otherPath, observedPath)) {
                return true;
            }
        }
        return false;
    }

    /** The path to match a backend against the log: its "hosturl" (what the host logs) if known, else the api value. */
    private static String matchPath(String tracedBackend, Map<String, String> hosturls) {
        if (hosturls != null) {
            String h = hosturls.get(tracedBackend);
            if (h != null && !h.isBlank()) {
                return h;
            }
        }
        return tracedBackend;
    }

    /** The path tail of a backend: strip a leading {{...}} placeholder, scheme+host and query. */
    private static String backendPathPart(String backend) {
        String s = stripLeadingPlaceholder(backend);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = slash >= 0 ? s.substring(slash) : "";
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        if (!s.isEmpty() && !s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    /**
     * Strip a leading Camel property placeholder {@code {{...}}} — matching BALANCED {@code {{}}} pairs so a
     * nested default like {@code {{am5.mock.url:{{am5.p.mfa.url}}}}${...}} loses the whole prefix and keeps
     * the path tail. A first-{@code }}} scan would stop inside the nesting and leave a dangling {@code }}}.
     * Only a fully-balanced leading placeholder is removed; anything else is returned unchanged.
     */
    private static String stripLeadingPlaceholder(String v) {
        if (v == null || !v.startsWith("{{")) {
            return v;
        }
        int depth = 0;
        for (int i = 0; i < v.length(); ) {
            if (v.startsWith("{{", i)) {
                depth++;
                i += 2;
            } else if (v.startsWith("}}", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return v.substring(i);
                }
            } else {
                i++;
            }
        }
        return v;   // unbalanced / malformed — leave as-is
    }

    /** Split a " / "-joined version list into trimmed, non-empty parts. */
    private static List<String> splitList(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : v.split(" / ")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Versions present in the full footprint ("4.0 / 2.0") but NOT in the release change ("4.0") → BAU ("2.0"). */
    private static List<String> bauVersions(String allVer, String changeVer) {
        if (allVer == null || allVer.isBlank()) {
            return List.of();
        }
        java.util.Set<String> change = new java.util.HashSet<>();
        if (changeVer != null) {
            for (String c : changeVer.split(" / ")) {
                change.add(c.trim());
            }
        }
        List<String> out = new ArrayList<>();
        for (String v : allVer.split(" / ")) {
            String tv = v.trim();
            if (!tv.isEmpty() && !change.contains(tv)) {
                out.add(tv);
            }
        }
        return out;
    }

    // --- internal records ---

    private record LogLine(String ts, boolean fe, boolean request, String version,
                           String correlationId, String platform, Integer tookMs,
                           String path, String code, String desc, String serviceVersion, String respJson) {
    }

    /** {@code respJson} = the raw backend response payload, retained (only when custom code-field rules exist)
     *  so a matching rule's code field can be re-read with precedence over the generic responseCode. */
    private record BackendCall(String path, Integer tookMs, String code, String desc,
                               boolean hasResponse, String serviceVersion, String respJson) {
    }

    private record BackendHit(Txn txn, BackendCall call) {
    }

    /** A backend call observed in a transaction at a timestamp — for flow coverage counting. */
    private record CallHit(String ts, BackendCall call) {
    }

    /**
     * The front-end and backend log markers for the selected application. For the auto-detected
     * SPL-Secure flavour ({@code secure=true}) the front-end uses a different logger
     * (SPLAppLog request / SPLWSAppLog response) while the backend stays SPLHostMessage.
     */
    private record Markers(String fe, String be, boolean secure, List<String> beCodeFields) {
        /** Cheap pre-filter: does this candidate line carry any marker we care about? */
        boolean present(String s) {
            if (s.indexOf(fe) >= 0 || s.indexOf(be) >= 0) {
                return true;
            }
            // Additive: an SPL-Secure source also uses these front-end loggers.
            return secure && (s.indexOf("SPLAppLog") >= 0 || s.indexOf("SPLWSAppLog") >= 0);
        }
    }

    private record Txn(String correlationId, String ts, String version, String platform,
                       String fePath, LogLine feReq, LogLine feResp, List<BackendCall> calls) {
    }

}
