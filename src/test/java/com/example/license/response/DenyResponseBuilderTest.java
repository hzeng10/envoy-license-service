package com.example.license.response;

import com.example.license.config.DenyConfig;
import com.example.license.validator.ValidationResult;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.type.v3.StatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DenyResponseBuilderTest {

    private DenyResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DenyResponseBuilder();
    }

    @Test
    void allowReturnsOkStatus() {
        CheckResponse resp = builder.allow();
        assertThat(resp.getStatus().getCode()).isEqualTo(com.google.rpc.Code.OK_VALUE);
        assertThat(resp.hasOkResponse()).isTrue();
        assertThat(resp.hasDeniedResponse()).isFalse();
    }

    @Test
    void denyReturnsPermissionDeniedStatus() {
        CheckResponse resp = builder.deny(ValidationResult.denyDefault(), DenyConfig.DEFAULT);
        assertThat(resp.getStatus().getCode()).isEqualTo(com.google.rpc.Code.PERMISSION_DENIED_VALUE);
        assertThat(resp.hasDeniedResponse()).isTrue();
    }

    @Test
    void denyUsesResultStatus() {
        ValidationResult result = ValidationResult.deny(402, "body", Map.of());
        CheckResponse resp = builder.deny(result, DenyConfig.DEFAULT);
        assertThat(resp.getDeniedResponse().getStatus().getCode()).isEqualTo(StatusCode.PaymentRequired);
    }

    @Test
    void denyFallsBackToRuleConfigStatus() {
        ValidationResult result = ValidationResult.deny(0, null, null);
        DenyConfig ruleConfig = new DenyConfig(402, "Payment required", Map.of());
        CheckResponse resp = builder.deny(result, ruleConfig);
        assertThat(resp.getDeniedResponse().getStatus().getCode()).isEqualTo(StatusCode.PaymentRequired);
    }

    @Test
    void denyIncludesBody() {
        ValidationResult result = ValidationResult.deny(403, "custom-body", Map.of());
        CheckResponse resp = builder.deny(result, DenyConfig.DEFAULT);
        assertThat(resp.getDeniedResponse().getBody()).isEqualTo("custom-body");
    }

    @Test
    void denyBodyFallsBackToRuleConfig() {
        ValidationResult result = ValidationResult.deny(403, null, null);
        DenyConfig ruleConfig = new DenyConfig(403, "rule-body", Map.of());
        CheckResponse resp = builder.deny(result, ruleConfig);
        assertThat(resp.getDeniedResponse().getBody()).isEqualTo("rule-body");
    }

    @Test
    void denyIncludesHeaders() {
        ValidationResult result = ValidationResult.deny(403, "body", Map.of("x-custom", "value"));
        CheckResponse resp = builder.deny(result, DenyConfig.DEFAULT);
        boolean found = resp.getDeniedResponse().getHeadersList().stream()
                .anyMatch(h -> h.getHeader().getKey().equals("x-custom")
                        && h.getHeader().getValue().equals("value"));
        assertThat(found).isTrue();
    }

    @Test
    void unknownStatusCodeFallsBackToForbidden() {
        ValidationResult result = ValidationResult.deny(999, "body", null);
        CheckResponse resp = builder.deny(result, DenyConfig.DEFAULT);
        // StatusCode.forNumber(999) returns null → fallback to Forbidden
        assertThat(resp.getDeniedResponse().getStatus().getCode()).isEqualTo(StatusCode.Forbidden);
    }

    @Test
    void allowResponseIsSingleton() {
        assertThat(builder.allow()).isSameAs(builder.allow());
    }

    @Test
    void denyWithStatusZeroAndNullBodyAndNullHeaders() {
        // status=0 → StatusCode.forNumber(0) = StatusCode.Empty → fallback to Forbidden
        // body=null (from both result and ruleConfig) → setBody("")
        // headers=null (from ruleConfig) → if(headers!=null) skipped
        ValidationResult result = ValidationResult.deny(0, null, null);
        DenyConfig ruleConfig = new DenyConfig(0, null, null);
        CheckResponse resp = builder.deny(result, ruleConfig);
        assertThat(resp.getDeniedResponse().getStatus().getCode()).isEqualTo(StatusCode.Forbidden);
        assertThat(resp.getDeniedResponse().getBody()).isEqualTo("");
        assertThat(resp.getDeniedResponse().getHeadersList()).isEmpty();
    }
}
