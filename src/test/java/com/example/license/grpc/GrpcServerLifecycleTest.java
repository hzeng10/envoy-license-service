package com.example.license.grpc;

import com.example.license.config.CompiledRule;
import com.example.license.config.DenyConfig;
import com.example.license.config.LicenseProperties;
import com.example.license.config.RuleSet;
import com.example.license.config.RuleSetHolder;
import com.example.license.matcher.UrlMatcherFactory;
import com.example.license.response.DenyResponseBuilder;
import com.example.license.validator.DefaultHeaderTokenValidator;
import com.example.license.validator.ValidatorRegistry;
import com.example.license.validator.support.ValidationCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcServerLifecycleTest {

    private GrpcServerLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        RuleSet rs = new RuleSet(
                List.of(new CompiledRule("r1", UrlMatcherFactory.create("/api/**", List.of("*")),
                        "default-header-token", Map.of("expectedToken", "t"),
                        DenyConfig.DEFAULT, null, 30, 5)),
                DenyConfig.DEFAULT, true);
        RuleSetHolder holder = new RuleSetHolder(rs);
        ValidatorRegistry registry = new ValidatorRegistry(List.of(new DefaultHeaderTokenValidator()));
        ExtAuthzService service = new ExtAuthzService(holder, registry, new ValidationCache(),
                new DenyResponseBuilder(), new SimpleMeterRegistry());

        LicenseProperties props = new LicenseProperties();
        props.setGrpcPort(19292); // use different port to avoid conflict
        props.setGrpcReflection(false);

        lifecycle = new GrpcServerLifecycle(service, props);
    }

    @AfterEach
    void tearDown() {
        if (lifecycle.isRunning()) lifecycle.stop();
    }

    @Test
    void startsAndIsRunning() {
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void stopSetsNotRunning() {
        lifecycle.start();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void getPhaseIsHighValue() {
        assertThat(lifecycle.getPhase()).isGreaterThan(0);
    }

    @Test
    void reflectionEnabled() {
        LicenseProperties props = new LicenseProperties();
        props.setGrpcPort(19393);
        props.setGrpcReflection(true);

        RuleSet rs = new RuleSet(List.of(new CompiledRule("r1",
                UrlMatcherFactory.create("/api/**", List.of("*")),
                "default-header-token", Map.of("expectedToken", "t"),
                DenyConfig.DEFAULT, null, 30, 5)), DenyConfig.DEFAULT, true);
        ValidatorRegistry registry = new ValidatorRegistry(List.of(new DefaultHeaderTokenValidator()));
        ExtAuthzService svc = new ExtAuthzService(new RuleSetHolder(rs), registry,
                new ValidationCache(), new DenyResponseBuilder(), new SimpleMeterRegistry());

        GrpcServerLifecycle reflectionLifecycle = new GrpcServerLifecycle(svc, props);
        reflectionLifecycle.start();
        assertThat(reflectionLifecycle.isRunning()).isTrue();
        reflectionLifecycle.stop();
    }
}
