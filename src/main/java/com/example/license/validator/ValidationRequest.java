package com.example.license.validator;

import com.example.license.config.CompiledRule;

import java.util.Map;

/**
 * Captures the per-request data passed to a {@link LicenseValidator}.
 */
public record ValidationRequest(
        String path,
        String method,
        Map<String, String> headers,
        CompiledRule rule
) {
    public String header(String name) {
        if (headers == null || name == null) return null;
        return headers.get(name.toLowerCase());
    }
}
