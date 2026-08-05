package com.uob.tracer;

import com.uob.tracer.api.ApiDiff;
import com.uob.tracer.api.TraceRequest;
import com.uob.tracer.api.VersionDiffReport;
import com.uob.tracer.service.RouteTraceService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release diff compares each impacted API's whole resolved flow at a target
 * version against its immediate-lower version, highlighting what the release changed.
 */
class VersionDiffTest {

    private final RouteTraceService service =
            new RouteTraceService("src/test/resources/sample-framework");

    private ApiDiff diffFor(VersionDiffReport report, String operation) {
        return report.getApis().stream()
                .filter(a -> a.operation().equals(operation))
                .findFirst().orElseThrow();
    }

    @Test
    void aMalformedTargetFtlTemplateIsReportedAsATemplateIssue() {
        // The 9.14 request template has a missing comma between two fields — valid FTL, invalid JSON once
        // rendered. It must surface as a STRUCTURE template issue for the impacted API's .ftl.
        RouteTraceService svc = new RouteTraceService("src/test/resources/ftl-broken");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.14", null, null));

        assertThat(report.getTemplateIssues()).anySatisfy(t -> {
            assertThat(t.kind()).isEqualTo("STRUCTURE");
            assertThat(t.file()).isEqualTo("precapture.ftl");
            assertThat(t.api()).contains("/svc/broken");
        });
    }

