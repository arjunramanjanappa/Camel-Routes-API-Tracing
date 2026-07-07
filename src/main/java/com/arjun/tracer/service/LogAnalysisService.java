package com.arjun.tracer.service;

import com.arjun.tracer.api.ApiImpact;
import com.arjun.tracer.api.ApiLogResult;
import com.arjun.tracer.api.BackendCallResult;
import com.arjun.tracer.api.BackendLogResult;
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
import java.util.Collection;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        InputStream in = (filename != null && filename.toLowerCase().endsWith(".gz"))
                ? new GZIPInputStream(raw) : raw;

        // The two applications differ only in the log marker: Mighty → MightyMessage /
        // MightyHostMessage, SPL → SPLMessage / SPLHostMessage. Everything else is identical.
        String application = (app == null || app.isBlank()) ? "Mighty" : app.trim();
        Markers markers = new Markers(application + "Message", application + "HostMessage");

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
                    default -> {
                        counters[0]++;
                        ingest(firstNonBlank, lines, counters, markers);
                        while ((line = r.readLine()) != null) {
                            counters[0]++;
                            ingest(line, lines, counters, markers);
                        }
                    }
                }
            }
        }

        if (lines.isEmpty()) {
            warnings.add("No log events found for the " + application + " application (detected " + detected
                    + "). Check the file is the raw output log or a Splunk export of the query.");
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
        ImpactIndex idx = traceService.impactIndex(
                new TraceRequest(null, version, null, sourceDir, country, repo, branch, dependencies));
        boolean apiSel = selectedApis != null && !selectedApis.isEmpty();
        boolean beSel = selectedBackends != null && !selectedBackends.isEmpty();

        // Backend URL → expected service version(s), aggregated across the release.
        // And backend api → its "hosturl" (what the host actually logs) — the log path is
        // matched against the hosturl when present, since the api value isn't what's logged.
        Map<String, String> expectedVersions = new LinkedHashMap<>();
        Map<String, String> hosturls = new LinkedHashMap<>();
        for (ApiImpact api : idx.getApis()) {
            api.backendVersions().forEach((url, ver) ->
                    expectedVersions.merge(url, ver, LogAnalysisService::joinVersions));
            api.backendHosturls().forEach(hosturls::putIfAbsent);
        }

        // Front-end (MightyMessage) section: the whole release when all=true, the
        // selected APIs when chosen, otherwise none (e.g. a backend-only analysis).
        List<ApiLogResult> apiResults = new ArrayList<>();
        if (all || apiSel) {
            for (ApiImpact api : idx.getApis()) {
                if (!all && !selectedApis.contains(api.api())) {
                    continue;
                }
                apiResults.add(correlate(api, txns, version, hosturls));
            }
        }

        // Backend (MightyHostMessage) section: every release backend when all=true,
        // the selected backends when chosen, otherwise none.
        List<BackendLogResult> backendResults = new ArrayList<>();
        List<String> beTargets = all ? idx.getAllBackends() : (beSel ? selectedBackends : List.of());
        // Disambiguate against the whole backend universe (release backends + the chosen
        // ones) so /bfs/… never steals a /bp/bfs/… call when both exist.
        Set<String> backendUniverse = new java.util.LinkedHashSet<>(idx.getAllBackends());
        backendUniverse.addAll(beTargets);
        for (String backend : beTargets) {
            backendResults.add(correlateBackend(backend, txns, version, expectedVersions.get(backend), backendUniverse, hosturls));
        }

        return new LogAnalysisReport(detected, version, idx.getCountry(),
                counters[0], lines.size(), txns.size(), counters[1], apiResults, backendResults, warnings);
    }

    // --- input shape detection + extraction ---

    /** A single candidate log line (raw, or a Splunk _raw value) → parse + collect. */
    private void ingest(String s, List<LogLine> lines, int[] counters, Markers markers) {
        if (s == null) {
            return;
        }
        if (s.indexOf(markers.fe()) < 0 && s.indexOf(markers.be()) < 0) {
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
        boolean marker = t.contains(markers.fe()) || t.contains(markers.be());
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

    /** The _raw cell of a parsed CSV record, or any cell carrying a marker when there's no _raw column. */
    private String cellFrom(List<String> cells, int rawIdx, Markers markers) {
        String cell = null;
        if (rawIdx >= 0 && rawIdx < cells.size()) {
            cell = cells.get(rawIdx);
        } else {
            for (String c : cells) {
                if (c.contains(markers.fe()) || c.contains(markers.be())) {
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
                code = jsonFind(tree, "responseCode");
                desc = jsonFind(tree, "responseDescription");
                svc = jsonFind(tree, "serviceVersionNumber");
            } else {
                code = firstGroup(CODE, json);
                desc = firstGroup(DESC, json);
                svc = firstGroup(SVC_VERSION, json);
            }
        }
        return new LogLine(ts, fe, request, version, corr, platform, took, path, code, desc, svc);
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
            // serviceVersionNumber is in both Request and Response payloads — prefer the request.
            String svc = req.serviceVersion() != null ? req.serviceVersion()
                    : (rp != null ? rp.serviceVersion() : null);
            calls.add(rp != null
                    ? new BackendCall(req.path(), rp.tookMs(), rp.code(), rp.desc(), true, svc)
                    : new BackendCall(req.path(), null, null, null, false, svc));
        }
        for (Deque<LogLine> leftover : respByPath.values()) {
            for (LogLine rp : leftover) {
                calls.add(new BackendCall(rp.path(), rp.tookMs(), rp.code(), rp.desc(), true, rp.serviceVersion()));
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

    private ApiLogResult correlate(ApiImpact api, List<Txn> txns, String version, Map<String, String> hosturls) {
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
            if (evaluate(t, api.backends(), api.backendVersions(), hosturls).status() == LogStatus.SUCCESS) {
                success++;
            }
        }
        Eval eval = evaluate(latest, api.backends(), api.backendVersions(), hosturls);
        String feCode = latest.feResp() != null ? latest.feResp().code() : null;
        String feDesc = latest.feResp() != null ? latest.feResp().desc() : null;
        Integer feTook = latest.feResp() != null ? latest.feResp().tookMs() : null;

        return new ApiLogResult(api.api(), api.operation(), api.resolvedRoute(), version,
                eval.status(), true, feTook, feCode, feDesc,
                forVersion.size(), success, forVersion.size() - success,
                latest.ts(), latest.correlationId(), eval.note(), eval.backends());
    }

    /** Backend-only correlation: read the MightyHostMessage calls that hit this backend. */
    private BackendLogResult correlateBackend(String backend, List<Txn> txns, String version, String expectedVersion,
                                              Collection<String> candidates, Map<String, String> hosturls) {
        boolean versionScoped = version != null && !version.isBlank();
        List<BackendHit> hits = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        boolean anyPathMatch = false;
        for (Txn t : txns) {
            // Match by URL AND service version together (prefer the svc-matching call),
            // letting "longest match wins" keep /bfs/… and /bp/bfs/… apart.
            BackendCall c = pickCall(t.calls(), backend, expectedVersion, candidates, hosturls);
            if (c == null) {
                continue;
            }
            anyPathMatch = true;
            seen.add(t.version() == null || t.version().isBlank() ? "(no version field read)" : t.version());
            if (!versionScoped || version.trim().equals(t.version())) {
                hits.add(new BackendHit(t, c));
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
            return new BackendLogResult(backend, LogStatus.NOT_TESTED, false, null, null, null, 0, 0, 0, null, null, note,
                    expectedVersion, null, null);
        }

        BackendHit latest = hits.stream().max(Comparator.comparing(h -> h.txn().ts())).orElseThrow();
        int success = 0;
        for (BackendHit h : hits) {
            if (beStatus(h.call()) == LogStatus.SUCCESS) {
                success++;
            }
        }
        LogStatus status = beStatus(latest.call());
        String logged = latest.call().serviceVersion();
        Boolean svcOk = versionOk(expectedVersion, logged);
        String note = switch (status) {
            case SUCCESS -> null;
            case TIMEOUT -> "Backend request logged but no response.";
            case INDETERMINATE -> "Backend response logged but no responseCode could be read.";
            default -> "Backend responseCode " + latest.call().code()
                    + (latest.call().desc() != null ? " (" + latest.call().desc() + ")." : ".");
        };
        if (Boolean.FALSE.equals(svcOk)) {
            note = (note == null ? "" : note + " ") + "Service version mismatch: called " + logged
                    + ", expected " + expectedVersion + ".";
        }
        return new BackendLogResult(backend, status, true, latest.call().tookMs(),
                latest.call().code(), latest.call().desc(),
                hits.size(), success, hits.size() - success, latest.txn().ts(), latest.txn().correlationId(), note,
                expectedVersion, logged, svcOk);
    }

    /** true if the logged version is one of the expected (possibly "2.2 / 3.3"); null if either is absent. */
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

    private LogStatus beStatus(BackendCall c) {
        if (!c.hasResponse()) {
            return LogStatus.TIMEOUT;
        }
        if (c.code() == null) {
            return LogStatus.INDETERMINATE;
        }
        return isSuccessCode(c.code()) ? LogStatus.SUCCESS : LogStatus.FAILED;
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
            if (pathMatch == null) {
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
        String s = backend;
        int ph = s.indexOf("}}");
        if (ph >= 0) {
            s = s.substring(ph + 2);
        }
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

    private Eval evaluate(Txn t, List<String> tracedBackends, Map<String, String> expectedVersions,
                          Map<String, String> hosturls) {
        List<BackendCallResult> backends = backendResults(t, tracedBackends, expectedVersions, hosturls);
        // Front end is the source of truth for the end-to-end verdict.
        if (t.feResp() == null) {
            return new Eval(LogStatus.TIMEOUT,
                    "Front-end request logged but no response — timeout or server down.", backends);
        }
        String code = t.feResp().code();
        if (code == null) {
            return new Eval(LogStatus.INDETERMINATE,
                    "Front-end response logged but no responseCode could be read"
                            + (t.feResp().desc() != null ? " (description: " + t.feResp().desc() + ")." : "."),
                    backends);
        }
        if (!isSuccessCode(code)) {
            return new Eval(LogStatus.FAILED,
                    "Front-end responseCode " + code
                            + (t.feResp().desc() != null ? " (" + t.feResp().desc() + ")." : "."), backends);
        }
        // Front end OK — check the backends it was traced to call. Only an observed
        // backend that failed (or was called at the wrong service version) downgrades
        // the verdict; a traced backend that never appears is usually a choice branch
        // that wasn't taken, so it is reported for info but does not flag PARTIAL.
        List<String> issues = new ArrayList<>();
        for (BackendCallResult b : backends) {
            if (b.status() != LogStatus.SUCCESS && b.status() != LogStatus.NOT_TESTED) {
                issues.add(b.status().name().toLowerCase() + " backend: " + b.backend());
            } else if (Boolean.FALSE.equals(b.serviceVersionOk())) {
                issues.add("wrong service version on " + b.backend()
                        + " (called " + b.loggedServiceVersion() + ", expected " + b.expectedServiceVersion() + ")");
            }
        }
        if (!issues.isEmpty()) {
            return new Eval(LogStatus.PARTIAL,
                    "Front-end succeeded but " + String.join("; ", issues) + ".", backends);
        }
        return new Eval(LogStatus.SUCCESS, null, backends);
    }

    private List<BackendCallResult> backendResults(Txn t, List<String> tracedBackends, Map<String, String> expectedVersions,
                                                   Map<String, String> hosturls) {
        List<BackendCallResult> out = new ArrayList<>();
        for (String tb : tracedBackends) {
            String expected = expectedVersions == null ? null : expectedVersions.get(tb);
            BackendCall hit = pickCall(t.calls(), tb, expected, tracedBackends, hosturls);
            if (hit == null) {
                out.add(new BackendCallResult(tb, null, LogStatus.NOT_TESTED, null, null, null, expected, null, null));
                continue;
            }
            LogStatus st = !hit.hasResponse() ? LogStatus.TIMEOUT
                    : hit.code() == null ? LogStatus.INDETERMINATE
                    : isSuccessCode(hit.code()) ? LogStatus.SUCCESS : LogStatus.FAILED;
            boolean timedOut = st == LogStatus.TIMEOUT;
            out.add(new BackendCallResult(tb, hit.path(), st,
                    timedOut ? null : hit.tookMs(), timedOut ? null : hit.code(), timedOut ? null : hit.desc(),
                    expected, hit.serviceVersion(), versionOk(expected, hit.serviceVersion())));
        }
        return out;
    }

    // --- internal records ---

    private record LogLine(String ts, boolean fe, boolean request, String version,
                           String correlationId, String platform, Integer tookMs,
                           String path, String code, String desc, String serviceVersion) {
    }

    private record BackendCall(String path, Integer tookMs, String code, String desc,
                               boolean hasResponse, String serviceVersion) {
    }

    private record BackendHit(Txn txn, BackendCall call) {
    }

    /** The front-end and backend log markers for the selected application. */
    private record Markers(String fe, String be) {
    }

    private record Txn(String correlationId, String ts, String version, String platform,
                       String fePath, LogLine feReq, LogLine feResp, List<BackendCall> calls) {
    }

    private record Eval(LogStatus status, String note, List<BackendCallResult> backends) {
    }
}
