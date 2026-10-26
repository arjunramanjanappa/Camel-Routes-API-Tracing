package io.traceguard.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The UI cache policy's page detection: the SPA shell (root, .html, extension-less paths) must never be cached,
 * while API calls and hashed assets are not treated as pages.
 */
class UiCacheFilterTest {

    @ParameterizedTest(name = "{0} -> page={1}")
    @CsvSource({
            "/, true",
            "'', true",
            "/index.html, true",
            "/route-tracer, true",
            "/route-tracer.html, true",
            "/internal/ui-version, false",
            "/internal/log-analysis-multi, false",
            "/assets/index-BnbRuSGs.js, false",
            "/favicon.ico, false",
            "/assets/index-abc.css, false",
    })
    void shouldTreatOnlyTheSpaShellAsAPage(String path, boolean expected) {
        assertThat(UiCacheFilter.isPage(path)).isEqualTo(expected);
    }
}