    @Test
    void comparesTheResolvedFlowAgainstTheImmediateLowerVersion() {
        VersionDiffReport report = service.versionDiff(new TraceRequest(null, "9.4", null, null));
        assertThat(report.getVersion()).isEqualTo("9.4");

        ApiDiff ft = diffFor(report, "fundTransferSubmitV2Api");
        // The immediate-lower baseline is the versioned 9.3 route, not BASE.
        assertThat(ft.lowerVersion()).isEqualTo("9.3");
        assertThat(ft.lowerRoute()).isEqualTo("R9.3_fundTransferSubmitV2Api");
        assertThat(ft.targetRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");
        assertThat(ft.status()).isEqualTo(ApiDiff.CHANGED);

        // 9.4 restructured the master route (explicit OWN / INTRA / INTER branches,
        // vs 9.3's single INTER branch + otherwise) — a real structural change.
        assertThat(ft.routeDiffs()).extracting(d -> d.routeBase())
                .contains("masterFundTransferSubmitApi");
        // ...and introduced dedicated own/intra sub-routes the 9.3 flow never called.
        assertThat(ft.addedRoutes())
                .contains("ftOwnAccountProcessSubmitDGEApi", "ftIntrabankProcessSubmitDGEApi");

        // The entry route differs ONLY by its version-stamped direct: hand-off uri,
        // which is normalised away — so it is NOT reported as a changed route.
        assertThat(ft.routeDiffs()).extracting(d -> d.routeBase())
                .doesNotContain("fundTransferSubmitV2Api");
    }

    @Test
    void usesBaseAsBaselineWhenNoLowerVersionedRouteExists() {
        // At 9.3 there is no versioned route below it, but a BASE route exists, so the
        // baseline is BASE — and 9.3 (delegating master) differs from the flat BASE flow.
        VersionDiffReport report = service.versionDiff(new TraceRequest(null, "9.3", null, null));

        ApiDiff ft = diffFor(report, "fundTransferSubmitV2Api");
        assertThat(ft.lowerVersion()).isEqualTo("BASE");
        assertThat(ft.lowerRoute()).isEqualTo("fundTransferSubmitV2Api");
        assertThat(ft.status()).isEqualTo(ApiDiff.CHANGED);
    }

    @Test
    void flagsApisWithNoLowerVersionAsNew() {
        VersionDiffReport report = service.versionDiff(new TraceRequest(null, "9.4", null, null));

        // limitInitiateApi / comboApi / dualApi exist only at 9.4 with no BASE → NEW.
        ApiDiff limit = diffFor(report, "limitInitiateApi");
        assertThat(limit.status()).isEqualTo(ApiDiff.NEW);
        assertThat(limit.lowerRoute()).isNull();
        assertThat(limit.lowerVersion()).isNull();
        // A new API with no payload/BAU-class signal is new-app work — it cannot impact BAU, so it is Low.
        assertThat(limit.risk()).isEqualTo(ApiDiff.RISK_LOW);
        assertThat(report.getNewCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void apisWithNoRouteAtTargetFallBackToTheirLatestLowerAsUnchanged() {
        // At 9.5 no API has a route, so each still resolves to its latest lower route
        // (R9.4 / R9.3). They are not part of 9.5 — shown as UNCHANGED with a note,
        // behind the toggle — rather than dropped.
        VersionDiffReport report = service.versionDiff(new TraceRequest(null, "9.5", null, null));

        assertThat(report.getChangedCount()).isZero();
        assertThat(report.getNewCount()).isZero();
        assertThat(report.getUnchangedCount()).isGreaterThanOrEqualTo(4);

        ApiDiff ft = diffFor(report, "fundTransferSubmitV2Api");
        assertThat(ft.status()).isEqualTo(ApiDiff.UNCHANGED);
        assertThat(ft.targetRoute()).isEqualTo("R9.4_fundTransferSubmitV2Api");  // latest lower
        assertThat(ft.note()).contains("No 9.5 route");

        // An operation with no route anywhere (the v1 endpoint) is not surfaced at all.
        assertThat(report.getApis()).noneMatch(a -> a.operation().equals("fundTransferSubmitApi"));
    }

    @Test
    void surfacesABackendServiceVersionBumpEvenWhenTheRouteStructureMatches() {
        // bumpApi at 9.5 vs 9.4: identical flow, but the request template carries a
        // bumped backend serviceVersionNumber (2.2 → 2.3). That lives in the template,
        // not the route XML, so it must be detected by comparing traced backend versions.
        // 9.4 uses a freemarker .ftl, 9.5 a velocity .vm — proving both template
        // engines are resolved, not just freemarker.
        RouteTraceService svc = new RouteTraceService("src/test/resources/svc-diff-framework");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.5", null, null));

        ApiDiff bump = diffFor(report, "bumpApi");
        assertThat(bump.status()).isEqualTo(ApiDiff.CHANGED);
        assertThat(bump.backendVersionChanges()).hasSize(1);
        assertThat(bump.backendVersionChanges().get(0).backend()).contains("/svc/bump");
        assertThat(bump.backendVersionChanges().get(0).fromVersion()).isEqualTo("2.2");
        assertThat(bump.backendVersionChanges().get(0).toVersion()).isEqualTo("2.3");
        // This fixture's 9.4→9.5 templates also restructure the payload keys (operation → ServiceContext/
        // channelId) — but a version-diff payload change is between two DIFFERENT version routes (new-app-scoped),
        // so it is NOT backward-incompatible. The only BAU-affecting signal here is the backend service-version
        // bump → Medium (and no backward-compat).
        assertThat(bump.payloadChange()).isNotNull();
        assertThat(bump.risk()).isEqualTo(ApiDiff.RISK_MEDIUM);
        assertThat(report.getBackwardCompatCount()).isZero();
    }

    @Test
    void pureBackendVersionBumpIsMediumRisk() {
        // Only the backend serviceVersionNumber the request template carries changes (2.2 → 2.3); the payload
        // keys are identical and no BAU class is touched. That is scoped to the new route, so risk is Medium.
        RouteTraceService svc = new RouteTraceService("src/test/resources/svc-bump-clean");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.5", null, null));

        ApiDiff bump = diffFor(report, "bumpApi");
        assertThat(bump.status()).isEqualTo(ApiDiff.CHANGED);
        assertThat(bump.backendVersionChanges()).hasSize(1);
        assertThat(bump.payloadChange()).isNull();   // same keys, only the version value differs
        assertThat(bump.codeChanged()).isFalse();
        assertThat(bump.risk()).isEqualTo(ApiDiff.RISK_MEDIUM);
        assertThat(report.getHighRiskCount()).isZero();
    }

    @Test
    void additivePayloadOnANewVersionRouteIsNotHighRisk() {
        // R9.14 (built from R9.8) only ADDS request fields (C, D) — no removals, no BAU class change, same
        // backend service version (2.0). Additive payload is backward compatible and scoped to the new
        // version-specific route; the old app keeps calling R9.8 unchanged. So this is LOW risk, not High.
        RouteTraceService svc = new RouteTraceService("src/test/resources/payload-add");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.14", null, null));

        ApiDiff add = diffFor(report, "addApi");
        assertThat(add.status()).isEqualTo(ApiDiff.CHANGED);
        assertThat(add.payloadChange()).isNotNull();
        assertThat(add.payloadChange().addedKeys()).contains("fieldC", "fieldD");
        assertThat(add.payloadChange().removedKeys()).isEmpty();
        assertThat(add.codeChanged()).isFalse();
        assertThat(add.backendVersionChanges()).isEmpty();   // svc 2.0 on both sides — no backend bump
        assertThat(add.risk()).isEqualTo(ApiDiff.RISK_LOW);
        assertThat(report.getHighRiskCount()).isZero();
    }

    @Test
    void removingPayloadFieldsOnANewVersionRouteIsNotHighAndNeedsNoBackwardCompat() {
        // R9.14 (built from R9.8) REMOVES request fields (C, D) — but this is the difference between two
        // DIFFERENT version routes: R9.14 is the new app's route, R9.8 is the unchanged BAU route the old app
        // still calls. So the removal is new-version-scoped and intentional — NOT backward-incompatible. It must
        // be Low with no backward-compat (the genuine High+BC payload case is an in-place edit to the BAU route
        // itself, which is git-detected as a bauRouteEdit, not this version diff).
        RouteTraceService svc = new RouteTraceService("src/test/resources/payload-remove");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.14", null, null));

        ApiDiff rem = diffFor(report, "removeApi");
        assertThat(rem.status()).isEqualTo(ApiDiff.CHANGED);
        assertThat(rem.payloadChange()).isNotNull();
        assertThat(rem.payloadChange().removedKeys()).contains("fieldC", "fieldD");
        assertThat(rem.payloadChange().addedKeys()).isEmpty();
        assertThat(rem.risk()).isEqualTo(ApiDiff.RISK_LOW);
        assertThat(report.getHighRiskCount()).isZero();
        assertThat(report.getBackwardCompatCount()).isZero();
    }

    @Test
    void aBeanDroppedInTheNewVersionRouteIsNotHighRisk() {
        // R9.14 (built from R9.4) does not include a bean (bean:validate) that R9.4 has — a change INTERNAL to
        // the new version-specific route. The BAU route R9.4 still runs the bean unchanged, so the old app is
        // unaffected. Scoped to the new app → LOW risk, no backward-compat. (A bean removed from the R9.4 BAU
        // route ITSELF would be a modification of R9.4 — which comparing two different version routes can't see;
        // that needs a git-diff of the BAU route, like code-change detection does for @Component classes.)
        RouteTraceService svc = new RouteTraceService("src/test/resources/bean-removed");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.14", null, null));

        ApiDiff bean = diffFor(report, "beanApi");
        assertThat(bean.status()).isEqualTo(ApiDiff.CHANGED);
        assertThat(bean.risk()).isEqualTo(ApiDiff.RISK_LOW);
        assertThat(report.getHighRiskCount()).isZero();
        assertThat(report.getBackwardCompatCount()).isZero();
    }

    @Test
    void anUnversionedBaseOnlyApiResolvesToItsBaseRouteAndReadsAsUnchanged() {
        // baseOnlyApi has only an un-versioned route (no R<ver>_ variant). At 9.5 it is
        // not part of the release — a 9.5 client still resolves to the base route — so it
        // is surfaced as UNCHANGED (behind the toggle) with a note, not dropped.
        RouteTraceService svc = new RouteTraceService("src/test/resources/svc-diff-framework");
        VersionDiffReport report = svc.versionDiff(new TraceRequest(null, "9.5", null, null));

        ApiDiff base = diffFor(report, "baseOnlyApi");
        assertThat(base.status()).isEqualTo(ApiDiff.UNCHANGED);
        assertThat(base.targetRoute()).isEqualTo("baseOnlyApi");   // the un-versioned base route
        assertThat(base.lowerRoute()).isNull();
        assertThat(base.note()).contains("No 9.5 route");
    }

    @Test
    void countsAndOrdersChangedFirstThenNew() {
        VersionDiffReport report = service.versionDiff(new TraceRequest(null, "9.4", null, null));

        // Only release-9.4 APIs are considered (those whose entry resolves to 9.4).
        assertThat(report.getApis()).allSatisfy(a ->
                assertThat(a.targetVersion()).isEqualTo("9.4"));
        // Changed APIs are listed before NEW ones.
        int firstNew = -1;
        int lastChanged = -1;
        for (int i = 0; i < report.getApis().size(); i++) {
            String status = report.getApis().get(i).status();
            if (ApiDiff.CHANGED.equals(status)) {
                lastChanged = i;
            } else if (ApiDiff.NEW.equals(status) && firstNew < 0) {
                firstNew = i;
            }
        }
        if (firstNew >= 0 && lastChanged >= 0) {
            assertThat(lastChanged).isLessThan(firstNew);
        }
    }
}
