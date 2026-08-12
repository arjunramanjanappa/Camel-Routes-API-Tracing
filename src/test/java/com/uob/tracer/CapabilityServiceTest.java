package com.uob.tracer;

import com.uob.tracer.service.CapabilityService;
import com.uob.tracer.service.CapabilityService.CapabilityResult;
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
            // the unknown API lands in the Unmatched sheet.
            Sheet un = wb.getSheet("Unmatched");
            assertThat(un).isNotNull();
            assertThat(un.getRow(1).getCell(0).getStringCellValue()).contains("/does/not/exist");
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
