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

    // The same <to> step nested one level deeper (indentation differs) must NOT read as a removed/added step;
    // only the wrapper the release actually added shows up.
    private static final String ROUTE_WRAPPED = """
            <routes xmlns="http://camel.apache.org/schema/spring">
              <route id="R9.4_foo">
                <from uri="direct:R9.4_foo"/>
                <choice>
                  <when>
                    <simple>${header.x} == 'Y'</simple>
                    <to uri="velocity:test.vm"/>
                  </when>
                </choice>
              </route>
            </routes>
            """;

    @Test
    void aStepMovedDeeperIsNotFlagged_onlyTheAddedWrapperIs() {
        RouteXmlDiff.Diff d = RouteXmlDiff.diff(body(ROUTE_A), body(ROUTE_WRAPPED));
        // The <to> step is preserved (indentation ignored) — not reported as removed.
        assertThat(d.removed()).noneMatch(s -> s.contains("velocity:test.vm"));
        // The genuinely-new wrapper still surfaces.
        assertThat(d.added()).anyMatch(s -> s.startsWith("choice"));
    }

    // A file holds several route versions. The release commit changed ONE of them (R8.15) and only
    // re-indented the other (R8.16). The diff is per-route: R8.16 must read as unchanged even though its
    // FILE was in the commit; R8.15 is still detected.
    private static final String FILE_BEFORE = """
            <routes xmlns="http://camel.apache.org/schema/spring">
              <route id="R8.15_summary"><from uri="direct:R8.15_summary"/><to uri="velocity:old.vm"/></route>
              <route id="R8.16_summary"><from uri="direct:R8.16_summary"/><to uri="velocity:insurance.vm"/></route>
            </routes>
            """;
    private static final String FILE_AFTER = """
            <routes xmlns="http://camel.apache.org/schema/spring">
              <route id="R8.15_summary"><from uri="direct:R8.15_summary"/><to uri="velocity:new.vm"/></route>
              <route id="R8.16_summary">
                  <from uri="direct:R8.16_summary"/>
                        <to uri="velocity:insurance.vm "/>
              </route>
            </routes>
            """;

    @Test
    void aRouteWhoseFileWasCommittedButBodyOnlyReindentedIsNotAChange() {
        var before = RouteXmlDiff.bodiesFromXml(FILE_BEFORE);
        var after = RouteXmlDiff.bodiesFromXml(FILE_AFTER);
        // R8.16: only re-indented + a stray space in the uri value → NOT a change.
        assertThat(RouteXmlDiff.diff(before.get("R8.16_summary"), after.get("R8.16_summary")).isEmpty()).isTrue();
        // R8.15: a real .vm swap in the same file → still a change.
        assertThat(RouteXmlDiff.diff(before.get("R8.15_summary"), after.get("R8.15_summary")).isEmpty()).isFalse();
    }
}
