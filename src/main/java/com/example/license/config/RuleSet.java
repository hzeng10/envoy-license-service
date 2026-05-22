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
     * 按声明顺序逐条匹配规则，返回第一条满足路径和 HTTP 方法的规则；
     * 如果没有匹配的规则则返回 {@code null}。
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
