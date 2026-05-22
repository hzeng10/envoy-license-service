package com.example.license.validator.support;

import com.example.license.config.CircuitBreakerConfig;
import com.example.license.config.RemoteConfig;
import com.example.license.validator.ValidationResult;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Wraps an async validation call with Bulkhead + CircuitBreaker + timeout (via
 * {@link CompletableFuture#orTimeout}).
 * Instances are created per rule and reused across requests.
 */
public class ResilienceDecorators {

    private static final Logger log = LoggerFactory.getLogger(ResilienceDecorators.class);

    private final int timeoutMs;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;
    private final RemoteConfig.FailurePolicy failurePolicy;

    public ResilienceDecorators(String id, RemoteConfig config) {
        this.timeoutMs = config.timeoutMs();
        this.failurePolicy = config.onFailure();

        this.bulkhead = Bulkhead.of("bh-" + id, BulkheadConfig.custom()
                .maxConcurrentCalls(config.bulkheadMaxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build());

        CircuitBreakerConfig cbCfg = config.circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of("cb-" + id,
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(cbCfg.failureRateThreshold())
                        .slidingWindowSize(cbCfg.slidingWindowSize())
                        .waitDurationInOpenState(Duration.ofSeconds(cbCfg.openStateSeconds()))
                        .permittedNumberOfCallsInHalfOpenState(cbCfg.halfOpenPermitted())
                        .build());
    }

    /**
     * Executes {@code supplier} with bulkhead protection, circuit-breaker, and a wall-clock timeout.
     * On failure returns a result according to the configured {@code onFailure} policy.
     */
    public CompletableFuture<ValidationResult> execute(Supplier<CompletableFuture<ValidationResult>> supplier) {
        if (!bulkhead.tryAcquirePermission()) {
            log.warn("Bulkhead full; applying onFailure policy");
            return CompletableFuture.completedFuture(failureResult());
        }

        if (!circuitBreaker.tryAcquirePermission()) {
            bulkhead.releasePermission();
            log.warn("Circuit breaker open; applying onFailure policy");
            return CompletableFuture.completedFuture(failureResult());
        }

        long start = System.nanoTime();
        CompletableFuture<ValidationResult> future;
        try {
            future = supplier.get().orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            bulkhead.releasePermission();
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            return CompletableFuture.completedFuture(failureResult());
        }

        return future
                .whenComplete((result, err) -> {
                    long elapsed = System.nanoTime() - start;
                    bulkhead.releasePermission();
                    if (err != null) {
                        circuitBreaker.onError(elapsed, TimeUnit.NANOSECONDS, err);
                    } else {
                        circuitBreaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                    }
                })
                .exceptionally(err -> {
                    if (err instanceof TimeoutException) {
                        log.warn("Validator call timed out after {}ms; applying onFailure policy", timeoutMs);
                    } else {
                        log.warn("Validator call failed; applying onFailure policy: {}", err.getMessage());
                    }
                    return failureResult();
                });
    }

    public CircuitBreaker circuitBreaker() { return circuitBreaker; }
    public Bulkhead bulkhead() { return bulkhead; }

    private ValidationResult failureResult() {
        return failurePolicy == RemoteConfig.FailurePolicy.FAIL_OPEN
                ? ValidationResult.allow()
                : ValidationResult.deny(503,
                "{\"code\":\"LICENSE_SERVICE_UNAVAILABLE\",\"message\":\"License backend unreachable\"}",
                Map.of("content-type", "application/json"));
    }

    public void shutdown() {
        // No executor to shut down now that we use CompletableFuture.orTimeout
    }
}
