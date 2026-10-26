package io.traceguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Release Impact ignore list: a pattern matches a bean name, a class name or a repo path, the list
 * round-trips through ignored-classes.json with blanks/comments dropped, and the seed never overwrites edits.
 */
class IgnoredClassesServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "{0} vs bean={1} file={2} -> {3}")
    @CsvSource({
            "commonAuditProcessor, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, true",
            "COMMONauditprocessor, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, true",
            "*Audit*, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, true",
            "CommonAuditProcessor, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, true",
            "**/common/*.java, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, true",
            "common/*.java, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, true",
            "**/other/*.java, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, false",
            "statusProcessor, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, false",
            "*Audit, commonAuditProcessor, src/main/java/com/example/common/CommonAuditProcessor.java, false",
    })
    void shouldMatchBeanNameClassNameOrPath(String pattern, String bean, String file, boolean expected) {
        String hit = IgnoredClassesService.matching(List.of(pattern), bean, file);
        assertThat(hit != null).isEqualTo(expected);
    }

    @Test
    void shouldReturnNullWhenNoPatternsConfigured() {
        assertThat(IgnoredClassesService.matching(List.of(), "x", "X.java")).isNull();
        assertThat(IgnoredClassesService.matching(null, "x", "X.java")).isNull();
    }

    @Test
    void shouldRoundTripPatternsAndDropBlanksCommentsAndDuplicates(@TempDir Path dir) {
        IgnoredClassesService service = new IgnoredClassesService(dir.toString(), mapper);

        List<String> saved = service.saveAll(List.of(" commonAuditProcessor ", "", "# a comment", "*Logger*, **/common/*.java", "commonAuditProcessor"));

        assertThat(saved).containsExactly("commonAuditProcessor", "*Logger*", "**/common/*.java");   // comma entry split, duplicate dropped
        assertThat(new IgnoredClassesService(dir.toString(), mapper).readAll()).isEqualTo(saved);
    }

    @Test
    void shouldIgnoreTheCommentKeyAndMissingListWhenReading(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ignored-classes.json"), "{\"_comment\":\"format hint\",\"patterns\":[\"*Logger*\"]}");
        assertThat(new IgnoredClassesService(dir.toString(), mapper).readAll()).containsExactly("*Logger*");

        Files.writeString(dir.resolve("ignored-classes.json"), "{\"_comment\":\"format hint\"}");
        assertThat(new IgnoredClassesService(dir.toString(), mapper).readAll()).isEmpty();
    }

    @Test
    void shouldSeedWhenAbsentButNeverOverwriteExisting(@TempDir Path dir) {
        IgnoredClassesService service = new IgnoredClassesService(dir.toString(), mapper);

        assertThat(service.seedIfAbsent("{\"patterns\":[\"*Logger*\"]}".getBytes())).isTrue();
        assertThat(service.seedIfAbsent("{\"patterns\":[\"*Audit*\"]}".getBytes())).isFalse();

        assertThat(service.readAll()).containsExactly("*Logger*");
    }
}
