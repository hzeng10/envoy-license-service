package com.example.license.validator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorRegistryTest {

    @Test
    void resolvesKnownValidator() {
        ValidatorRegistry registry = new ValidatorRegistry(List.of(
                new DefaultHeaderTokenValidator(),
                namedValidator("custom")));
        assertThat(registry.get("custom").name()).isEqualTo("custom");
    }

    @Test
    void unknownNameFallsBackToDefault() {
        ValidatorRegistry registry = new ValidatorRegistry(List.of(new DefaultHeaderTokenValidator()));
        LicenseValidator v = registry.get("nonexistent");
        assertThat(v.name()).isEqualTo("default-header-token");
    }

    @Test
    void missingDefaultValidatorThrowsOnConstruction() {
        assertThatThrownBy(() -> new ValidatorRegistry(List.of(namedValidator("other"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-header-token");
    }

    @Test
    void namesContainsAllRegistered() {
        ValidatorRegistry registry = new ValidatorRegistry(List.of(
                new DefaultHeaderTokenValidator(),
                namedValidator("v2")));
        assertThat(registry.names()).containsExactlyInAnyOrder("default-header-token", "v2");
    }

    private static LicenseValidator namedValidator(String name) {
        return new LicenseValidator() {
            @Override
            public String name() { return name; }
            @Override
            public CompletableFuture<ValidationResult> validate(ValidationRequest request) {
                return CompletableFuture.completedFuture(ValidationResult.allow());
            }
        };
    }
}
