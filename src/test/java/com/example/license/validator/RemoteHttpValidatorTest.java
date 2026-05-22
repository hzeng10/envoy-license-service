package com.example.license.validator;

import com.example.license.config.CompiledRule;
import com.example.license.config.DenyConfig;
import com.example.license.matcher.UrlMatcherFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteHttpValidatorTest {

    private HttpServer server;
    private int port;
    private volatile int responseStatus = 200;
    private AtomicInteger hitCount;
    private RemoteHttpValidator validator;

    @BeforeAll
    void startStubServer() throws IOException {
        hitCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/validate", exchange -> {
            hitCount.incrementAndGet();
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        validator = new RemoteHttpValidator();
    }

    @AfterAll
    void stopStubServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void allowOn200() throws Exception {
        responseStatus = 200;
        ValidationResult r = validator.validate(requestFor("token-ok")).get();
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void denyOn401() throws Exception {
        responseStatus = 401;
        ValidationResult r = validator.validate(requestFor("bad-token")).get();
        assertThat(r.allowed()).isFalse();
        assertThat(r.denyStatus()).isEqualTo(403);
    }

    @Test
    void denyOn403() throws Exception {
        responseStatus = 403;
        ValidationResult r = validator.validate(requestFor("expired-token")).get();
        assertThat(r.allowed()).isFalse();
    }

    @Test
    void unexpectedStatusDeniesWithDefault() throws Exception {
        responseStatus = 500;
        ValidationResult r = validator.validate(requestFor("any-token")).get();
        assertThat(r.allowed()).isFalse();
    }

    @Test
    void missingEndpointDenies() throws Exception {
        CompiledRule rule = new CompiledRule(
                "no-endpoint-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of(),    // no endpoint
                DenyConfig.DEFAULT,
                null,
                30, 5
        );
        ValidationResult r = validator.validate(new ValidationRequest("/api/x", "GET",
                Map.of("x-license-token", "t"), rule)).get();
        assertThat(r.allowed()).isFalse();
    }

    @Test
    void validatorName() {
        assertThat(validator.name()).isEqualTo("remote-http");
    }

    @Test
    void connectionErrorDenies() throws Exception {
        CompiledRule rule = new CompiledRule(
                "bad-host-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of("endpoint", "http://127.0.0.1:1"),  // port 1 = connection refused
                DenyConfig.DEFAULT,
                null,
                30, 5
        );
        ValidationResult r = validator.validate(new ValidationRequest("/api/x", "GET",
                Map.of("x-license-token", "t"), rule)).get();
        assertThat(r.allowed()).isFalse();
    }

    @Test
    void blankEndpointDenies() throws Exception {
        CompiledRule rule = new CompiledRule(
                "blank-endpoint-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of("endpoint", "   "),
                DenyConfig.DEFAULT, null, 30, 5
        );
        ValidationResult r = validator.validate(
                new ValidationRequest("/api/x", "GET", Map.of(), rule)).get();
        assertThat(r.allowed()).isFalse();
    }

    @Test
    void unsupportedMethodDefaultsToPost() throws Exception {
        responseStatus = 200;
        CompiledRule rule = new CompiledRule(
                "delete-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of("endpoint", "http://127.0.0.1:" + port + "/validate", "method", "DELETE"),
                DenyConfig.DEFAULT, null, 30, 5
        );
        // "DELETE" is not GET or POST → default branch in switch → falls through to POST
        ValidationResult r = validator.validate(
                new ValidationRequest("/api/x", "DELETE", Map.of("x-license-token", "t"), rule)).get();
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void noTokenHeaderSkipsTokenForwarding() throws Exception {
        responseStatus = 200;
        CompiledRule rule = new CompiledRule(
                "no-token-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of("endpoint", "http://127.0.0.1:" + port + "/validate"),
                DenyConfig.DEFAULT, null, 30, 5
        );
        // No token header in request → token is null → buildRequest skips adding token header
        ValidationResult r = validator.validate(
                new ValidationRequest("/api/x", "GET", Map.of(), rule)).get();
        assertThat(r.allowed()).isTrue();
    }

    private ValidationRequest requestFor(String token) {
        CompiledRule rule = new CompiledRule(
                "remote-test-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of(
                        "endpoint", "http://127.0.0.1:" + port + "/validate",
                        "method", "POST",
                        "tokenHeader", "x-license-token",
                        "successStatus", List.of(200),
                        "denyStatus", List.of(401, 402, 403)
                ),
                DenyConfig.DEFAULT,
                null,
                30, 5
        );
        return new ValidationRequest("/api/resource", "GET",
                Map.of("x-license-token", token), rule);
    }
}
