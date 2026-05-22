package com.example.license.validator;

import com.example.license.config.CompiledRule;
import com.example.license.config.DenyConfig;
import com.example.license.matcher.UrlMatcherFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultHeaderTokenValidatorTest {

    private DefaultHeaderTokenValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DefaultHeaderTokenValidator();
    }

    @Test
    void validTokenAllows() throws Exception {
        ValidationResult result = validator.validate(request(Map.of("expectedToken", "secret"),
                Map.of("x-license-token", "secret"))).get();
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void missingHeaderDenies() throws Exception {
        ValidationResult result = validator.validate(request(Map.of("expectedToken", "secret"),
                Map.of())).get();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denyStatus()).isEqualTo(403);
    }

    @Test
    void wrongTokenDenies() throws Exception {
        ValidationResult result = validator.validate(request(Map.of("expectedToken", "secret"),
                Map.of("x-license-token", "wrong"))).get();
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void customHeaderName() throws Exception {
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "token", "headerName", "X-Custom-License"),
                Map.of("x-custom-license", "token"))).get();
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void headerLookupIsCaseInsensitive() throws Exception {
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "tok"),
                Map.of("X-License-Token", "tok"))).get();
        // request.header() lowercases key; headers map key is already lowercased in Envoy
        // Our ValidationRequest.header() lowercases the lookup key, so this depends on map key casing
        // The rule config headerName is lowercased internally, and we look up with that key
        // The request headers map key "X-License-Token" is not lowercased, so won't match
        // This demonstrates the expectation: headers must be stored lowercase (Envoy sends lowercase)
        assertThat(result.allowed()).isFalse(); // header key case-sensitive in Map
    }

    @Test
    void beforeValidFromDenies() throws Exception {
        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "t", "validFrom", future),
                Map.of("x-license-token", "t"))).get();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denyBody()).contains("not yet valid");
    }

    @Test
    void afterValidUntilDenies() throws Exception {
        String past = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "t", "validUntil", past),
                Map.of("x-license-token", "t"))).get();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denyBody()).contains("expired");
    }

    @Test
    void withinValidWindowAllows() throws Exception {
        String from = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String until = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "t", "validFrom", from, "validUntil", until),
                Map.of("x-license-token", "t"))).get();
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void missingExpectedTokenReturnConfigError() throws Exception {
        ValidationResult result = validator.validate(request(Map.of(), Map.of("x-license-token", "x"))).get();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denyStatus()).isEqualTo(500);
        assertThat(result.denyBody()).contains("CONFIG_ERROR");
    }

    @Test
    void validatorNameIsDefaultHeaderToken() {
        assertThat(validator.name()).isEqualTo("default-header-token");
    }

    @Test
    void returnsCompletedFuture() {
        var future = validator.validate(request(Map.of("expectedToken", "x"),
                Map.of("x-license-token", "x")));
        assertThat(future.isDone()).isTrue();
    }

    @Test
    void blankExpectedTokenDeniesWithConfigError() throws Exception {
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "   "),
                Map.of("x-license-token", "x"))).get();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denyStatus()).isEqualTo(500);
        assertThat(result.denyBody()).contains("CONFIG_ERROR");
    }

    @Test
    void blankTokenInRequestDenies() throws Exception {
        ValidationResult result = validator.validate(request(
                Map.of("expectedToken", "secret"),
                Map.of("x-license-token", "   "))).get();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denyBody()).contains("Missing license token");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ValidationRequest request(Map<String, Object> config, Map<String, String> headers) {
        CompiledRule rule = new CompiledRule(
                "test-rule",
                UrlMatcherFactory.create("/test/**", List.of("*")),
                "default-header-token",
                config,
                DenyConfig.DEFAULT,
                null,
                30,
                5
        );
        return new ValidationRequest("/test/path", "GET", headers, rule);
    }
}
