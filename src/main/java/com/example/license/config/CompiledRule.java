package com.example.license.config;

import com.example.license.matcher.UrlMatcher;

import java.util.Map;

/**
 * An immutable, compiled license rule. Produced by {@link RuleSetLoader} from YAML; stored in {@link RuleSet}.
 */
public record CompiledRule(
        String id,
        UrlMatcher matcher,
        String validatorName,
        Map<String, Object> config,
        DenyConfig denyConfig,
        RemoteConfig remoteConfig,          // null for in-process validators
        int cacheTtlSeconds,
        int negativeCacheTtlSeconds
) {
}
