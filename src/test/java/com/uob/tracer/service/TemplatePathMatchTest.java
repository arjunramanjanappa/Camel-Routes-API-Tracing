package com.uob.tracer.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A BAU route's template must be matched to the release-changed file by its FULL path, not just its file name.
 * Two versions of a template share the same name ({@code enquiry.ftl}) under different folders
 * ({@code sg/v1} vs {@code sg/v2}); a release that changed one must never be attributed to a route sending the
 * other. This guards the "API modified a BAU route in place" false positive where R9.10 (sending sg/v1) was
 * flagged with a payload change because a same-named sg/v2 template was touched by the release.
 */
class TemplatePathMatchTest {

    @Test
    void uriPathIsStrippedToItsCanonicalTemplatePath() {
        assertThat(RouteTraceService.templateUriPath("freemarker:META-INF/templates/sg/v1/enquiry.ftl"))
                .isEqualTo("META-INF/templates/sg/v1/enquiry.ftl");
        assertThat(RouteTraceService.templateUriPath("velocity:/META-INF/templates/sg/v2/enquiry.vm?foo=bar"))
                .isEqualTo("META-INF/templates/sg/v2/enquiry.vm");
        assertThat(RouteTraceService.templateUriPath("freemarker:classpath:META-INF/templates/sg/v1/enquiry.ftl"))
                .isEqualTo("META-INF/templates/sg/v1/enquiry.ftl");
    }

    @Test
    void aSameNamedSiblingTemplateIsNotMatched() {
        // The release changed only sg/v2. A route sending sg/v1 must NOT match — no phantom payload change.
        Set<String> changed = Set.of("apps/wealth/src/main/resources/META-INF/templates/sg/v2/enquiry.ftl");
        assertThat(RouteTraceService.changedTemplate("META-INF/templates/sg/v1/enquiry.ftl", changed)).isNull();
    }

    @Test
    void theExactTemplateThatChangedIsMatched() {
        String v1 = "apps/wealth/src/main/resources/META-INF/templates/sg/v1/enquiry.ftl";
        Set<String> changed = Set.of(v1, "apps/wealth/src/main/resources/META-INF/templates/sg/v2/enquiry.ftl");
        assertThat(RouteTraceService.changedTemplate("META-INF/templates/sg/v1/enquiry.ftl", changed)).isEqualTo(v1);
    }

    @Test
    void nothingMatchesWhenTheReleaseChangedNoTemplate() {
        assertThat(RouteTraceService.changedTemplate("META-INF/templates/sg/v1/enquiry.ftl", Set.of())).isNull();
        assertThat(RouteTraceService.changedTemplate("", Set.of("a/b/enquiry.ftl"))).isNull();
    }
}
