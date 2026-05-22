package com.example.license.config;

/**
 * 熔断器配置：失败率阈值、滑动窗口大小、熔断开启时长（秒）以及半开状态允许的试探请求数。
 * 默认值：失败率 50%，窗口 50 次，开启 10 秒，半开允许 5 次。
 */
public record CircuitBreakerConfig(
        float failureRateThreshold,
        int slidingWindowSize,
        int openStateSeconds,
        int halfOpenPermitted
) {
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(50.0f, 50, 10, 5);
}
