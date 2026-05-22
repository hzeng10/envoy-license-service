package com.example.license.config;

/**
 * Configuration for remote-validator resilience (timeout, bulkhead, circuit breaker).
 */
public record RemoteConfig(
        int timeoutMs,
        int bulkheadMaxConcurrent,
        CircuitBreakerConfig circuitBreaker,
        FailurePolicy onFailure
) {
    public static final RemoteConfig DEFAULT = new RemoteConfig(
            100,
            64,
            CircuitBreakerConfig.DEFAULT,
            FailurePolicy.FAIL_CLOSED
    );

    public enum FailurePolicy {
        FAIL_OPEN, FAIL_CLOSED
    }

    /** Returns this config merged with any non-null fields from {@code override}. */
    public RemoteConfig mergeWith(RemoteConfig override) {
        if (override == null) return this;
        int mergedTimeout = override.timeoutMs() > 0 ? override.timeoutMs() : this.timeoutMs;
        int mergedBulkhead = override.bulkheadMaxConcurrent() > 0 ? override.bulkheadMaxConcurrent() : this.bulkheadMaxConcurrent;
        CircuitBreakerConfig mergedCb = override.circuitBreaker() != null ? override.circuitBreaker() : this.circuitBreaker;
        FailurePolicy mergedPolicy = override.onFailure() != null ? override.onFailure() : this.onFailure;
        return new RemoteConfig(mergedTimeout, mergedBulkhead, mergedCb, mergedPolicy);
    }
}
