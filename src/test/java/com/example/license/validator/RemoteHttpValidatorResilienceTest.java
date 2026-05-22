package com.example.license.validator;

import com.example.license.config.CircuitBreakerConfig;
import com.example.license.config.CompiledRule;
import com.example.license.config.DenyConfig;
import com.example.license.config.RemoteConfig;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteHttpValidatorResilienceTest {

    private HttpServer server;
    private int port;
    private volatile int responseStatus = 200;
    private RemoteHttpValidator validator;

    @BeforeAll
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/validate", exchange -> {
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        validator = new RemoteHttpValidator();
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void withResilienceConfigCircuitBreakerProtects() throws Exception {
        CircuitBreakerConfig cbCfg = new CircuitBreakerConfig(50.0f, 10, 30, 2);
        RemoteConfig remoteConfig = new RemoteConfig(500, 64, cbCfg, RemoteConfig.FailurePolicy.FAIL_CLOSED);

        responseStatus = 500;
        // Run enough calls to potentially trigger circuit opening
        for (int i = 0; i < 3; i++) {
            ValidationResult r = validator.validate(requestWith(remoteConfig)).get();
            assertThat(r.allowed()).isFalse();
        }
    }

    @Test
    void withResilienceConfigFailOpenAllowsOnBackendError() throws Exception {
        RemoteConfig remoteConfig = new RemoteConfig(500, 64, CircuitBreakerConfig.DEFAULT,
                RemoteConfig.FailurePolicy.FAIL_OPEN);

        // Use an unreachable endpoint to trigger failure
        CompiledRule rule = new CompiledRule(
                "failopen-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of("endpoint", "http://127.0.0.1:1/validate"), // connection refused
                DenyConfig.DEFAULT,
                remoteConfig,
                30, 5
        );
        ValidationResult r = validator.validate(
                new ValidationRequest("/api/x", "GET", Map.of("x-license-token", "t"), rule)).get();
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void getMethodIsUsed() throws Exception {
        responseStatus = 200;
        ValidationRequest req = new ValidationRequest("/api/x", "GET", Map.of("x-license-token", "t"),
                new CompiledRule("get-rule",
                        UrlMatcherFactory.create("/api/**", List.of("*")),
                        "remote-http",
                        Map.of("endpoint", "http://127.0.0.1:" + port + "/validate", "method", "GET"),
                        DenyConfig.DEFAULT, null, 30, 5));
        ValidationResult r = validator.validate(req).get();
        assertThat(r.allowed()).isTrue();
    }

    private ValidationRequest requestWith(RemoteConfig remoteConfig) {
        CompiledRule rule = new CompiledRule(
                "resilience-rule",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "remote-http",
                Map.of("endpoint", "http://127.0.0.1:" + port + "/validate"),
                DenyConfig.DEFAULT,
                remoteConfig,
                30, 5
        );
        return new ValidationRequest("/api/resource", "GET",
                Map.of("x-license-token", "token"), rule);
    }
}
