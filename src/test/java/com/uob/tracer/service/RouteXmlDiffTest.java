package com.uob.tracer.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The route-body diff must ignore whitespace/tab-only edits (re-indent, stray space in an attribute value)
 *  so a formatting commit isn't flagged as a BAU route change, while a real content change still is. */
class RouteXmlDiffTest {

    private static final String ROUTE_A = """
            <routes xmlns="http://camel.apache.org/schema/spring">
              <route id="R9.4_foo">
                <from uri="direct:R9.4_foo"/>
                <to uri="velocity:test.vm"/>
              </route>
            </routes>
            """;

    // Same route, re-indented with tabs AND a stray trailing space inside the uri value — no behavioural change.
    private static final String ROUTE_WHITESPACE = """
            <routes xmlns="http://camel.apache.org/schema/spring">
            \t<route id="R9.4_foo">
            \t\t<from uri="direct:R9.4_foo"/>
            \t\t<to uri="velocity:test.vm "/>
            \t</route>
            </routes>
            """;

    private List<String> body(String xml) {
        return RouteXmlDiff.bodiesFromXml(xml).get("R9.4_foo");
    }

    @Test
    void aWhitespaceOnlyEditIsNotAChange() {
        assertThat(RouteXmlDiff.diff(body(ROUTE_A), body(ROUTE_WHITESPACE)).isEmpty()).isTrue();
    }

    @Test
    void aRealAttributeValueChangeIsStillDetected() {
        String changed = ROUTE_A.replace("velocity:test.vm", "velocity:other.vm");
        assertThat(RouteXmlDiff.diff(body(ROUTE_A), body(changed)).isEmpty()).isFalse();
    }
}
