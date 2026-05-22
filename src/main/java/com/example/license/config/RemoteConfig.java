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

    /** 将 {@code override} 中有效值（>0 或非 null）覆盖到当前远程配置并返回新实例。 */
    public RemoteConfig mergeWith(RemoteConfig override) {
        if (override == null) return this;
        int mergedTimeout = override.timeoutMs() > 0 ? override.timeoutMs() : this.timeoutMs;
        int mergedBulkhead = override.bulkheadMaxConcurrent() > 0 ? override.bulkheadMaxConcurrent() : this.bulkheadMaxConcurrent;
        CircuitBreakerConfig mergedCb = override.circuitBreaker() != null ? override.circuitBreaker() : this.circuitBreaker;
        FailurePolicy mergedPolicy = override.onFailure() != null ? override.onFailure() : this.onFailure;
        return new RemoteConfig(mergedTimeout, mergedBulkhead, mergedCb, mergedPolicy);
    }
}
