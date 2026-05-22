package com.example.license.matcher;

/**
 * Compiled, stateless URL pattern matcher. Implementations are thread-safe and pre-compiled at rule-load time.
 */
public interface UrlMatcher {

    /** 判断请求路径和 HTTP 方法是否满足本规则的匹配条件，满足返回 {@code true}。 */
    boolean matches(String path, String method);
}
