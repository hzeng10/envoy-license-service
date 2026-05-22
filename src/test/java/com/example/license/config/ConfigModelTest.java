package com.example.license.config;

import com.example.license.matcher.UrlMatcherFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigModelTest {

    @Test
    void denyConfigMergeOverridesStatus() {
        DenyConfig base = new DenyConfig(403, "base", Map.of("a", "1"));
        DenyConfig override = new DenyConfig(402, null, null);
        DenyConfig merged = base.mergeWith(override);
        assertThat(merged.status()).isEqualTo(402);
        assertThat(merged.body()).isEqualTo("base");
        assertThat(merged.headers()).containsKey("a");
    }

    @Test
    void denyConfigMergeOverridesBodyAndHeaders() {
        DenyConfig base = DenyConfig.DEFAULT;
        DenyConfig override = new DenyConfig(0, "new body", Map.of("x", "y"));
        DenyConfig merged = base.mergeWith(override);
        assertThat(merged.body()).isEqualTo("new body");
        assertThat(merged.headers()).containsEntry("x", "y");
        assertThat(merged.status()).isEqualTo(403); // unchanged
    }

    @Test
    void denyConfigMergeWithNullReturnsOriginal() {
        DenyConfig base = DenyConfig.DEFAULT;
        assertThat(base.mergeWith(null)).isSameAs(base);
    }

    @Test
    void remoteConfigMergeOverridesTimeout() {
        RemoteConfig base = RemoteConfig.DEFAULT;
        RemoteConfig override = new RemoteConfig(50, 0, null, null);
        RemoteConfig merged = base.mergeWith(override);
        assertThat(merged.timeoutMs()).isEqualTo(50);
        assertThat(merged.bulkheadMaxConcurrent()).isEqualTo(base.bulkheadMaxConcurrent());
    }

    @Test
    void remoteConfigMergeWithNullReturnsOriginal() {
        RemoteConfig base = RemoteConfig.DEFAULT;
        assertThat(base.mergeWith(null)).isSameAs(base);
    }

    @Test
    void ruleSetMatchReturnsFirstRule() {
        CompiledRule r1 = new CompiledRule("r1",
                UrlMatcherFactory.create("/api/**", List.of("GET")),
                "default-header-token", Map.of(), DenyConfig.DEFAULT, null, 30, 5);
        CompiledRule r2 = new CompiledRule("r2",
                UrlMatcherFactory.create("/api/**", List.of("POST")),
                "default-header-token", Map.of(), DenyConfig.DEFAULT, null, 30, 5);
        RuleSet rs = new RuleSet(List.of(r1, r2), DenyConfig.DEFAULT, true);
        assertThat(rs.match("/api/x", "GET")).isEqualTo(r1);
        assertThat(rs.match("/api/x", "POST")).isEqualTo(r2);
    }

    @Test
    void ruleSetMatchReturnsNullWhenNoMatch() {
        RuleSet rs = new RuleSet(List.of(
                new CompiledRule("r1",
                        UrlMatcherFactory.create("/api/**", List.of("GET")),
                        "default-header-token", Map.of(), DenyConfig.DEFAULT, null, 30, 5)
        ), DenyConfig.DEFAULT, true);
        assertThat(rs.match("/other/path", "GET")).isNull();
    }

    @Test
    void circuitBreakerConfigDefaults() {
        assertThat(CircuitBreakerConfig.DEFAULT.failureRateThreshold()).isEqualTo(50.0f);
        assertThat(CircuitBreakerConfig.DEFAULT.slidingWindowSize()).isEqualTo(50);
        assertThat(CircuitBreakerConfig.DEFAULT.openStateSeconds()).isEqualTo(10);
        assertThat(CircuitBreakerConfig.DEFAULT.halfOpenPermitted()).isEqualTo(5);
    }

    @Test
    void remoteConfigDefaults() {
        assertThat(RemoteConfig.DEFAULT.timeoutMs()).isEqualTo(100);
        assertThat(RemoteConfig.DEFAULT.bulkheadMaxConcurrent()).isEqualTo(64);
        assertThat(RemoteConfig.DEFAULT.onFailure()).isEqualTo(RemoteConfig.FailurePolicy.FAIL_CLOSED);
    }

    @Test
    void validationResultAllow() {
        var r = com.example.license.validator.ValidationResult.allow();
        assertThat(r.allowed()).isTrue();
        assertThat(r.denyStatus()).isEqualTo(0);
    }

    @Test
    void validationResultDenyDefault() {
        var r = com.example.license.validator.ValidationResult.denyDefault();
        assertThat(r.allowed()).isFalse();
        assertThat(r.denyStatus()).isEqualTo(403);
    }

    @Test
    void cacheKeyEquality() {
        var k1 = new com.example.license.validator.support.CacheKey("r", "t");
        var k2 = new com.example.license.validator.support.CacheKey("r", "t");
        assertThat(k1).isEqualTo(k2).hasSameHashCodeAs(k2);
    }
}
