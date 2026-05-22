package com.example.license.config;

import java.util.List;

/**
 * Immutable, thread-safe set of compiled license rules. Accessed on every gRPC hot path.
 */
public record RuleSet(
        List<CompiledRule> rules,
        DenyConfig defaultDeny,
        boolean unmatchedAllow
) {
    public RuleSet {
        rules = List.copyOf(rules);
    }

    /**
     * Returns the first rule whose matcher accepts the given path+method, or {@code null} if none match.
     */
    public CompiledRule match(String path, String method) {
        for (CompiledRule rule : rules) {
            if (rule.matcher().matches(path, method)) {
                return rule;
            }
        }
        return null;
    }
}
