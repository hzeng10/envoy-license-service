package com.example.license.validator;

import com.example.license.config.CircuitBreakerConfig;
import com.example.license.config.RemoteConfig;
import com.example.license.validator.support.ResilienceDecorators;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceDecoratorsTest {

    @Test
    void successCallReturnsResult() throws Exception {
        ResilienceDecorators resilience = buildDecorators("r1", RemoteConfig.FailurePolicy.FAIL_CLOSED);
        ValidationResult result = resilience.execute(
                () -> CompletableFuture.completedFuture(ValidationResult.allow())).get();
        assertThat(result.allowed()).isTrue();
        resilience.shutdown();
    }

    @Test
    void timeoutTriggersFailPolicy() throws Exception {
        RemoteConfig cfg = new RemoteConfig(50, 64, CircuitBreakerConfig.DEFAULT, RemoteConfig.FailurePolicy.FAIL_CLOSED);
        ResilienceDecorators resilience = new ResilienceDecorators("timeout-test", cfg);

        ValidationResult result = resilience.execute(() ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return ValidationResult.allow();
                })
        ).get(5, TimeUnit.SECONDS);

        assertThat(result.allowed()).isFalse();
        assertThat(result.denyStatus()).isEqualTo(503);
        resilience.shutdown();
    }

    @Test
    void failOpenPolicyAllowsOnError() throws Exception {
        RemoteConfig cfg = new RemoteConfig(50, 64, CircuitBreakerConfig.DEFAULT, RemoteConfig.FailurePolicy.FAIL_OPEN);
        ResilienceDecorators resilience = new ResilienceDecorators("failopen-test", cfg);

        ValidationResult result = resilience.execute(() ->
                CompletableFuture.failedFuture(new RuntimeException("backend error"))
        ).get(5, TimeUnit.SECONDS);

        assertThat(result.allowed()).isTrue();
        resilience.shutdown();
    }

    @Test
    void circuitBreakerOpensAfterFailures() throws Exception {
        CircuitBreakerConfig cbCfg = new CircuitBreakerConfig(50.0f, 4, 10, 2);
        RemoteConfig cfg = new RemoteConfig(1000, 64, cbCfg, RemoteConfig.FailurePolicy.FAIL_CLOSED);
        ResilienceDecorators resilience = new ResilienceDecorators("cb-open-test", cfg);

        // Cause enough failures to open the circuit (need 100% failure rate over window of 4)
        for (int i = 0; i < 4; i++) {
            resilience.execute(() -> CompletableFuture.failedFuture(new RuntimeException("fail")))
                    .get(5, TimeUnit.SECONDS);
        }

        // Circuit should be open now; next call should return failClosed result without calling supplier
        AtomicInteger callCount = new AtomicInteger(0);
        ValidationResult result = resilience.execute(() -> {
            callCount.incrementAndGet();
            return CompletableFuture.completedFuture(ValidationResult.allow());
        }).get(5, TimeUnit.SECONDS);

        // When circuit is open, it returns failClosed immediately
        assertThat(result.allowed()).isFalse();
        assertThat(callCount.get()).isEqualTo(0);
        resilience.shutdown();
    }

    @Test
    void bulkheadRejectsWhenFull() throws Exception {
        RemoteConfig cfg = new RemoteConfig(5000, 1, CircuitBreakerConfig.DEFAULT, RemoteConfig.FailurePolicy.FAIL_CLOSED);
        ResilienceDecorators resilience = new ResilienceDecorators("bh-test", cfg);

        // Occupy the single slot with a blocking future
        var blockingFuture = new CompletableFuture<ValidationResult>();
        resilience.execute(() -> blockingFuture);

        // Second call should be rejected immediately
        ValidationResult rejected = resilience.execute(
                () -> CompletableFuture.completedFuture(ValidationResult.allow()))
                .get(5, TimeUnit.SECONDS);

        assertThat(rejected.allowed()).isFalse();

        // Unblock and cleanup
        blockingFuture.complete(ValidationResult.allow());
        resilience.shutdown();
    }

    private static ResilienceDecorators buildDecorators(String id, RemoteConfig.FailurePolicy policy) {
        RemoteConfig cfg = new RemoteConfig(1000, 64, CircuitBreakerConfig.DEFAULT, policy);
        return new ResilienceDecorators(id, cfg);
    }
}
