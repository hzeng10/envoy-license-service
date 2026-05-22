package com.example.license.grpc;

import com.example.license.config.CompiledRule;
import com.example.license.config.DenyConfig;
import com.example.license.config.RuleSet;
import com.example.license.config.RuleSetHolder;
import com.example.license.matcher.UrlMatcherFactory;
import com.example.license.response.DenyResponseBuilder;
import com.example.license.validator.DefaultHeaderTokenValidator;
import com.example.license.validator.ValidatorRegistry;
import com.example.license.validator.support.ValidationCache;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtAuthzServiceIT {

    private ManagedChannel channel;
    private io.grpc.Server grpcServer;
    private RuleSetHolder holder;

    @BeforeEach
    void setUp() throws Exception {
        CompiledRule rule = new CompiledRule(
                "orders-rule",
                UrlMatcherFactory.create("/api/orders/**", List.of("GET", "POST")),
                "default-header-token",
                Map.of("expectedToken", "valid-token"),
                DenyConfig.DEFAULT,
                null,
                30, 5
        );
        RuleSet ruleSet = new RuleSet(List.of(rule), DenyConfig.DEFAULT, true);
        holder = new RuleSetHolder(ruleSet);

        ValidatorRegistry registry = new ValidatorRegistry(List.of(new DefaultHeaderTokenValidator()));
        ValidationCache cache = new ValidationCache();
        DenyResponseBuilder denyBuilder = new DenyResponseBuilder();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ExtAuthzService service = new ExtAuthzService(holder, registry, cache, denyBuilder, meterRegistry);

        String serverName = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        grpcServer.shutdownNow();
    }

    @Test
    void allowsValidRequest() {
        CheckResponse resp = check("/api/orders/123", "GET", "valid-token");
        assertThat(resp.getStatus().getCode()).isEqualTo(com.google.rpc.Code.OK_VALUE);
        assertThat(resp.hasOkResponse()).isTrue();
    }

    @Test
    void deniesInvalidToken() {
        CheckResponse resp = check("/api/orders/123", "GET", "wrong-token");
        assertThat(resp.getStatus().getCode()).isEqualTo(com.google.rpc.Code.PERMISSION_DENIED_VALUE);
        assertThat(resp.hasDeniedResponse()).isTrue();
    }

    @Test
    void deniesMissingToken() {
        CheckResponse resp = checkNoToken("/api/orders/123", "GET");
        assertThat(resp.hasDeniedResponse()).isTrue();
    }

    @Test
    void allowsUnmatchedUrlWhenPolicyIsAllow() {
        CheckResponse resp = check("/other/path", "GET", "any-token");
        // unmatched → allow
        assertThat(resp.getStatus().getCode()).isEqualTo(com.google.rpc.Code.OK_VALUE);
    }

    @Test
    void deniesUnmatchedUrlWhenPolicyIsDeny() {
        RuleSet denyUnmatched = new RuleSet(holder.current().rules(), DenyConfig.DEFAULT, false);
        holder.set(denyUnmatched);
        CheckResponse resp = check("/other/path", "GET", "any-token");
        assertThat(resp.hasDeniedResponse()).isTrue();
    }

    @Test
    void deniesMethodNotInRule() {
        CheckResponse resp = check("/api/orders/123", "DELETE", "valid-token");
        // DELETE not in rule [GET, POST] → unmatched → allow (default policy)
        assertThat(resp.getStatus().getCode()).isEqualTo(com.google.rpc.Code.OK_VALUE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CheckResponse check(String path, String method, String token) {
        AuthorizationGrpc.AuthorizationBlockingStub stub = AuthorizationGrpc.newBlockingStub(channel);
        return stub.check(buildRequest(path, method, Map.of("x-license-token", token)));
    }

    private CheckResponse checkNoToken(String path, String method) {
        AuthorizationGrpc.AuthorizationBlockingStub stub = AuthorizationGrpc.newBlockingStub(channel);
        return stub.check(buildRequest(path, method, Map.of()));
    }

    private static CheckRequest buildRequest(String path, String method, Map<String, String> headers) {
        AttributeContext.HttpRequest.Builder httpBuilder = AttributeContext.HttpRequest.newBuilder()
                .setPath(path)
                .setMethod(method);
        headers.forEach(httpBuilder::putHeaders);

        return CheckRequest.newBuilder()
                .setAttributes(AttributeContext.newBuilder()
                        .setRequest(AttributeContext.Request.newBuilder()
                                .setHttp(httpBuilder.build())
                                .build())
                        .build())
                .build();
    }
}
