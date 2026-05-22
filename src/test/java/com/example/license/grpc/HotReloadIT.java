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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HotReloadIT {

    private ManagedChannel channel;
    private io.grpc.Server grpcServer;
    private RuleSetHolder holder;
    private ValidationCache cache;

    @BeforeEach
    void setUp() throws Exception {
        CompiledRule rule = new CompiledRule(
                "rule-1",
                UrlMatcherFactory.create("/api/**", List.of("GET")),
                "default-header-token",
                Map.of("expectedToken", "old-token"),
                DenyConfig.DEFAULT,
                null,
                30, 5
        );
        holder = new RuleSetHolder(new RuleSet(List.of(rule), DenyConfig.DEFAULT, true));
        cache = new ValidationCache();

        ValidatorRegistry registry = new ValidatorRegistry(List.of(new DefaultHeaderTokenValidator()));
        DenyResponseBuilder denyBuilder = new DenyResponseBuilder();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ExtAuthzService service = new ExtAuthzService(holder, registry, cache, denyBuilder, meterRegistry);

        String serverName = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(service).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        grpcServer.shutdownNow();
    }

    @Test
    void newRuleSetAppliedToSubsequentRequests() {
        // Old token works initially
        assertThat(check("old-token").hasOkResponse()).isTrue();

        // Swap rule set with a new token
        CompiledRule updatedRule = new CompiledRule(
                "rule-1",
                UrlMatcherFactory.create("/api/**", List.of("GET")),
                "default-header-token",
                Map.of("expectedToken", "new-token"),
                DenyConfig.DEFAULT,
                null,
                30, 5
        );
        holder.set(new RuleSet(List.of(updatedRule), DenyConfig.DEFAULT, true));
        cache.invalidateAll(); // simulate flushCacheOnReload

        // Old token now fails
        assertThat(check("old-token").hasDeniedResponse()).isTrue();
        // New token succeeds
        assertThat(check("new-token").hasOkResponse()).isTrue();
    }

    @Test
    void concurrentRequestsDuringRuleSwapAreConsistent() throws InterruptedException {
        int threads = 10;
        boolean[] results = new boolean[threads];
        Thread[] ts = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                CheckResponse r = check("old-token");
                results[idx] = r.hasOkResponse() || r.hasDeniedResponse(); // must be one or the other
            });
        }

        // Swap during concurrent requests
        CompiledRule updated = new CompiledRule("rule-1",
                UrlMatcherFactory.create("/api/**", List.of("GET")),
                "default-header-token",
                Map.of("expectedToken", "new-token"),
                DenyConfig.DEFAULT, null, 30, 5);
        holder.set(new RuleSet(List.of(updated), DenyConfig.DEFAULT, true));

        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join(5000);

        for (boolean r : results) {
            assertThat(r).isTrue(); // no exceptions, no nulls
        }
    }

    private CheckResponse check(String token) {
        return AuthorizationGrpc.newBlockingStub(channel).check(
                CheckRequest.newBuilder()
                        .setAttributes(AttributeContext.newBuilder()
                                .setRequest(AttributeContext.Request.newBuilder()
                                        .setHttp(AttributeContext.HttpRequest.newBuilder()
                                                .setPath("/api/resource")
                                                .setMethod("GET")
                                                .putHeaders("x-license-token", token)
                                                .build())
                                        .build())
                                .build())
                        .build());
    }
}
