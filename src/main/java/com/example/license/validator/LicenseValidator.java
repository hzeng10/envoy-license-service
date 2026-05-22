package com.example.license.validator;

import java.util.concurrent.CompletableFuture;

/**
 * Extension point for license validation logic. Implementations are Spring beans registered by name.
 *
 * <p>Implementations MUST be:
 * <ul>
 *   <li>Thread-safe — called concurrently from Netty event-loop threads</li>
 *   <li>Non-blocking — must not park the calling thread; return an already-completed future for
 *       in-process logic</li>
 * </ul>
 */
public interface LicenseValidator {

    /**
     * The unique name referenced from {@code license-rules.yml}. Must be stable across restarts
     * and match the value in the {@code validator} field of each rule.
     */
    String name();

    /**
     * Validates the request. Never returns {@code null}.
     *
     * @param request the validation request with path, method, headers, and the matched rule config
     * @return a future that completes with either {@link ValidationResult#allow()} or
     *         {@link ValidationResult#deny}
     */
    CompletableFuture<ValidationResult> validate(ValidationRequest request);
}
