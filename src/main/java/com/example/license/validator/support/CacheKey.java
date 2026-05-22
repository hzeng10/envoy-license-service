package com.example.license.validator.support;

/**
 * Cache key for validation results. Keyed by rule id + token value so that only the
 * license-relevant signal is cached, not the full request path/method.
 */
public record CacheKey(String ruleId, String token) {
}
