package com.example.license.validator;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of all {@link LicenseValidator} beans. Validators are discovered automatically from
 * the Spring application context via constructor injection of {@code List<LicenseValidator>}.
 */
@Component
public class ValidatorRegistry {

    private static final String DEFAULT_VALIDATOR = "default-header-token";

    private final Map<String, LicenseValidator> validators;
    private final LicenseValidator defaultValidator;

    /** 从 Spring 上下文注入所有 {@link LicenseValidator} 实现，按 name() 构建索引；缺少默认校验器时快速失败。 */
    public ValidatorRegistry(List<LicenseValidator> validators) {
        this.validators = validators.stream()
                .collect(Collectors.toUnmodifiableMap(LicenseValidator::name, Function.identity()));
        this.defaultValidator = this.validators.get(DEFAULT_VALIDATOR);
        if (this.defaultValidator == null) {
            throw new IllegalStateException("No validator named '" + DEFAULT_VALIDATOR + "' is registered");
        }
    }

    /** 按名称查找校验器，名称未注册时回退到默认校验器（{@code default-header-token}）。 */
    public LicenseValidator get(String name) {
        return validators.getOrDefault(name, defaultValidator);
    }

    /** 返回所有已注册校验器的名称集合，供 {@link com.example.license.config.RuleSetLoader} 校验规则配置时使用。 */
    public Set<String> names() {
        return validators.keySet();
    }
}
