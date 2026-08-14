package com.uob.tracer;

import com.uob.tracer.service.CapabilityService;
import com.uob.tracer.service.CapabilityService.CapabilityResult;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Joining the impacted APIs to the VAL Interface Spec + Capability Matrix Excel reports. */
class CapabilityServiceTest {

    private static byte[] xlsx(String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c] == null ? "" : rows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** An Interface Spec data row: Col G (6) = interface, Col M (12) = countries, Col Q (16) = capability IDs. */
    private static String[] specRow(String iface, String countries, String capIds) {
        String[] r = new String[19];   // A..S
        for (int i = 0; i < r.length; i++) r[i] = "";
        r[6] = iface; r[12] = countries; r[16] = capIds;
        return r;
    }

    /** A Capability Matrix data row: Col A (0) = ID, then L1..description across A..N. */
    private static String[] matrixRow(String id, String l1, String l2) {
        String[] r = new String[14];   // A..N
        for (int i = 0; i < r.length; i++) r[i] = "";
        r[0] = id; r[1] = l1; r[2] = l2; r[13] = "how to test " + id;
        return r;
    }

    private CapabilityService configured(Path home) throws Exception {
        CapabilityService svc = new CapabilityService(home.toString());
        svc.saveInterfaceSpec(xlsx(
                new String[]{"Created", "ID", "Internal", "Func", "Layer", "Desc", "Interface", "Zone", "Src",
                        "SrcSub", "Tgt", "TgtSub", "Countries", "Regional", "Batch", "Status", "Linked Capabilities",
                        "MultiJourney", "LinkedIfaces"},
                specRow("/spl-onboarding/services/onboarding/security/fr/profile", "TH,SG", "SPL-CPB-488, SPL-CPB-489"),
                specRow("/spl-host/onboarding/security/fr/profile", "SG", "SPL-CPB-490")));   // BE (no /services/)
        svc.saveCapabilityMatrix(xlsx(
                new String[]{"ID", "L1", "L2", "L3", "L4", "L5", "L6", "Dep", "LinkedIface", "LinkedReport",
                        "LinkedTC", "Entity", "SPLTable", "Description"},
                matrixRow("SPL-CPB-488", "Onboarding", "Security"),
                matrixRow("SPL-CPB-489", "Onboarding", "Profile"),
                matrixRow("SPL-CPB-490", "Onboarding", "Host")));
        return svc;
    }

    @Test
    void joinsFeAndBeApisToTheirCapabilitiesByEndsWithCountryAndServices(@TempDir Path home) throws Exception {
        CapabilityService svc = configured(home);

        CapabilityResult r = svc.resolve(
                List.of("/services/onboarding/security/fr/profile"),   // FE (has /services/)
                List.of("/onboarding/security/fr/profile"),            // BE (no /services/) — same tail
                "SG");

        // FE matched the FE interface row → UNION of its two linked capability IDs.
        assertThat(r.matched()).anySatisfy(m -> {
            assertThat(m.fe()).isTrue();
            assertThat(m.matchedInterface()).contains("/services/");
            assertThat(m.capabilities()).extracting(CapabilityService.CapabilityRow::id)
                    .containsExactlyInAnyOrder("SPL-CPB-488", "SPL-CPB-489");
        });
        // BE matched the BE interface row (NOT the FE one, despite the identical tail).
        assertThat(r.matched()).anySatisfy(m -> {
            assertThat(m.fe()).isFalse();
            assertThat(m.matchedInterface()).doesNotContain("/services/");
            assertThat(m.capabilities()).extracting(CapabilityService.CapabilityRow::id).containsExactly("SPL-CPB-490");
        });
        assertThat(r.unmatched()).isEmpty();
    }

    @Test
    void countryScopesTheMatch(@TempDir Path home) throws Exception {
        CapabilityService svc = configured(home);
        // The FE interface is TH,SG only — a MY analysis must not match it.
        CapabilityResult r = svc.resolve(List.of("/services/onboarding/security/fr/profile"), List.of(), "MY");
        assertThat(r.matched()).isEmpty();
        assertThat(r.unmatched()).anySatisfy(u -> assertThat(u.reason()).contains("MY"));
    }

