package com.uob.tracer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uob.tracer.service.LogRulesService;
import com.uob.tracer.service.LogRulesService.AppRules;
import com.uob.tracer.service.LogRulesService.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The per-app host response-code rule store (log-rules.json): persistence, app keying, matching. */
class LogRulesServiceTest {

    private LogRulesService svc(Path home) {
        return new LogRulesService(home.toString(), new ObjectMapper());
    }

    @Test
    void emptyWhenNothingSaved(@TempDir Path home) {
        LogRulesService s = svc(home);
        assertTrue(s.readAll().isEmpty());
        assertTrue(s.rulesFor("Mighty", false).rules().isEmpty());
        // responseCode is always tried even with no config.
        assertEquals(List.of("responseCode"), s.rulesFor("Mighty", false).effectiveCodeFields());
    }

    @Test
    void savesReadsBackAndKeysBySecureFlavour(@TempDir Path home) {
        LogRulesService s = svc(home);
        AppRules mighty = new AppRules(List.of("resultCode"),
                List.of(new Rule("*/host/xyz", "resultCode", List.of("000000"), false)));
        s.saveApp("SPL", true, mighty);   // stored under SPL-Secure

        assertEquals("SPL-Secure", LogRulesService.appKey("SPL", true));
        assertEquals("Mighty", LogRulesService.appKey("Mighty", false));
        AppRules back = svc(home).rulesFor("SPL", true);   // fresh instance reads the file
        assertEquals(List.of("responseCode", "resultCode"), back.effectiveCodeFields());
        assertEquals(1, back.rules().size());
        assertTrue(Files.exists(home.resolve("log-rules.json")));
        // A different app/flavour is untouched (merge-save).
        assertTrue(svc(home).rulesFor("Mighty", false).rules().isEmpty());
    }

    @Test
    void ruleForMatchesHosturlByGlobInOrder(@TempDir Path home) {
        AppRules rules = new AppRules(List.of(), List.of(
                new Rule("*/host/limit/*", null, List.of(), true),
                new Rule("*/host/xyz", "resultCode", List.of("000000", "200"), false)));

        assertTrue(rules.ruleFor("/host-mng/host/limit/initiate").skip());
        Rule xyz = rules.ruleFor("/bfs/host/xyz");
        assertNotNull(xyz);
        assertEquals("resultCode", xyz.codeField());
        assertEquals(List.of("000000", "200"), xyz.successCodes());
        assertNull(rules.ruleFor("/some/other/path"));   // no rule matches
    }

    @Test
    void globMatchIsCaseInsensitiveWholeString() {
        assertTrue(LogRulesService.globMatches("*/host/xyz", "/BFS/HOST/XYZ"));
        assertTrue(LogRulesService.globMatches("/host/limit/?", "/host/limit/1"));
        assertFalse(LogRulesService.globMatches("*/host/xyz", "/host/xyz/more"));   // whole-string: trailing text fails
    }
}
