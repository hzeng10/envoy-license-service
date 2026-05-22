package com.example.license.validator;

import com.example.license.config.CompiledRule;
import com.example.license.config.DenyConfig;
import com.example.license.matcher.UrlMatcherFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationRequestTest {

    @Test
    void headerReturnsValue() {
        ValidationRequest req = request(Map.of("x-token", "abc"));
        assertThat(req.header("x-token")).isEqualTo("abc");
    }

    @Test
    void headerIsCaseInsensitiveOnLookup() {
        ValidationRequest req = request(Map.of("x-license-token", "val"));
        // header() lowercases the name argument before looking up
        assertThat(req.header("X-License-Token")).isEqualTo("val");
    }

    @Test
    void headerReturnsNullWhenMissing() {
        ValidationRequest req = request(Map.of());
        assertThat(req.header("missing")).isNull();
    }

    @Test
    void headerReturnsNullForNullName() {
        ValidationRequest req = request(Map.of("x", "y"));
        assertThat(req.header(null)).isNull();
    }

    @Test
    void headerReturnsNullWhenHeadersNull() {
        CompiledRule rule = new CompiledRule("r", UrlMatcherFactory.create("/a/**", List.of("*")),
                "default-header-token", Map.of(), DenyConfig.DEFAULT, null, 30, 5);
        ValidationRequest req = new ValidationRequest("/a/b", "GET", null, rule);
        assertThat(req.header("any")).isNull();
    }

    private static ValidationRequest request(Map<String, String> headers) {
        CompiledRule rule = new CompiledRule("r", UrlMatcherFactory.create("/test/**", List.of("*")),
                "default-header-token", Map.of(), DenyConfig.DEFAULT, null, 30, 5);
        return new ValidationRequest("/test/path", "GET", headers, rule);
    }
}
