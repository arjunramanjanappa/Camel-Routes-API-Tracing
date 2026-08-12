package com.uob.tracer.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Joins the impacted APIs (Release Test / Release Impact / Release Scope) to the team's VAL Excel reports so a
 * tester or a leadership summary can see the business capabilities behind each API (how to test it), rather than
 * a raw path.
 *
 * <p>Two files are configured once (stored in {@code ~/.traceguard}, like the log rules) and re-uploaded when the
 * VAL updates:
 * <ul>
 *   <li><b>Interface Spec</b> — column <b>G</b> (Interface) is the API path (matched by <i>ends-with</i>, so a
 *       {@code /spl-onboarding/...} context prefix is tolerated); column <b>M</b> (Countries, comma-separated)
 *       scopes it; column <b>Q</b> (Linked Capabilities, 1+ comma-separated IDs) is the bridge.</li>
 *   <li><b>Capability Matrix</b> — column <b>A</b> is the ID; columns <b>A–N</b> (L1–L5 capability, L6 features,
 *       dependent capability, linked interface/report/test-case, entity, SPL table, description) are the output.</li>
 * </ul>
 *
 * <p>FE vs BE: a front-end path contains {@code /services/}; a backend path does not — so an API is matched only
 * against Interface rows of the same kind, stopping a BE path from false-matching an FE row with the same tail.
 */
