package com.example.license.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleSetLoaderTest {

    @TempDir
    Path tempDir;

    private Environment env;
    private RuleSetLoader loader;

    @BeforeEach
    void setUp() {
        env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);
        loader = new RuleSetLoader(env, Set.of("default-header-token"));
    }

    @Test
    void loadsBasicRules() throws IOException {
        Path file = writeYaml("""
                defaults:
                  unmatchedPolicy: allow
                rules:
                  - id: test-rule
                    match:
                      path: /api/**
                      methods: [GET]
                    validator: default-header-token
                    config:
                      expectedToken: secret
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules()).hasSize(1);
        assertThat(rs.rules().get(0).id()).isEqualTo("test-rule");
        assertThat(rs.unmatchedAllow()).isTrue();
    }

    @Test
    void unmatchedPolicyDeny() throws IOException {
        Path file = writeYaml("""
                defaults:
                  unmatchedPolicy: deny
                rules:
                  - id: r1
                    match: { path: /api/**, methods: [GET] }
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.unmatchedAllow()).isFalse();
    }

    @Test
    void environmentVariableInterpolation() throws IOException {
        Mockito.when(env.getProperty("MY_TOKEN")).thenReturn("from-env");
        Path file = writeYaml("""
                rules:
                  - id: r1
                    match: { path: /api/**, methods: [GET] }
                    config:
                      expectedToken: ${MY_TOKEN}
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).config().get("expectedToken")).isEqualTo("from-env");
    }

    @Test
    void environmentVariableDefaultValue() throws IOException {
        Path file = writeYaml("""
                rules:
                  - id: r1
                    match: { path: /api/**, methods: [GET] }
                    config:
                      expectedToken: ${MISSING_VAR:fallback-value}
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).config().get("expectedToken")).isEqualTo("fallback-value");
    }

    @Test
    void perRuleDenyOverridesDefault() throws IOException {
        Path file = writeYaml("""
                defaults:
                  deny:
                    status: 403
                    body: default-body
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                    deny:
                      status: 402
                      body: custom-body
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).denyConfig().status()).isEqualTo(402);
        assertThat(rs.rules().get(0).denyConfig().body()).isEqualTo("custom-body");
    }

    @Test
    void duplicateRuleIdFails() throws IOException {
        Path file = writeYaml("""
                rules:
                  - id: dup
                    match: { path: /api/a }
                    config: { expectedToken: x }
                  - id: dup
                    match: { path: /api/b }
                    config: { expectedToken: y }
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate rule id");
    }

    @Test
    void emptyRulesFails() throws IOException {
        Path file = writeYaml("rules: []\n");
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingPathFails() throws IOException {
        Path file = writeYaml("""
                rules:
                  - id: r1
                    match:
                      methods: [GET]
                    config: { expectedToken: x }
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void invalidDenyStatusFails() throws IOException {
        Path file = writeYaml("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                    deny:
                      status: 200
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny.status");
    }

    @Test
    void regexPatternCompiles() throws IOException {
        Path file = writeYaml("""
                rules:
                  - id: r1
                    match:
                      path: "regex:^/items/[0-9]+$"
                      methods: [GET]
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).matcher().matches("/items/42", "GET")).isTrue();
        assertThat(rs.rules().get(0).matcher().matches("/items/abc", "GET")).isFalse();
    }

    @Test
    void firstMatchWins() throws IOException {
        Path file = writeYaml("""
                rules:
                  - id: specific
                    match: { path: exact:/api/orders }
                    config: { expectedToken: specific-token }
                  - id: wildcard
                    match: { path: /api/** }
                    config: { expectedToken: wildcard-token }
                """);
        RuleSet rs = loader.load(file.toString());
        CompiledRule matched = rs.match("/api/orders", "GET");
        assertThat(matched).isNotNull();
        assertThat(matched.id()).isEqualTo("specific");
    }

    private Path writeYaml(String content) throws IOException {
        Path file = tempDir.resolve("rules.yml");
        Files.writeString(file, content);
        return file;
    }
}
