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
        s.save("bb-secret", "npm-secret", null, null);
        assertEquals("bb-secret", s.bitbucketToken());
        assertEquals("npm-secret", s.read().npmToken());
        // A fresh instance over the same home reads the persisted file.
        assertEquals("bb-secret", svc(home).bitbucketToken());
    }

    @Test
    void nullFieldIsLeftUnchangedEmptyClears(@TempDir Path home) {
        SettingsService s = svc(home);
        s.save("bb-1", "npm-1", null, null);
        // null bitbucket -> keep; new npm -> replace
        s.save(null, "npm-2", null, null);
        assertEquals("bb-1", s.bitbucketToken());
        assertEquals("npm-2", s.read().npmToken());
        // empty string clears just that token, leaves the other
        s.save("", null, null, null);
        assertEquals("", s.bitbucketToken());
        assertEquals("npm-2", s.read().npmToken());
    }

    @Test
    void tokensAreTrimmed(@TempDir Path home) {
        SettingsService s = svc(home);
        s.save("  spaced-token  ", null, null, null);
        assertEquals("spaced-token", s.bitbucketToken());
    }

    @Test
    void savesAndMergesTheSplunkUrl(@TempDir Path home) {
        SettingsService s = svc(home);
        String url = "https://host:8000/en-US/splunkd/__raw/services/search/jobs/";
        s.save("bb", "npm", url, null);
        assertEquals(url, s.read().splunkUrl());
        // Saving only a token leaves the Splunk URL untouched (null = keep).
        s.save("bb2", null, null, null);
        assertEquals(url, s.read().splunkUrl());
        assertEquals(url, svc(home).read().splunkUrl());   // persisted across instances
    }

    @Test
    void savesMergesAndParsesThePassThreshold(@TempDir Path home) {
        SettingsService s = svc(home);
        s.save(null, null, null, "0.9");
        assertEquals(0.9, s.read().passThresholdFraction(), 1e-9);
        // A percentage like "95" is normalised to a 0..1 fraction.
        s.save(null, null, null, "95");
        assertEquals(0.95, s.read().passThresholdFraction(), 1e-9);
        // Saving a token leaves the threshold untouched (null = keep).
        s.save("bb", null, null, null);
        assertEquals(0.95, s.read().passThresholdFraction(), 1e-9);
        // Unset / invalid → null (caller falls back to the default).
        s.save(null, null, null, "");
        assertNull(s.read().passThresholdFraction());
    }

    @Test
    void writesFileUnderTheConfiguredHome(@TempDir Path home) {
        svc(home).save("x", "y", null, null);
        assertTrue(Files.exists(home.resolve("settings.json")), "settings.json should be written under the home dir");
    }
}
