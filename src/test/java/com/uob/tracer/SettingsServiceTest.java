package com.uob.tracer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uob.tracer.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Save/merge/clear semantics of the machine-wide {@link SettingsService} token store. */
class SettingsServiceTest {

    private SettingsService svc(Path home) {
        return new SettingsService(home.toString(), new ObjectMapper());
    }

    @Test
    void emptyWhenNothingSaved(@TempDir Path home) {
        SettingsService s = svc(home);
        assertEquals("", s.bitbucketToken());
        assertFalse(s.read().bitbucketToken().length() > 0);
        assertEquals("", s.read().npmToken());
    }

    @Test
    void savesAndReadsBackTokens(@TempDir Path home) {
        SettingsService s = svc(home);
        s.save("bb-secret", "npm-secret");
        assertEquals("bb-secret", s.bitbucketToken());
        assertEquals("npm-secret", s.read().npmToken());
        // A fresh instance over the same home reads the persisted file.
        assertEquals("bb-secret", svc(home).bitbucketToken());
    }

    @Test
    void nullFieldIsLeftUnchangedEmptyClears(@TempDir Path home) {
        SettingsService s = svc(home);
        s.save("bb-1", "npm-1");
        // null bitbucket -> keep; new npm -> replace
        s.save(null, "npm-2");
        assertEquals("bb-1", s.bitbucketToken());
        assertEquals("npm-2", s.read().npmToken());
        // empty string clears just that token, leaves the other
        s.save("", null);
        assertEquals("", s.bitbucketToken());
        assertEquals("npm-2", s.read().npmToken());
    }

    @Test
    void tokensAreTrimmed(@TempDir Path home) {
        SettingsService s = svc(home);
        s.save("  spaced-token  ", null);
        assertEquals("spaced-token", s.bitbucketToken());
    }

    @Test
    void writesFileUnderTheConfiguredHome(@TempDir Path home) {
        svc(home).save("x", "y");
        assertTrue(Files.exists(home.resolve("settings.json")), "settings.json should be written under the home dir");
    }
}
