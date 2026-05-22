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

    public ValidatorRegistry(List<LicenseValidator> validators) {
        this.validators = validators.stream()
                .collect(Collectors.toUnmodifiableMap(LicenseValidator::name, Function.identity()));
        this.defaultValidator = this.validators.get(DEFAULT_VALIDATOR);
        if (this.defaultValidator == null) {
            throw new IllegalStateException("No validator named '" + DEFAULT_VALIDATOR + "' is registered");
        }
    }

    /**
     * Returns the validator with the given name, or the default validator if the name is not found.
     */
    public LicenseValidator get(String name) {
        return validators.getOrDefault(name, defaultValidator);
    }

    public Set<String> names() {
        return validators.keySet();
    }
}
