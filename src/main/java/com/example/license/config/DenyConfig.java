package com.example.license.config;

import java.util.Map;

/**
 * Immutable deny response definition. Per-rule deny can override the global defaults.
 */
public record DenyConfig(
        int status,
        String body,
        Map<String, String> headers
) {
    public static final DenyConfig DEFAULT = new DenyConfig(
            403,
            "{\"code\":\"LICENSE_INVALID\",\"message\":\"License rule violated\"}",
            Map.of("content-type", "application/json")
    );

    /** 将 {@code override} 中非空/非零的字段覆盖到当前配置并返回新实例；{@code override} 为 null 时返回自身。 */
    public DenyConfig mergeWith(DenyConfig override) {
        if (override == null) return this;
        int mergedStatus = override.status() > 0 ? override.status() : this.status;
        String mergedBody = override.body() != null ? override.body() : this.body;
        Map<String, String> mergedHeaders = (override.headers() != null && !override.headers().isEmpty())
                ? override.headers()
                : this.headers;
        return new DenyConfig(mergedStatus, mergedBody, mergedHeaders);
    }
}
