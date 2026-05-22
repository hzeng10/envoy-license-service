package com.example.license.validator;

import com.example.license.config.CompiledRule;

import java.util.Map;

/**
 * 封装传递给 {@link LicenseValidator} 的单次请求数据，包括路径、方法、请求头和匹配的规则。
 */
public record ValidationRequest(
        String path,
        String method,
        Map<String, String> headers,
        CompiledRule rule
) {
    /** 以小写名称从请求头 Map 中取值，不区分大小写；不存在时返回 {@code null}。 */
    public String header(String name) {
        if (headers == null || name == null) return null;
        return headers.get(name.toLowerCase());
    }
}