    @Test
    void exportProducesACapabilitiesSheetAndAnUnmatchedSheet(@TempDir Path home) throws Exception {
        CapabilityService svc = configured(home);
        CapabilityResult r = svc.resolve(
                List.of("/services/onboarding/security/fr/profile", "/services/does/not/exist"),
                List.of(), "SG");
        byte[] out = svc.exportExcel(r);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet caps = wb.getSheet("Capabilities");
            assertThat(caps).isNotNull();
            // header + 2 capability rows (488, 489), last column = the matched interface (Col G).
            assertThat(caps.getLastRowNum()).isEqualTo(2);
            Row first = caps.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isIn("SPL-CPB-488", "SPL-CPB-489");
            assertThat(first.getCell(15).getStringCellValue()).contains("/services/onboarding/security/fr/profile");
            // the unknown API lands in the "Unmapped Interface spec" sheet.
            Sheet un = wb.getSheet("Unmapped Interface spec");
            assertThat(un).isNotNull();
            assertThat(un.getRow(1).getCell(0).getStringCellValue()).contains("/does/not/exist");
        }
    }

    @Test
    void exportAddsALeadingTestStatusColumnWhenVerdictsAreProvided(@TempDir Path home) throws Exception {
        CapabilityService svc = configured(home);
        CapabilityResult r = svc.resolve(List.of("/services/onboarding/security/fr/profile"), List.of(), "SG");
        byte[] out = svc.exportExcel(r, Map.of("/services/onboarding/security/fr/profile", "Failed"));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet caps = wb.getSheet("Capabilities");
            assertThat(caps.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Test Status");   // leading column
            assertThat(caps.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Failed");         // the API's verdict
            assertThat(caps.getRow(1).getCell(0).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);  // colour-filled
            assertThat(caps.getRow(1).getCell(1).getStringCellValue()).isIn("SPL-CPB-488", "SPL-CPB-489");   // ID shifted to col 1
        }
        // Without statuses (Release Scope), the column is omitted — ID stays first.
        byte[] noStatus = svc.exportExcel(r);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(noStatus))) {
            assertThat(wb.getSheet("Capabilities").getRow(0).getCell(0).getStringCellValue()).isEqualTo("ID");
        }
    }

    @Test
    void generalExportSupportsANamedSheetAndATrailingColumn(@TempDir Path home) throws Exception {
        // The Release Impact "Release Impact - Capability Matrix" (sheet "BAU App Coverage"): a named matched
        // sheet + a plain "Impact Reason" column appended as the LAST column (ID stays first).
        CapabilityService svc = configured(home);
        String api = "/services/onboarding/security/fr/profile";
        CapabilityResult r = svc.resolve(List.of(api), List.of(), "SG");
        byte[] out = svc.exportExcel(r, "BAU App Coverage", Map.of(), List.of(
                new CapabilityService.ExtraColumn("Impact Reason", Map.of(api, "BAU route modified"))));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            Sheet s = wb.getSheet("BAU App Coverage");
            assertThat(s).isNotNull();
            int last = s.getRow(0).getLastCellNum() - 1;
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("ID");            // no leading extras
            assertThat(s.getRow(0).getCell(last).getStringCellValue()).isEqualTo("Impact Reason");   // trailing = last
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isIn("SPL-CPB-488", "SPL-CPB-489");
            assertThat(s.getRow(1).getCell(last).getStringCellValue()).isEqualTo("BAU route modified");
        }
    }

    @Test
    void perModuleExportPutsEachModuleInItsOwnTab(@TempDir Path home) throws Exception {
        // The consolidated Release Test export: ONE workbook, a sheet per module (tab name = module), so no
        // Module column is needed. Each tab holds that module's capabilities; unmapped APIs sit in a section below.
        CapabilityService svc = configured(home);
        CapabilityResult mighty = svc.resolve(List.of("/services/onboarding/security/fr/profile"), List.of(), "SG");
        CapabilityResult spl = svc.resolve(List.of("/services/does/not/exist"), List.of(), "SG");   // no capability → unmapped
        byte[] out = svc.exportByModule(List.of(
                new CapabilityService.ModuleCapabilities("mighty-banking", mighty,
                        Map.of("/services/onboarding/security/fr/profile", "Passed")),
                new CapabilityService.ModuleCapabilities("spl-onboarding", spl, Map.of())));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out))) {
            // One tab per module, named by the module.
            assertThat(wb.getSheetName(0)).isEqualTo("mighty-banking");
            assertThat(wb.getSheetName(1)).isEqualTo("spl-onboarding");
            Sheet m = wb.getSheet("mighty-banking");
            assertThat(m.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Test Status");      // its verdicts lead
            assertThat(m.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Passed");
            assertThat(m.getRow(1).getCell(1).getStringCellValue()).isIn("SPL-CPB-488", "SPL-CPB-489");
            // The other module's tab carries its unmapped API under the labelled section.
            Sheet s = wb.getSheet("spl-onboarding");
            boolean hasUnmappedHeader = false, hasUnknownApi = false;
            for (int r = 0; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                if (row == null) continue;
                for (int c = 0; row.getCell(c) != null && c < row.getLastCellNum(); c++) {
                    String v = row.getCell(c).getStringCellValue();
                    if (v.startsWith("Unmapped Interface spec")) hasUnmappedHeader = true;
                    if (v.contains("/does/not/exist")) hasUnknownApi = true;
                }
            }
            assertThat(hasUnmappedHeader).isTrue();
            assertThat(hasUnknownApi).isTrue();
        }
    }

    @Test
    void reportsMissingConfigAsUnmatched(@TempDir Path home) {
        CapabilityService svc = new CapabilityService(home.toString());   // nothing uploaded
        CapabilityResult r = svc.resolve(List.of("/services/x"), List.of("/y"), "SG");
        assertThat(r.matched()).isEmpty();
        assertThat(r.unmatched()).hasSize(2);
        assertThat(r.unmatched()).allSatisfy(u -> assertThat(u.reason()).contains("not configured"));
    }
}
