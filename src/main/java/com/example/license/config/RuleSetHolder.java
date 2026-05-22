package com.example.license.config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the active {@link RuleSet}. Reads on the hot path are lock-free.
 */
public class RuleSetHolder {

    private final AtomicReference<RuleSet> ref = new AtomicReference<>();

    /** 使用初始规则集构造持有者。 */
    public RuleSetHolder(RuleSet initial) {
        ref.set(initial);
    }

    /** Returns the currently active rule set. Volatile read; never null after construction. */
    public RuleSet current() {
        return ref.get();
    }

    /** Atomically replaces the active rule set. */
    public void set(RuleSet ruleSet) {
        ref.set(ruleSet);
    }
}
