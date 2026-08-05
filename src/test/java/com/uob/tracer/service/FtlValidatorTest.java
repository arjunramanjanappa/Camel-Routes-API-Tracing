package com.uob.tracer.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FTL template validation: FTL syntax (parse) + JSON structure (render a stub model, parse the output). */
class FtlValidatorTest {

    private List<FtlValidator.Issue> validate(String ftl) {
        return FtlValidator.validate("test.ftl", ftl);
    }

    @Test
    void aValidTemplateWithDirectivesAndInterpolationsHasNoIssues() {
        String ftl = """
                <#-- request body -->
                {
                  "channelId": "${channel}",
                  <#if amount??>"amount": ${amount},</#if>
                  "items": [
                    <#list order.items as it>{ "sku": "${it.sku}" }<#sep>,</#list>
                  ],
                  "serviceVersionNumber": "2.3"
                }
                """;
        assertThat(validate(ftl)).isEmpty();
    }

    @Test
    void aMissingCommaBetweenFieldsIsAStructureIssueWithLine() {
        // No comma after "a" (line 3) — valid FTL, but the rendered output isn't valid JSON. The finding must
        // carry the line where the JSON parser trips (line 4, the "b" that needed a comma before it).
        String ftl = "{\n  \"a\": \"${x}\"\n  \"b\": \"${y}\"\n}";
        assertThat(validate(ftl)).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("STRUCTURE");
            assertThat(i.line()).isEqualTo(3);   // JSON parser reports the token that broke the expectation
        });
    }

    @Test
    void aTrailingCommaIsAStructureIssue() {
        String ftl = "{ \"a\": \"${x}\", \"b\": \"${y}\", }";
        assertThat(validate(ftl)).anySatisfy(i -> assertThat(i.kind()).isEqualTo("STRUCTURE"));
    }

    @Test
    void anUnclosedDirectiveIsASyntaxIssueWithLine() {
        String ftl = "{\n  <#if x>\n  \"a\": 1\n}\n";   // no </#if>
        assertThat(validate(ftl)).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("SYNTAX");
            assertThat(i.line()).isGreaterThan(0);
        });
    }

    @Test
    void aMalformedInterpolationIsASyntaxIssue() {
        String ftl = "{ \"a\": \"${x\" }";   // unterminated ${
        assertThat(validate(ftl)).anySatisfy(i -> assertThat(i.kind()).isEqualTo("SYNTAX"));
    }

    @Test
    void blankOrNullIsNoIssue() {
        assertThat(validate("")).isEmpty();
        assertThat(FtlValidator.validate("x.ftl", null)).isEmpty();
    }
}
