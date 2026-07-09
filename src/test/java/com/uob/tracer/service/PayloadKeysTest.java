package com.uob.tracer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON-key extraction + diff that drives the release-diff "Payload change". */
class PayloadKeysTest {

    @Test
    void sameKeysAcrossVelocityAndFreemarkerIsNotAChange() {
        // OLD: Velocity, single quotes, a #set directive and $ interpolations.
        String vm = """
                #set( $ctx = 'x' )
                {
                  'ServiceContext': {
                    'channelId': 'MTY',
                    'amount': $amount,
                    'serviceVersionNumber': '2.2'
                  }
                }
                """;
        // NEW: FreeMarker, double quotes, <#if> directive and ${} interpolations — SAME keys.
        String ftl = """
                <#-- request body -->
                {
                  "ServiceContext": {
                    "channelId": "${channel}",
                    <#if amount??>"amount": ${amount},</#if>
                    "serviceVersionNumber": "2.3"
                  }
                }
                """;
        PayloadKeys.PayloadDiff d = PayloadKeys.diff(PayloadKeys.extract(ftl), PayloadKeys.extract(vm));
        // Engine and serviceVersionNumber bump differ, but the key set is identical -> no payload change.
        assertThat(d.isEmpty()).isTrue();
    }

    @Test
    void addedAndRemovedKeysAreReported() {
        String lower = "{ \"ServiceContext\": { \"channelId\": \"x\", \"oldField\": \"y\" } }";
        String target = "{ \"ServiceContext\": { \"channelId\": \"x\", \"newField\": \"z\" } }";
        PayloadKeys.PayloadDiff d = PayloadKeys.diff(PayloadKeys.extract(target), PayloadKeys.extract(lower));
        assertThat(d.added()).containsExactly("newField");
        assertThat(d.removed()).containsExactly("oldField");
    }

    @Test
    void serviceVersionNumberOnlyChangeIsNotAPayloadChange() {
        String lower = "{ \"channelId\": \"x\", \"serviceVersionNumber\": \"2.2\" }";
        String target = "{ \"channelId\": \"x\", \"serviceVersionNumber\": \"2.3\" }";
        assertThat(PayloadKeys.diff(PayloadKeys.extract(target), PayloadKeys.extract(lower)).isEmpty()).isTrue();
    }

    @Test
    void sameKeyNameUnderDifferentObjectsIsQualified() {
        // "amount" appears under both Header and Body -> it must be qualified to distinguish.
        String lower = "{ \"Header\": { \"amount\": 1 } }";
        String target = "{ \"Header\": { \"amount\": 1 }, \"Body\": { \"amount\": 2 } }";
        PayloadKeys.PayloadDiff d = PayloadKeys.diff(PayloadKeys.extract(target), PayloadKeys.extract(lower));
        // Body.amount is the new one; Header.amount unchanged. Body itself is also new.
        assertThat(d.added()).contains("Body.amount", "Body");
        assertThat(d.removed()).isEmpty();
    }

    @Test
    void nestedObjectKeysAreCaptured() {
        var refs = PayloadKeys.extract("{ \"ServiceContext\": { \"channelId\": \"x\" } }");
        assertThat(refs).extracting(PayloadKeys.KeyRef::name).contains("ServiceContext", "channelId");
        assertThat(refs.stream().filter(r -> r.name().equals("channelId")).findFirst().orElseThrow().parent())
                .isEqualTo("ServiceContext");
    }
}
