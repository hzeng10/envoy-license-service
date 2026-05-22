package com.example.license.matcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlMatcherFactoryTest {

    @ParameterizedTest(name = "ant [{0}] matches [{1}] = {2}")
    @CsvSource({
        "/api/**,          /api/orders,      GET,   true",
        "/api/**,          /api/orders/123,  POST,  true",
        "/api/**,          /other/path,      GET,   false",
        "/api/v1/*,        /api/v1/users,    GET,   true",
        "/api/v1/*,        /api/v1/a/b,      GET,   false",
        "/v?/endpoint,     /v1/endpoint,     GET,   true",
        "/v?/endpoint,     /v10/endpoint,    GET,   false",
    })
    void antPatterns(String pattern, String path, String method, boolean expected) {
        UrlMatcher matcher = UrlMatcherFactory.create(pattern, List.of("*"));
        assertThat(matcher.matches(path, method)).isEqualTo(expected);
    }

    @Test
    void exactMatch() {
        UrlMatcher m = UrlMatcherFactory.create("exact:/admin/health", List.of("GET"));
        assertThat(m.matches("/admin/health", "GET")).isTrue();
        assertThat(m.matches("/admin/health/", "GET")).isFalse();
        assertThat(m.matches("/admin", "GET")).isFalse();
    }

    @Test
    void exactMatchIgnoresQueryString() {
        UrlMatcher m = UrlMatcherFactory.create("exact:/foo", List.of("GET"));
        assertThat(m.matches("/foo?bar=1", "GET")).isTrue();
    }

    @Test
    void regexMatch() {
        UrlMatcher m = UrlMatcherFactory.create("regex:^/items/[0-9]+$", List.of("GET"));
        assertThat(m.matches("/items/42", "GET")).isTrue();
        assertThat(m.matches("/items/abc", "GET")).isFalse();
        assertThat(m.matches("/items/", "GET")).isFalse();
    }

    @Test
    void methodFilter() {
        UrlMatcher m = UrlMatcherFactory.create("/api/**", List.of("GET", "POST"));
        assertThat(m.matches("/api/foo", "GET")).isTrue();
        assertThat(m.matches("/api/foo", "POST")).isTrue();
        assertThat(m.matches("/api/foo", "DELETE")).isFalse();
    }

    @Test
    void wildcardMethodMatchesAll() {
        UrlMatcher m = UrlMatcherFactory.create("/api/**", List.of("*"));
        assertThat(m.matches("/api/foo", "DELETE")).isTrue();
        assertThat(m.matches("/api/foo", "PUT")).isTrue();
    }

    @Test
    void emptyMethodListMatchesAll() {
        UrlMatcher m = UrlMatcherFactory.create("/api/**", List.of());
        assertThat(m.matches("/api/foo", "PATCH")).isTrue();
    }

    @Test
    void methodIsCaseInsensitive() {
        UrlMatcher m = UrlMatcherFactory.create("/api/**", List.of("get"));
        assertThat(m.matches("/api/foo", "GET")).isTrue();
        assertThat(m.matches("/api/foo", "get")).isTrue();
    }

    @Test
    void queryStringStrippedForAnt() {
        UrlMatcher m = UrlMatcherFactory.create("/api/**", List.of("*"));
        assertThat(m.matches("/api/foo?x=1&y=2", "GET")).isTrue();
    }

    @Test
    void blankPathExpressionThrows() {
        assertThatThrownBy(() -> UrlMatcherFactory.create("", List.of("GET")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlMatcherFactory.create(null, List.of("GET")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMethodMatchesAll() {
        UrlMatcher m = UrlMatcherFactory.create("/api/**", null);
        assertThat(m.matches("/api/foo", "DELETE")).isTrue();
    }
}
