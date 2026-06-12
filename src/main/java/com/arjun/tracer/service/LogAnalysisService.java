package com.arjun.tracer.service;

import com.arjun.tracer.api.ApiImpact;
import com.arjun.tracer.api.ApiLogResult;
import com.arjun.tracer.api.BackendCallResult;
import com.arjun.tracer.api.ImpactIndex;
import com.arjun.tracer.api.LogAnalysisReport;
import com.arjun.tracer.api.LogStatus;
import com.arjun.tracer.api.TraceRequest;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    public LogAnalysisService(RouteTraceService traceService) {
        this.traceService = traceService;
    }

    // timestamp [thread] LEVEL [marker][...fields...]-/path -Dir - json
    // The bracket fields after the marker are located by PATTERN, not by fixed
    // position — environments differ in how many/which fields they emit, so the
    // version is found by its 9.18 shape and the latency by its 500ms shape.
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}[.:]\\d{2}[.:]\\d{2}[.:]\\d{1,3})\\s+"
                    + "\\[[^\\]]*\\]\\s+\\S+\\s+"
                    + "\\[(MightyMessage|MightyHostMessage)\\]"
                    + "((?:\\[[^\\]]*\\])+?)-(\\S+)\\s+-\\s*\\[?(Request|Response)\\]?\\s*-\\s*(.*)$");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern CODE = Pattern.compile("\"responseCode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DESC = Pattern.compile("\"responseDescription\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TOOK = Pattern.compile("(\\d+)\\s*ms");
    // A client release version: 9.18, 9.4, R9.14, 9.4.1 — at least one dot so plain
    // numeric fields (session ids etc.) are never mistaken for a version.
    private static final Pattern VERSION_FIELD = Pattern.compile("R?(\\d+(?:\\.\\d+)+)");
    private static final Pattern ALL_ZEROS = Pattern.compile("0+");

    /** Analyse a raw output log. Caller owns the stream. */
    public LogAnalysisReport analyze(InputStream raw, String filename, String version,
                                     String country, String sourceDir, List<String> selectedApis)
            throws IOException {
        InputStream in = (filename != null && filename.toLowerCase().endsWith(".gz"))
                ? new GZIPInputStream(raw) : raw;

        int[] counters = new int[2];   // [0] = records scanned, [1] = marked-but-unparsed
        List<LogLine> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String detected = "RAW_LOG";

        // The upload is one of three shapes — a raw output log, or a Splunk export
        // (CSV or JSON) of the generated query. A Splunk export carries the original
        // log line in its _raw field, so every shape ultimately yields the same
        // MightyMessage/MightyHostMessage strings, which feed the one line parser.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String firstNonBlank = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) { firstNonBlank = line; break; }
                counters[0]++;
            }
            if (firstNonBlank == null) {
                detected = "EMPTY";
            } else {
                detected = detectFormat(firstNonBlank);
                switch (detected) {
                    case "SPLUNK_CSV" -> {
                        int rawIdx = csvRawIndex(firstNonBlank);   // header row consumed
                        while ((line = r.readLine()) != null) {
                            counters[0]++;
                            ingest(csvCell(line, rawIdx), lines, counters);
                        }
                    }
                    case "SPLUNK_JSON" -> {
                        StringBuilder sb = new StringBuilder(firstNonBlank);
                        while ((line = r.readLine()) != null) {
                            sb.append('\n').append(line);
                        }
                        for (String event : extractJsonRaw(sb.toString(), warnings)) {
                            counters[0]++;
                            ingest(event, lines, counters);
                        }
                    }
                    default -> {
                        counters[0]++;
                        ingest(firstNonBlank, lines, counters);
                        while ((line = r.readLine()) != null) {
                            counters[0]++;
                            ingest(line, lines, counters);
                        }
                    }
                }
            }
        }

        if (lines.isEmpty()) {
            warnings.add("No MightyMessage/MightyHostMessage lines found in the upload (detected "
                    + detected + "). Check the file is the raw output log or a Splunk export of the query.");
        }

        // Group into transactions by correlation id (FE + BE share the id).
        Map<String, List<LogLine>> byCorr = new LinkedHashMap<>();
        for (LogLine l : lines) {
            byCorr.computeIfAbsent(l.correlationId(), k -> new ArrayList<>()).add(l);
        }
        List<Txn> txns = new ArrayList<>(byCorr.size());
        for (List<LogLine> group : byCorr.values()) {
            txns.add(buildTxn(group));
        }

        // Footprint (controller path + traced backends per API) for this release.
        ImpactIndex idx = traceService.impactIndex(new TraceRequest(null, version, null, sourceDir, country));
        List<ApiLogResult> results = new ArrayList<>();
        for (ApiImpact api : idx.getApis()) {
            if (selectedApis != null && !selectedApis.isEmpty() && !selectedApis.contains(api.api())) {
                continue;
            }
            results.add(correlate(api, txns, version));
        }

        return new LogAnalysisReport(detected, version, idx.getCountry(),
                counters[0], lines.size(), txns.size(), counters[1], results, warnings);
    }

    // --- input shape detection + extraction ---

    /** A single candidate log line (raw, or a Splunk _raw value) → parse + collect. */
    private void ingest(String s, List<LogLine> lines, int[] counters) {
        if (s == null) {
            return;
        }
        if (s.indexOf("[MightyMessage]") < 0 && s.indexOf("[MightyHostMessage]") < 0) {
            return;   // cheap pre-filter: skip the noise without touching the regex
        }
        LogLine parsed = null;
        try {
            parsed = parseLine(s);
        } catch (RuntimeException ignore) {
            // a single malformed line must never abort the scan
        }
        if (parsed != null && parsed.correlationId() != null && !parsed.correlationId().isBlank()) {
            lines.add(parsed);
        } else {
            counters[1]++;
        }
    }

    private String detectFormat(String firstNonBlank) {
        String t = firstNonBlank.trim();
        if (t.startsWith("[") || t.startsWith("{")) {
            return "SPLUNK_JSON";
        }
        boolean marker = t.contains("[MightyMessage]") || t.contains("[MightyHostMessage]");
        if (marker) {
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

    private String csvCell(String line, int rawIdx) {
        List<String> cells = parseCsvLine(line);
        if (rawIdx >= 0 && rawIdx < cells.size()) {
            return cells.get(rawIdx);
        }
        for (String c : cells) {
            if (c.contains("[MightyMessage]") || c.contains("[MightyHostMessage]")) {
                return c;
            }
        }
        return null;
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

    private LogLine parseLine(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.find()) {
            return null;
        }
        String ts = m.group(1);
        boolean fe = "MightyMessage".equals(m.group(2));
        List<String> fields = new ArrayList<>();
        Matcher b = BRACKET.matcher(m.group(3));
        while (b.find()) {
            fields.add(b.group(1).trim());
        }
        // Locate the version by pattern; the correlation id is the field right after
        // it (per the [...][version][correlationId][platform]... layout), and the
        // latency is whichever field is shaped like "500ms".
        int vi = -1;
        String version = null;
        for (int i = 0; i < fields.size(); i++) {
            Matcher vm = VERSION_FIELD.matcher(fields.get(i));
            if (vm.matches()) {
                version = vm.group(1);
                vi = i;
                break;
            }
        }
        String corr = (vi >= 0 && vi + 1 < fields.size()) ? blankToNull(fields.get(vi + 1)) : null;
        String platform = (vi >= 0 && vi + 2 < fields.size()) ? blankToNull(fields.get(vi + 2)) : null;
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
        String json = m.group(6);
        String code = request ? null : firstGroup(CODE, json);
        String desc = request ? null : firstGroup(DESC, json);
        return new LogLine(ts, fe, request, version, corr, platform, took, path, code, desc);
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

    private static boolean isSuccessCode(String code) {
        return code != null && ALL_ZEROS.matcher(code).matches();
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
            calls.add(rp != null
                    ? new BackendCall(req.path(), rp.tookMs(), rp.code(), rp.desc(), true)
                    : new BackendCall(req.path(), null, null, null, false));
        }
        for (Deque<LogLine> leftover : respByPath.values()) {
            for (LogLine rp : leftover) {
                calls.add(new BackendCall(rp.path(), rp.tookMs(), rp.code(), rp.desc(), true));
            }
        }

        LogLine anchor = feReq != null ? feReq : (feResp != null ? feResp
                : (!beReq.isEmpty() ? beReq.get(0) : (!beResp.isEmpty() ? beResp.get(0) : null)));
        String corr = anchor != null ? anchor.correlationId() : null;
        String ts = group.stream().map(LogLine::ts).min(Comparator.naturalOrder()).orElse(null);
        String version = anchor != null ? anchor.version() : null;
        String platform = anchor != null ? anchor.platform() : null;
        String fePath = feReq != null ? feReq.path() : (feResp != null ? feResp.path() : null);
        return new Txn(corr, ts, version, platform, fePath, feReq, feResp, calls);
    }

    // --- per-API correlation ---

    private ApiLogResult correlate(ApiImpact api, List<Txn> txns, String version) {
        List<Txn> matched = new ArrayList<>();
        for (Txn t : txns) {
            if (t.fePath() != null && feMatches(t.fePath(), api.api())) {
                matched.add(t);
            }
        }
        boolean versionScoped = version != null && !version.isBlank();
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
                    LogStatus.NOT_TESTED, false, null, null, null, 0, 0, 0, null, null, note, List.of());
        }

        Txn latest = forVersion.stream().max(Comparator.comparing(Txn::ts)).orElseThrow();
        int success = 0;
        for (Txn t : forVersion) {
            if (evaluate(t, api.backends()).status() == LogStatus.SUCCESS) {
                success++;
            }
        }
        Eval eval = evaluate(latest, api.backends());
        String feCode = latest.feResp() != null ? latest.feResp().code() : null;
        String feDesc = latest.feResp() != null ? latest.feResp().desc() : null;
        Integer feTook = latest.feResp() != null ? latest.feResp().tookMs() : null;

        return new ApiLogResult(api.api(), api.operation(), api.resolvedRoute(), version,
                eval.status(), true, feTook, feCode, feDesc,
                forVersion.size(), success, forVersion.size() - success,
                latest.ts(), latest.correlationId(), eval.note(), eval.backends());
    }

    /** Front-end path match: the log path ends with (or contains) the traced controller path. */
    private boolean feMatches(String logPath, String apiPath) {
        if (apiPath == null || apiPath.isBlank()) {
            return false;
        }
        String a = apiPath.trim();
        return logPath.endsWith(a) || logPath.contains(a);
    }

    /** Backend match: the traced backend (which may carry a {{baseUrl}} prefix) ends with the log path. */
    private boolean backendMatches(String tracedBackend, String observedPath) {
        String tb = tracedBackend.trim();
        String op = observedPath.trim();
        return tb.endsWith(op) || tb.endsWith(op.startsWith("/") ? op.substring(1) : "/" + op);
    }

    private Eval evaluate(Txn t, List<String> tracedBackends) {
        // Front end is the source of truth for the end-to-end verdict.
        if (t.feResp() == null) {
            return new Eval(LogStatus.TIMEOUT,
                    "Front-end request logged but no response — timeout or server down.",
                    backendResults(t, tracedBackends));
        }
        String code = t.feResp().code();
        if (code == null) {
            return new Eval(LogStatus.INDETERMINATE,
                    "Front-end response logged but no responseCode could be read"
                            + (t.feResp().desc() != null ? " (description: " + t.feResp().desc() + ")." : "."),
                    backendResults(t, tracedBackends));
        }
        if (!isSuccessCode(code)) {
            return new Eval(LogStatus.FAILED,
                    "Front-end responseCode " + code
                            + (t.feResp().desc() != null ? " (" + t.feResp().desc() + ")." : "."),
                    backendResults(t, tracedBackends));
        }
        // Front end OK — check the backends it was traced to call. Only a backend
        // that was actually observed and failed downgrades the verdict: a traced
        // backend that never appears is usually a choice branch that wasn't taken,
        // not a failure, so it is reported for info but does not flag PARTIAL.
        List<BackendCallResult> backends = backendResults(t, tracedBackends);
        List<String> issues = new ArrayList<>();
        for (BackendCallResult b : backends) {
            if (b.status() != LogStatus.SUCCESS && b.status() != LogStatus.NOT_TESTED) {
                issues.add(b.status().name().toLowerCase() + " backend: " + b.backend());
            }
        }
        if (!issues.isEmpty()) {
            return new Eval(LogStatus.PARTIAL,
                    "Front-end succeeded but " + String.join("; ", issues) + ".", backends);
        }
        return new Eval(LogStatus.SUCCESS, null, backends);
    }

    private List<BackendCallResult> backendResults(Txn t, List<String> tracedBackends) {
        List<BackendCallResult> out = new ArrayList<>();
        for (String tb : tracedBackends) {
            BackendCall hit = null;
            for (BackendCall c : t.calls()) {
                if (backendMatches(tb, c.path())) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) {
                out.add(new BackendCallResult(tb, null, LogStatus.NOT_TESTED, null, null, null));
            } else if (!hit.hasResponse()) {
                out.add(new BackendCallResult(tb, hit.path(), LogStatus.TIMEOUT, null, null, null));
            } else if (hit.code() == null) {
                out.add(new BackendCallResult(tb, hit.path(), LogStatus.INDETERMINATE,
                        hit.tookMs(), hit.code(), hit.desc()));
            } else if (isSuccessCode(hit.code())) {
                out.add(new BackendCallResult(tb, hit.path(), LogStatus.SUCCESS,
                        hit.tookMs(), hit.code(), hit.desc()));
            } else {
                out.add(new BackendCallResult(tb, hit.path(), LogStatus.FAILED,
                        hit.tookMs(), hit.code(), hit.desc()));
            }
        }
        return out;
    }

    // --- internal records ---

    private record LogLine(String ts, boolean fe, boolean request, String version,
                           String correlationId, String platform, Integer tookMs,
                           String path, String code, String desc) {
    }

    private record BackendCall(String path, Integer tookMs, String code, String desc, boolean hasResponse) {
    }

    private record Txn(String correlationId, String ts, String version, String platform,
                       String fePath, LogLine feReq, LogLine feResp, List<BackendCall> calls) {
    }

    private record Eval(LogStatus status, String note, List<BackendCallResult> backends) {
    }
}