@Service
public class CapabilityService {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilityService.class);

    // Fixed 0-based column indices per the VAL report layout (row 1 is the header). See the class doc.
    private static final int SPEC_INTERFACE = 6;    // G
    private static final int SPEC_COUNTRIES = 12;   // M
    private static final int SPEC_CAPABILITIES = 16; // Q
    private static final int MATRIX_LAST = 13;      // N — capability columns A..N are kept

    /** One Capability Matrix row (columns A–N) — the output for a matched capability. */
    public record CapabilityRow(String id, String l1, String l2, String l3, String l4, String l5,
                                String l6Features, String dependentCapability, String linkedInterface,
                                String linkedReport, String linkedTestCase, String entity,
                                String splDetailedInterfaceTable, String description) {}

    /** An impacted API matched to its capabilities, with the Interface Spec Col G value that matched (for cross-check). */
    public record CapabilityMatch(String api, boolean fe, String matchedInterface, List<CapabilityRow> capabilities) {}

    /** An impacted API that couldn't be resolved, with why (no interface row, or an ID missing from the Matrix). */
    public record Unmatched(String api, boolean fe, String reason) {}

    /** The full join for a scope's impacted APIs. */
    public record CapabilityResult(List<CapabilityMatch> matched, List<Unmatched> unmatched) {}

    /** One parsed Interface Spec row: the interface path, its countries, and its linked capability IDs. */
    private record SpecRow(String interfaceCol, Set<String> countries, List<String> capabilityIds) {}

    private final Path home;
    private final Path interfaceSpecFile;
    private final Path capabilityMatrixFile;
    private final Object lock = new Object();

    public CapabilityService(@Value("${tracer.home:}") String home) {
        this.home = resolveHome(home);
        this.interfaceSpecFile = this.home.resolve("val-interface-spec.xlsx");
        this.capabilityMatrixFile = this.home.resolve("val-capability-matrix.xlsx");
    }

    // --- config store (two .xlsx files) ---

    public boolean hasInterfaceSpec() { return Files.exists(interfaceSpecFile); }

    public boolean hasCapabilityMatrix() { return Files.exists(capabilityMatrixFile); }

    public void saveInterfaceSpec(byte[] xlsx) { write(interfaceSpecFile, xlsx); }

    public void saveCapabilityMatrix(byte[] xlsx) { write(capabilityMatrixFile, xlsx); }

    private void write(Path file, byte[] xlsx) {
        if (xlsx == null || xlsx.length == 0) {
            throw new IllegalArgumentException("Empty file");
        }
        synchronized (lock) {
            try {
                Files.createDirectories(home);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, xlsx);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Saved VAL report to {}", file);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not save " + file.getFileName() + ": " + e.getMessage());
            }
        }
    }

    // --- join ---

    /**
     * Resolve each impacted API (front-end paths + backend paths) to its capabilities, scoped to {@code country}.
     * Both VAL files must be configured; otherwise every API is reported unmatched with that reason.
     */
    public CapabilityResult resolve(List<String> feApis, List<String> beApis, String country) {
        List<CapabilityMatch> matched = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        if (!hasInterfaceSpec() || !hasCapabilityMatrix()) {
            String why = "VAL reports not configured (attach the Interface Spec and Capability Matrix).";
            for (String a : distinct(feApis)) unmatched.add(new Unmatched(a, true, why));
            for (String a : distinct(beApis)) unmatched.add(new Unmatched(a, false, why));
            return new CapabilityResult(matched, unmatched);
        }

        List<SpecRow> spec;
        Map<String, CapabilityRow> matrix;
        synchronized (lock) {
            spec = parseInterfaceSpec();
            matrix = parseCapabilityMatrix();
        }
        String cc = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);

        for (String api : distinct(feApis)) resolveOne(api, true, cc, spec, matrix, matched, unmatched);
        for (String api : distinct(beApis)) resolveOne(api, false, cc, spec, matrix, matched, unmatched);
        return new CapabilityResult(matched, unmatched);
    }

    private void resolveOne(String api, boolean fe, String cc, List<SpecRow> spec, Map<String, CapabilityRow> matrix,
                            List<CapabilityMatch> matched, List<Unmatched> unmatched) {
        String a = api == null ? "" : api.trim();
        if (a.isEmpty()) {
            return;
        }
        // FE paths carry /services/, BE paths do not — match only same-kind interface rows so a BE path can't
        // false-match an FE interface that shares the same tail.
        String matchedInterface = null;
        Set<String> ids = new LinkedHashSet<>();   // UNION of Col Q across all matching interface rows
        for (SpecRow r : spec) {
            boolean rowIsFe = containsServices(r.interfaceCol());
            if (rowIsFe != fe) {
                continue;
            }
            if (!endsWithApi(r.interfaceCol(), a)) {
                continue;
            }
            if (!cc.isEmpty() && !r.countries().contains(cc)) {
                continue;
            }
            if (matchedInterface == null) {
                matchedInterface = r.interfaceCol();
            }
            ids.addAll(r.capabilityIds());
        }
        if (matchedInterface == null) {
            unmatched.add(new Unmatched(a, fe, "No Interface Spec row (Col G) ends with this API"
                    + (cc.isEmpty() ? "" : " for country " + cc) + "."));
            return;
        }
        List<CapabilityRow> caps = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            CapabilityRow row = matrix.get(id.toUpperCase(Locale.ROOT));
            if (row != null) {
                caps.add(row);
            } else {
                missing.add(id);
            }
        }
        if (caps.isEmpty()) {
            unmatched.add(new Unmatched(a, fe, ids.isEmpty()
                    ? "Interface row matched but its Linked Capabilities (Col Q) is empty."
                    : "Linked Capability ID(s) not found in the Capability Matrix: " + String.join(", ", missing) + "."));
            return;
        }
        matched.add(new CapabilityMatch(a, fe, matchedInterface, caps));
    }

    /** ends-with on a path boundary: the interface value ends with the API, and the API begins a path segment. */
    private static boolean endsWithApi(String interfaceCol, String api) {
        if (interfaceCol == null || api.isEmpty()) {
            return false;
        }
        String g = interfaceCol.trim();
        if (!g.toLowerCase(Locale.ROOT).endsWith(api.toLowerCase(Locale.ROOT))) {
            return false;
        }
        // The match must start at a '/' boundary (or be the whole value) so a short api can't match mid-segment.
        int at = g.length() - api.length();
        return at == 0 || api.charAt(0) == '/' || g.charAt(at - 1) == '/';
    }

    private static boolean containsServices(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains("/services/");
    }

    // --- Excel export (option B: one row per API→capability; matched Interface Col G printed last) ---

    private static final String[] OUT_HEADERS = {
            "ID", "L1 Capability", "L2 Capability", "L3 Capability", "L4 Capability", "L5 Capability",
            "L6 Features", "Dependent Capability", "Linked Interface", "Linked Report", "Linked Test Case",
            "Entity", "Linked SPL detailed Interface table", "Description", "FE/BE", "Matched Interface (Col G)"
    };

    /** Back-compat: export with no per-API test status (Release Scope — no logs). */
    public byte[] exportExcel(CapabilityResult result) {
        return exportExcel(result, Map.of());
    }

    /**
     * Build the joined Capability export workbook: a "Capabilities" sheet + an "Unmatched" sheet. When
     * {@code statusByApi} is non-empty (Release Test — the log-analysis verdicts), a leading <b>Test Status</b>
     * column is added to both sheets so the tester sees what to focus on (Failed / Partial / Not Tested).
     */
    public byte[] exportExcel(CapabilityResult result, Map<String, String> statusByApi) {
        Map<String, String> status = statusByApi == null ? Map.of() : statusByApi;
        boolean withStatus = !status.isEmpty();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Map<String, CellStyle> statusStyles = new LinkedHashMap<>();   // one fill style per status label, per workbook
            Sheet sheet = wb.createSheet("Capabilities");
            writeRow(sheet, 0, header(withStatus, OUT_HEADERS));
            int r = 1;
            for (CapabilityMatch m : result.matched()) {
                String st = status.getOrDefault(m.api(), "");
                for (CapabilityRow c : m.capabilities()) {
                    String[] base = {
                            c.id(), c.l1(), c.l2(), c.l3(), c.l4(), c.l5(), c.l6Features(), c.dependentCapability(),
                            c.linkedInterface(), c.linkedReport(), c.linkedTestCase(), c.entity(),
                            c.splDetailedInterfaceTable(), c.description(),
                            m.fe() ? "FE" : "BE", m.matchedInterface()};
                    Row row = writeRow(sheet, r++, withStatus ? prepend(st, base) : base);
                    if (withStatus) colourStatus(wb, statusStyles, row.getCell(0), st);
                }
            }
            Sheet un = wb.createSheet("Unmatched");
            writeRow(un, 0, header(withStatus, new String[]{"API", "FE/BE", "Reason"}));
            int u = 1;
            for (Unmatched m : result.unmatched()) {
                String[] base = {m.api(), m.fe() ? "FE" : "BE", m.reason()};
                String st = status.getOrDefault(m.api(), "");
                Row row = writeRow(un, u++, withStatus ? prepend(st, base) : base);
                if (withStatus) colourStatus(wb, statusStyles, row.getCell(0), st);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the capability export: " + e.getMessage(), e);
        }
    }

    /** Prepend the "Test Status" header when a status is being written. */
    private static String[] header(boolean withStatus, String[] base) {
        return withStatus ? prepend("Test Status", base) : base;
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static Row writeRow(Sheet sheet, int rowIdx, String[] values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
        return row;
    }

    /** Fill the Test Status cell with a status colour (green/amber/red…) so the sheet is scannable at a glance. */
    private static void colourStatus(XSSFWorkbook wb, Map<String, CellStyle> cache, Cell cell, String label) {
        byte[] rgb = statusRgb(label);
        if (cell == null || rgb == null) {
            return;
        }
        CellStyle style = cache.computeIfAbsent(label, k -> {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(new XSSFColor(rgb, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return s;
        });
        cell.setCellStyle(style);
    }

    /** Excel fill for a verdict label (the classic conditional-format tints), or null for an unknown/blank one. */
    private static byte[] statusRgb(String label) {
        String l = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        return switch (l) {
            case "success", "passed" -> rgb(0xC6, 0xEF, 0xCE);   // green
            case "partial" -> rgb(0xFF, 0xEB, 0x9C);             // amber
            case "failed" -> rgb(0xFF, 0xC7, 0xCE);              // red
            case "not tested" -> rgb(0xFF, 0xD9, 0xB3);          // orange (a gap to cover)
            case "timeout" -> rgb(0xFF, 0xC7, 0xCE);             // red
            case "check", "indeterminate" -> rgb(0xBD, 0xD7, 0xEE); // blue (needs a look)
            case "skipped" -> rgb(0xE7, 0xE6, 0xE6);             // grey (neutral)
            default -> null;
        };
    }

    private static byte[] rgb(int r, int g, int b) { return new byte[]{(byte) r, (byte) g, (byte) b}; }

    // --- parsing ---

    private List<SpecRow> parseInterfaceSpec() {
        List<SpecRow> rows = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(interfaceSpecFile)))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {   // skip header row
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String iface = cell(row, SPEC_INTERFACE, fmt);
                if (iface.isBlank()) {
                    continue;
                }
                Set<String> countries = splitUpper(cell(row, SPEC_COUNTRIES, fmt));
                List<String> caps = splitList(cell(row, SPEC_CAPABILITIES, fmt));
                rows.add(new SpecRow(iface.trim(), countries, caps));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the Interface Spec: " + e.getMessage(), e);
        }
        return rows;
    }

    private Map<String, CapabilityRow> parseCapabilityMatrix() {
        Map<String, CapabilityRow> byId = new LinkedHashMap<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(capabilityMatrixFile)))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String[] c = new String[MATRIX_LAST + 1];
                for (int col = 0; col <= MATRIX_LAST; col++) {
                    c[col] = cell(row, col, fmt);
                }
                String id = c[0].trim();
                if (id.isBlank()) {
                    continue;
                }
                byId.put(id.toUpperCase(Locale.ROOT), new CapabilityRow(
                        id, c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13]));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the Capability Matrix: " + e.getMessage(), e);
        }
        return byId;
    }

    private static String cell(Row row, int idx, DataFormatter fmt) {
        Cell c = row.getCell(idx);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }

    /** Split a comma-separated cell into a trimmed, upper-cased set (for countries). */
    private static Set<String> splitUpper(String v) {
        Set<String> out = new LinkedHashSet<>();
        if (v != null) {
            for (String p : v.split("[,/]")) {   // tolerate comma or slash between countries
                String t = p.trim().toUpperCase(Locale.ROOT);
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** Split a comma-separated cell into a trimmed list (for capability IDs). */
    private static List<String> splitList(String v) {
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String p : v.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static List<String> distinct(List<String> in) {
        Set<String> seen = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) seen.add(s.trim());
            }
        }
        return new ArrayList<>(seen);
    }

    private static Path resolveHome(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".traceguard");
    }
}
