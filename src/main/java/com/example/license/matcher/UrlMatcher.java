package com.example.license.matcher;

/**
 * Compiled, stateless URL pattern matcher. Implementations are thread-safe and pre-compiled at rule-load time.
 */
public interface UrlMatcher {

    /** Returns true if the request path and HTTP method satisfy this rule's match criteria. */
    boolean matches(String path, String method);
}
