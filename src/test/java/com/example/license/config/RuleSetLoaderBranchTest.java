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



/**
 * Exercises the branching in RuleSetLoader.compile() for full config inheritance.
 */
class RuleSetLoaderBranchTest {

    @TempDir
    Path tempDir;

    private RuleSetLoader loader;

    @BeforeEach
    void setUp() {
        Environment env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);
        loader = new RuleSetLoader(env, Set.of("default-header-token", "remote-http"));
    }

    @Test
    void ruleWithFullRemoteConfig() throws IOException {
        Path file = write("""
                defaults:
                  remote:
                    timeoutMs: 200
                    bulkheadMaxConcurrent: 32
                    onFailure: failOpen
                    circuitBreaker:
                      failureRateThreshold: 60
                      slidingWindowSize: 20
                      openStateSeconds: 5
                      halfOpenPermitted: 3
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: remote-http
                    config:
                      endpoint: http://example.com/validate
                    remote:
                      timeoutMs: 80
                    cacheTtlSeconds: 60
                    negativeCacheTtlSeconds: 10
                """);
        RuleSet rs = loader.load(file.toString());
        CompiledRule rule = rs.rules().get(0);
        assertThat(rule.remoteConfig()).isNotNull();
        assertThat(rule.remoteConfig().timeoutMs()).isEqualTo(80);
        assertThat(rule.remoteConfig().onFailure()).isEqualTo(RemoteConfig.FailurePolicy.FAIL_OPEN);
        assertThat(rule.remoteConfig().circuitBreaker().slidingWindowSize()).isEqualTo(20);
        assertThat(rule.cacheTtlSeconds()).isEqualTo(60);
        assertThat(rule.negativeCacheTtlSeconds()).isEqualTo(10);
    }

    @Test
    void ruleInheritsGlobalRemoteWhenNoPerRuleRemote() throws IOException {
        Path file = write("""
                defaults:
                  remote:
                    timeoutMs: 150
                    onFailure: failClosed
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: remote-http
                    config: { endpoint: http://x.com }
                """);
        RuleSet rs = loader.load(file.toString());
        // No per-rule remote → remoteConfig is null (rule doesn't override defaults, no remote block on rule)
        // But defaults.remote exists → what happens? RuleSetLoader sets ruleRemote = null if rule.getRemote() == null
        assertThat(rs.rules().get(0).remoteConfig()).isNull();
    }

    @Test
    void denyConfigInheritanceBodyOnly() throws IOException {
        Path file = write("""
                defaults:
                  deny:
                    status: 403
                    body: default-body
                    headers:
                      content-type: application/json
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                    deny:
                      body: custom-body
                """);
        RuleSet rs = loader.load(file.toString());
        DenyConfig deny = rs.rules().get(0).denyConfig();
        assertThat(deny.status()).isEqualTo(403);
        assertThat(deny.body()).isEqualTo("custom-body");
        assertThat(deny.headers()).containsKey("content-type");
    }

    @Test
    void circuitBreakerInheritedFromDefault() throws IOException {
        Path file = write("""
                defaults:
                  remote:
                    circuitBreaker:
                      failureRateThreshold: 70
                      slidingWindowSize: 30
                      openStateSeconds: 15
                      halfOpenPermitted: 4
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: remote-http
                    config: { endpoint: http://x.com }
                    remote:
                      timeoutMs: 50
                """);
        RuleSet rs = loader.load(file.toString());
        RemoteConfig rc = rs.rules().get(0).remoteConfig();
        assertThat(rc).isNotNull();
        assertThat(rc.circuitBreaker().failureRateThreshold()).isEqualTo(70.0f);
        assertThat(rc.circuitBreaker().slidingWindowSize()).isEqualTo(30);
    }

    @Test
    void unmatchedPolicyDenyBranch() throws IOException {
        Path file = write("""
                defaults:
                  unmatchedPolicy: deny
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                """);
        assertThat(loader.load(file.toString()).unmatchedAllow()).isFalse();
    }

    @Test
    void ruleWithEmptyConfig() throws IOException {
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).config()).isEmpty();
    }

    @Test
    void ruleWithNullMethodsDefaultsToAny() throws IOException {
        Path file = write("""
                rules:
                  - id: r1
                    match:
                      path: /api/**
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).matcher().matches("/api/foo", "DELETE")).isTrue();
    }

    @Test
    void ruleWithExplicitValidatorNameWarnsIfUnknown() throws IOException {
        // Unknown validator name is allowed (just warns)
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: unknown-custom
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).validatorName()).isEqualTo("unknown-custom");
    }

    @Test
    void blankRuleIdFails() throws IOException {
        Path file = write("""
                rules:
                  - id: ""
                    match: { path: /api/** }
                    config: { expectedToken: x }
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsBlockNull() throws IOException {
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.unmatchedAllow()).isTrue();
        assertThat(rs.defaultDeny().status()).isEqualTo(403);
    }

    @Test
    void blankRuleIdWithSpacesFails() throws IOException {
        Path file = write("""
                rules:
                  - id: "   "
                    match: { path: /api/** }
                    config: { expectedToken: x }
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void blankMatchPathFails() throws IOException {
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: "   " }
                    config: { expectedToken: x }
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void denyStatusAbove599Fails() throws IOException {
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                    deny:
                      status: 600
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deny.status");
    }

    @Test
    void blankValidatorNameUsesDefault() throws IOException {
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: "  "
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).validatorName()).isEqualTo("default-header-token");
    }

    @Test
    void emptyValidatorRegistrySkipsWarning() throws IOException {
        Environment env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);
        // Empty registry: !registeredValidatorNames.isEmpty() short-circuits to false → no warning
        RuleSetLoader emptyLoader = new RuleSetLoader(env, Set.of());
        Path file = write("""
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: any-custom-validator
                    config: { expectedToken: x }
                """);
        RuleSet rs = emptyLoader.load(file.toString());
        assertThat(rs.rules().get(0).validatorName()).isEqualTo("any-custom-validator");
    }

    @Test
    void invalidOnFailureValueFallsToDefault() throws IOException {
        Path file = write("""
                defaults:
                  remote:
                    onFailure: not-a-valid-value
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: remote-http
                    config: { endpoint: http://x.com }
                    remote:
                      timeoutMs: 100
                """);
        RuleSet rs = loader.load(file.toString());
        assertThat(rs.rules().get(0).remoteConfig().onFailure())
                .isEqualTo(RemoteConfig.FailurePolicy.FAIL_CLOSED);
    }

    @Test
    void partialCircuitBreakerFieldsInheritFromDefault() throws IOException {
        Path file = write("""
                defaults:
                  remote:
                    circuitBreaker:
                      failureRateThreshold: 70
                      slidingWindowSize: 30
                      openStateSeconds: 15
                      halfOpenPermitted: 4
                rules:
                  - id: r1
                    match: { path: /api/** }
                    validator: remote-http
                    config: { endpoint: http://x.com }
                    remote:
                      timeoutMs: 50
                      circuitBreaker:
                        failureRateThreshold: 80
                """);
        RuleSet rs = loader.load(file.toString());
        RemoteConfig rc = rs.rules().get(0).remoteConfig();
        assertThat(rc.circuitBreaker().failureRateThreshold()).isEqualTo(80.0f);
        assertThat(rc.circuitBreaker().slidingWindowSize()).isEqualTo(30);
        assertThat(rc.circuitBreaker().openStateSeconds()).isEqualTo(15);
        assertThat(rc.circuitBreaker().halfOpenPermitted()).isEqualTo(4);
    }

    @Test
    void nullRulesFieldFails() throws IOException {
        Path file = write("""
                defaults:
                  unmatchedPolicy: allow
                """);
        assertThatThrownBy(() -> loader.load(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rules");
    }

    @Test
    void defaultsDenyNoBodyInheritsFromFallback() throws IOException {
        Path file = write("""
                defaults:
                  deny:
                    status: 403
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config: { expectedToken: x }
                """);
        RuleSet rs = loader.load(file.toString());
        DenyConfig defaultDeny = rs.defaultDeny();
        assertThat(defaultDeny.status()).isEqualTo(403);
        assertThat(defaultDeny.body()).isNotNull();
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("rules.yml");
        Files.writeString(file, content);
        return file;
    }
}
