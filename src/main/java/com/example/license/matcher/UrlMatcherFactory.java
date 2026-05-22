package com.example.license.matcher;

import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses rule path expressions and produces compiled {@link UrlMatcher} instances.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>{@code exact:/foo/bar} — literal path equality</li>
 *   <li>{@code regex:^/x/[0-9]+$} — Java regex applied to the path (query string stripped)</li>
 *   <li>anything else — treated as an Ant-style pattern ({@code /api/**}, {@code /v?/x/*})</li>
 * </ul>
 */
public final class UrlMatcherFactory {

    private static final AntPathMatcher ANT = new AntPathMatcher();
    private static final String WILDCARD = "*";

    private UrlMatcherFactory() {}

    /**
     * 根据路径表达式和方法列表创建组合匹配器。
     * 方法列表为空或包含 {@code "*"} 时表示接受任意 HTTP 方法。
     */
    public static UrlMatcher create(String pathExpr, List<String> methods) {
        if (pathExpr == null || pathExpr.isBlank()) {
            throw new IllegalArgumentException("path expression must not be blank");
        }

        Set<String> upperMethods = (methods == null || methods.isEmpty())
                ? Set.of()
                : methods.stream().map(String::toUpperCase).collect(Collectors.toUnmodifiableSet());
        boolean anyMethod = upperMethods.isEmpty() || upperMethods.contains(WILDCARD);

        UrlMatcher pathMatcher = buildPathMatcher(pathExpr.trim());
        return (path, method) -> {
            if (!pathMatcher.matches(path, method)) return false;
            if (anyMethod) return true;
            return method != null && upperMethods.contains(method.toUpperCase());
        };
    }

    private static UrlMatcher buildPathMatcher(String expr) {
        if (expr.startsWith("exact:")) {
            String literal = expr.substring("exact:".length());
            return (path, method) -> literal.equals(stripQuery(path));
        }
        if (expr.startsWith("regex:")) {
            Pattern pattern = Pattern.compile(expr.substring("regex:".length()));
            return (path, method) -> pattern.matcher(stripQuery(path)).matches();
        }
        // Ant-style pattern
        return (path, method) -> ANT.match(expr, stripQuery(path));
    }

    /** Removes the query string so matchers work on the path only. */
    private static String stripQuery(String path) {
        if (path == null) return "";
        int idx = path.indexOf('?');
        return idx < 0 ? path : path.substring(0, idx);
    }
}
