package com.example.license.validator;

import java.util.Map;

/**
 * {@link LicenseValidator} 返回的校验结果，不可变值对象。
 * {@code allowed=true} 时拒绝相关字段无意义；{@code allowed=false} 时携带 HTTP 状态码、响应体和响应头。
 */
public record ValidationResult(
        boolean allowed,
        int denyStatus,
        String denyBody,
        Map<String, String> denyHeaders
) {
    private static final ValidationResult ALLOW = new ValidationResult(true, 0, null, null);

    /** 返回全局单例的"允许"结果，零分配。 */
    public static ValidationResult allow() {
        return ALLOW;
    }

    /** 构造携带自定义状态码、响应体和响应头的"拒绝"结果。 */
    public static ValidationResult deny(int status, String body, Map<String, String> headers) {
        return new ValidationResult(false, status, body, headers);
    }

    /** 返回默认"拒绝"结果（HTTP 403，JSON 错误码 LICENSE_INVALID）。 */
    public static ValidationResult denyDefault() {
        return deny(403, "{\"code\":\"LICENSE_INVALID\"}", Map.of("content-type", "application/json"));
    }
}
