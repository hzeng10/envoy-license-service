package com.example.license.validator;

import java.util.Map;

/**
 * Result returned by a {@link LicenseValidator}. Immutable value type.
 */
public record ValidationResult(
        boolean allowed,
        int denyStatus,
        String denyBody,
        Map<String, String> denyHeaders
) {
    private static final ValidationResult ALLOW = new ValidationResult(true, 0, null, null);

    public static ValidationResult allow() {
        return ALLOW;
    }

    public static ValidationResult deny(int status, String body, Map<String, String> headers) {
        return new ValidationResult(false, status, body, headers);
    }

    public static ValidationResult denyDefault() {
        return deny(403, "{\"code\":\"LICENSE_INVALID\"}", Map.of("content-type", "application/json"));
    }
}
