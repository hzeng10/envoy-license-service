package com.example.license.validator;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default in-process validator. Checks for a configured token in a request header,
 * optionally within a validity time window. Returns an already-completed future — zero I/O.
 *
 * <p>Rule {@code config} keys:
 * <ul>
 *   <li>{@code headerName} — header to inspect (default: {@code x-license-token})</li>
 *   <li>{@code expectedToken} — required token value</li>
 *   <li>{@code validFrom} — ISO-8601 instant; reject before this time (optional)</li>
 *   <li>{@code validUntil} — ISO-8601 instant; reject after this time (optional)</li>
 * </ul>
 */
@Component
public class DefaultHeaderTokenValidator implements LicenseValidator {

    @Override
    public String name() {
        return "default-header-token";
    }

    @Override
    public CompletableFuture<ValidationResult> validate(ValidationRequest request) {
        Map<String, Object> cfg = request.rule().config();
        String headerName = configStr(cfg, "headerName", "x-license-token").toLowerCase();
        String expected = configStr(cfg, "expectedToken", null);
        String validFrom = configStr(cfg, "validFrom", null);
        String validUntil = configStr(cfg, "validUntil", null);

        if (expected == null || expected.isBlank()) {
            return CompletableFuture.completedFuture(
                    ValidationResult.deny(500, "{\"code\":\"CONFIG_ERROR\",\"message\":\"expectedToken not configured\"}", Map.of("content-type", "application/json")));
        }

        String token = request.header(headerName);
        if (token == null || token.isBlank()) {
            return deny("Missing license token header: " + headerName);
        }
        if (!expected.equals(token)) {
            return deny("Invalid license token");
        }

        Instant now = Instant.now();
        if (validFrom != null) {
            Instant from = Instant.parse(validFrom);
            if (now.isBefore(from)) {
                return deny("License is not yet valid");
            }
        }
        if (validUntil != null) {
            Instant until = Instant.parse(validUntil);
            if (now.isAfter(until)) {
                return deny("License has expired");
            }
        }

        return CompletableFuture.completedFuture(ValidationResult.allow());
    }

    private static CompletableFuture<ValidationResult> deny(String reason) {
        return CompletableFuture.completedFuture(
                ValidationResult.deny(403,
                        "{\"code\":\"LICENSE_INVALID\",\"message\":\"" + escape(reason) + "\"}",
                        Map.of("content-type", "application/json")));
    }

    private static String configStr(Map<String, Object> cfg, String key, String defaultValue) {
        Object val = cfg != null ? cfg.get(key) : null;
        return val != null ? val.toString() : defaultValue;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
