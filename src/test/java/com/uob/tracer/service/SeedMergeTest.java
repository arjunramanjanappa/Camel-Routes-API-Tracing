package com.uob.tracer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 3-way seed merge: a new package can push corrected/updated defaults to existing users while every
 * user edit and deletion is preserved.
 */
class SeedMergeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private byte[] b(String json) {
        return json.getBytes();
    }

    private JsonNode merged(String base, String ours, String theirs) throws Exception {
        byte[] out = SeedMerge.merge(base == null ? null : b(base), b(ours), b(theirs), mapper);
        return mapper.readTree(out);
    }

    @Test
    void addsABrandNewDefaultTheUserDoesNotHave() throws Exception {
        JsonNode m = merged("{\"Mighty\":{\"v\":1}}",
                "{\"Mighty\":{\"v\":1}}",
                "{\"Mighty\":{\"v\":1},\"SPL\":{\"v\":2}}");
        assertThat(m.get("SPL").get("v").asInt()).isEqualTo(2);   // new default pushed
        assertThat(m.get("Mighty").get("v").asInt()).isEqualTo(1);
    }

    @Test
    void updatesAKeyTheUserNeverTouched() throws Exception {
        JsonNode m = merged("{\"Mighty\":{\"code\":\"responseCode\"}}",
                "{\"Mighty\":{\"code\":\"responseCode\"}}",       // ours == base → untouched
                "{\"Mighty\":{\"code\":\"resultCode\"}}");        // corrected default
        assertThat(m.get("Mighty").get("code").asText()).isEqualTo("resultCode");
    }

    @Test
    void keepsAKeyTheUserEdited() throws Exception {
        JsonNode m = merged("{\"Mighty\":{\"code\":\"responseCode\"}}",
                "{\"Mighty\":{\"code\":\"myCustom\"}}",           // user edited it
                "{\"Mighty\":{\"code\":\"resultCode\"}}");        // package tries to correct it
        assertThat(m.get("Mighty").get("code").asText()).isEqualTo("myCustom");   // user wins
    }

    @Test
    void doesNotResurrectAKeyTheUserDeleted() throws Exception {
        JsonNode m = merged("{\"Mighty\":{\"v\":1},\"SPL\":{\"v\":2}}",
                "{\"Mighty\":{\"v\":1}}",                          // user deleted SPL
                "{\"Mighty\":{\"v\":1},\"SPL\":{\"v\":2}}");       // seed still has it
        assertThat(m.has("SPL")).isFalse();
    }

    @Test
    void neverRemovesAKeyTheSeedNoLongerShips() throws Exception {
        JsonNode m = merged("{\"Mighty\":{\"v\":1},\"SPL\":{\"v\":2}}",
                "{\"Mighty\":{\"v\":1},\"SPL\":{\"v\":2}}",
                "{\"Mighty\":{\"v\":1}}");                         // SPL dropped from the seed
        assertThat(m.get("SPL").get("v").asInt()).isEqualTo(2);   // user's data is not wiped
    }

    @Test
    void withNoBaselineOnlyAddsNewKeysAndKeepsExistingOnes() throws Exception {
        // First merge-enabled upgrade: no baseline recorded yet → don't overwrite existing keys, only add new.
        JsonNode m = merged(null,
                "{\"Mighty\":{\"code\":\"a\"}}",
                "{\"Mighty\":{\"code\":\"b\"},\"SPL\":{\"v\":2}}");
        assertThat(m.get("Mighty").get("code").asText()).isEqualTo("a");   // existing kept
        assertThat(m.get("SPL").get("v").asInt()).isEqualTo(2);            // new added
    }

    @Test
    void jsonEqualsIsOrderIndependent() {
        assertThat(SeedMerge.jsonEquals(b("{\"a\":1,\"b\":2}"), b("{\"b\":2,\"a\":1}"), mapper)).isTrue();
        assertThat(SeedMerge.jsonEquals(b("{\"a\":1}"), b("{\"a\":2}"), mapper)).isFalse();
    }
}
