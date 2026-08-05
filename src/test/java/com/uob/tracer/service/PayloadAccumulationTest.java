package com.uob.tracer.service;

import com.uob.tracer.api.PayloadValueChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The BAU payload diff must reflect ONLY the release's own commits on a template, accumulated per commit (each
 * commit vs its parent) — so a key/value that a DIFFERENT-version commit changed, even one interleaved between two
 * release commits on the same file, never surfaces. Guards the reported case: a .ftl with two [19.14.0] commits
 * adding tokenSerialNumber, and an interleaved [19.4.0] commit that changed bankAddress.addressLine2 — the latter
 * was leaking in via the old span diff.
 */
class PayloadAccumulationTest {

    private static String[] pair(String before, String after) {
        return new String[]{before, after};
    }

    @Test
    void anInterleavedOtherVersionValueChangeIsExcluded() {
        // [19.14.0] commit A adds tokenSerialNumber (addressLine2 still value1 on both sides of A).
        String baseline = "{ \"bankAddress\": { \"addressLine1\": \"x\", \"addressLine2\": \"value1\" } }";
        String afterA = "{ \"bankAddress\": { \"addressLine1\": \"x\", \"addressLine2\": \"value1\" }, \"tokenSerialNumber\": \"0\" }";
        // A [19.4.0] commit (NOT replayed) changed addressLine2 value1 -> value2 in between.
        // [19.14.0] commit B only tweaks the tokenSerialNumber row (addressLine2 is value2 on both sides of B).
        String beforeB = "{ \"bankAddress\": { \"addressLine1\": \"x\", \"addressLine2\": \"value2\" }, \"tokenSerialNumber\": \"0\" }";
        String afterB = "{ \"bankAddress\": { \"addressLine1\": \"x\", \"addressLine2\": \"value2\" }, \"tokenSerialNumber\": \"00\" }";

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<PayloadValueChange> vals = new ArrayList<>();
        RouteTraceService.accumulatePayload(List.of(pair(baseline, afterA), pair(beforeB, afterB)), added, removed, vals);

        assertThat(added).contains("tokenSerialNumber");   // the real 19.14.0 change
        assertThat(removed).isEmpty();
        // The interleaved 19.4.0 change to addressLine2 is NOT attributed to the release — no single release
        // commit's own diff contains it (A: value1 both sides; B: value2 both sides).
        assertThat(vals).noneMatch(v -> v.key().toLowerCase().contains("addressline2"));
        assertThat(vals).isEmpty();
    }

    @Test
    void aValueChangeAReleaseCommitItselfMadeIsReported() {
        String before = "{ \"limit\": \"10\" }";
        String after = "{ \"limit\": \"20\" }";
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<PayloadValueChange> vals = new ArrayList<>();
        RouteTraceService.accumulatePayload(List.<String[]>of(pair(before, after)), added, removed, vals);
        assertThat(vals).anySatisfy(v -> {
            assertThat(v.key()).contains("limit");
            assertThat(v.before()).isEqualTo("10");
            assertThat(v.after()).isEqualTo("20");
        });
    }

    @Test
    void aWhitespaceOrTabOnlyReindentOfTheFtlIsNotAPayloadChange() {
        String before = "{ \"amount\": ${amt}, \"bankAddress\": { \"addressLine2\": \"${line2}\" } }";
        // Identical content, reindented with tabs + newlines and stray spaces inside interpolations/around colons.
        String after = "{\n\t\"amount\":   ${ amt },\n\t\"bankAddress\": {\n\t\t\"addressLine2\" :\t\"${line2}\"\n\t}\n}";
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<PayloadValueChange> vals = new ArrayList<>();
        RouteTraceService.accumulatePayload(List.<String[]>of(pair(before, after)), added, removed, vals);
        assertThat(added).isEmpty();
        assertThat(removed).isEmpty();
        assertThat(vals).isEmpty();
    }

    @Test
    void aLiteralValueWhitespaceReflowIsNotAChange() {
        String before = "{ \"greeting\": \"Dear   Customer\" }";
        String after = "{ \"greeting\": \"Dear Customer\" }";   // collapsed internal whitespace only
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<PayloadValueChange> vals = new ArrayList<>();
        RouteTraceService.accumulatePayload(List.<String[]>of(pair(before, after)), added, removed, vals);
        assertThat(vals).isEmpty();
    }

    @Test
    void aKeyTheReleaseAddedThenRemovedNetsToNothing() {
        String base = "{ \"a\": \"1\" }";
        String withB = "{ \"a\": \"1\", \"b\": \"2\" }";
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<PayloadValueChange> vals = new ArrayList<>();
        RouteTraceService.accumulatePayload(List.of(pair(base, withB), pair(withB, base)), added, removed, vals);
        assertThat(added).doesNotContain("b");
        assertThat(removed).doesNotContain("b");
    }
}
