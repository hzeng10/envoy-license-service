package com.example.license.config;

public record CircuitBreakerConfig(
        float failureRateThreshold,
        int slidingWindowSize,
        int openStateSeconds,
        int halfOpenPermitted
) {
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(50.0f, 50, 10, 5);
}
