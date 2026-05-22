package com.example.license.config;

import com.example.license.config.dto.CircuitBreakerConfigDto;
import com.example.license.config.dto.DefaultsDto;
import com.example.license.config.dto.DenyConfigDto;
import com.example.license.config.dto.RemoteConfigDto;
import com.example.license.config.dto.RuleDto;
import com.example.license.config.dto.RulesFileDto;
import com.example.license.matcher.UrlMatcherFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and compiles a {@link RuleSet} from a YAML file.
 * Supports {@code ${VAR:default}} substitution via Spring's {@link Environment}.
 */
public class RuleSetLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleSetLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    @SuppressWarnings("deprecation")
    private static final PropertyPlaceholderHelper PLACEHOLDER_HELPER =
            new PropertyPlaceholderHelper("${", "}", ":", false);
    private static final String DEFAULT_VALIDATOR = "default-header-token";

    private final Environment env;
    private final Set<String> registeredValidatorNames;

    /** 注入 Spring Environment（用于 ${VAR} 占位符解析）和已注册的校验器名称集合。 */
    public RuleSetLoader(Environment env, Set<String> registeredValidatorNames) {
        this.env = env;
        this.registeredValidatorNames = registeredValidatorNames;
    }

    /** 读取指定路径的 YAML 文件，解析占位符后编译为不可变的 {@link RuleSet}。 */
    public RuleSet load(String filePath) throws IOException {
        String raw = Files.readString(Path.of(filePath));
        String resolved = PLACEHOLDER_HELPER.replacePlaceholders(raw,
                name -> env.getProperty(name));
        RulesFileDto dto = YAML_MAPPER.readValue(resolved, RulesFileDto.class);
        return compile(dto);
    }

    private RuleSet compile(RulesFileDto dto) {
        DefaultsDto defaults = dto.getDefaults() != null ? dto.getDefaults() : new DefaultsDto();

        if (dto.getRules() == null || dto.getRules().isEmpty()) {
            throw new IllegalArgumentException("'rules' list must not be empty");
        }

        Set<String> seenIds = new LinkedHashSet<>();
        List<CompiledRule> compiled = new ArrayList<>(dto.getRules().size());

        DenyConfig defaultDeny = buildDenyConfig(defaults.getDeny(), DenyConfig.DEFAULT);
        RemoteConfig defaultRemote = buildRemoteConfig(defaults.getRemote(), RemoteConfig.DEFAULT);

        for (RuleDto rule : dto.getRules()) {
            validateRule(rule, seenIds);
            seenIds.add(rule.getId());

            DenyConfig ruleDeny = defaultDeny.mergeWith(buildDenyConfig(rule.getDeny(), null));
            RemoteConfig ruleRemote = rule.getRemote() != null
                    ? buildRemoteConfig(rule.getRemote(), defaultRemote)
                    : null;

            int ttl = rule.getCacheTtlSeconds() != null ? rule.getCacheTtlSeconds() : defaults.getCacheTtlSeconds();
            int negTtl = rule.getNegativeCacheTtlSeconds() != null
                    ? rule.getNegativeCacheTtlSeconds()
                    : defaults.getNegativeCacheTtlSeconds();

            compiled.add(new CompiledRule(
                    rule.getId(),
                    UrlMatcherFactory.create(rule.getMatch().getPath(), rule.getMatch().getMethods()),
                    resolveValidatorName(rule.getValidator()),
                    rule.getConfig() != null ? Collections.unmodifiableMap(rule.getConfig()) : Map.of(),
                    ruleDeny,
                    ruleRemote,
                    ttl,
                    negTtl
            ));
        }

        boolean allowUnmatched = !"deny".equalsIgnoreCase(defaults.getUnmatchedPolicy());
        return new RuleSet(compiled, defaultDeny, allowUnmatched);
    }

    private void validateRule(RuleDto rule, Set<String> seenIds) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            throw new IllegalArgumentException("Rule 'id' must not be blank");
        }
        if (seenIds.contains(rule.getId())) {
            throw new IllegalArgumentException("Duplicate rule id: " + rule.getId());
        }
        if (rule.getMatch() == null || rule.getMatch().getPath() == null || rule.getMatch().getPath().isBlank()) {
            throw new IllegalArgumentException("Rule '" + rule.getId() + "': match.path must not be blank");
        }
        if (rule.getDeny() != null && rule.getDeny().getStatus() != 0
                && (rule.getDeny().getStatus() < 400 || rule.getDeny().getStatus() > 599)) {
            throw new IllegalArgumentException("Rule '" + rule.getId() + "': deny.status must be 400–599");
        }
    }

    private String resolveValidatorName(String name) {
        if (name == null || name.isBlank()) return DEFAULT_VALIDATOR;
        if (!registeredValidatorNames.isEmpty() && !registeredValidatorNames.contains(name)) {
            log.warn("Validator '{}' not registered; check your Spring configuration", name);
        }
        return name;
    }

    private static DenyConfig buildDenyConfig(DenyConfigDto dto, DenyConfig fallback) {
        if (dto == null) return fallback;
        return new DenyConfig(
                dto.getStatus() > 0 ? dto.getStatus() : (fallback != null ? fallback.status() : 0),
                dto.getBody() != null ? dto.getBody() : (fallback != null ? fallback.body() : null),
                dto.getHeaders() != null && !dto.getHeaders().isEmpty()
                        ? Collections.unmodifiableMap(dto.getHeaders())
                        : (fallback != null ? fallback.headers() : Map.of())
        );
    }

    private static RemoteConfig buildRemoteConfig(RemoteConfigDto dto, RemoteConfig fallback) {
        if (dto == null) return fallback;
        int timeout = dto.getTimeoutMs() > 0 ? dto.getTimeoutMs() : fallback.timeoutMs();
        int bulkhead = dto.getBulkheadMaxConcurrent() > 0 ? dto.getBulkheadMaxConcurrent() : fallback.bulkheadMaxConcurrent();
        CircuitBreakerConfig cb = buildCircuitBreakerConfig(dto.getCircuitBreaker(), fallback.circuitBreaker());
        RemoteConfig.FailurePolicy policy = parseFailurePolicy(dto.getOnFailure(), fallback.onFailure());
        return new RemoteConfig(timeout, bulkhead, cb, policy);
    }

    private static CircuitBreakerConfig buildCircuitBreakerConfig(CircuitBreakerConfigDto dto, CircuitBreakerConfig fallback) {
        if (dto == null) return fallback;
        float frt = dto.getFailureRateThreshold() > 0 ? dto.getFailureRateThreshold() : fallback.failureRateThreshold();
        int sw = dto.getSlidingWindowSize() > 0 ? dto.getSlidingWindowSize() : fallback.slidingWindowSize();
        int os = dto.getOpenStateSeconds() > 0 ? dto.getOpenStateSeconds() : fallback.openStateSeconds();
        int hop = dto.getHalfOpenPermitted() > 0 ? dto.getHalfOpenPermitted() : fallback.halfOpenPermitted();
        return new CircuitBreakerConfig(frt, sw, os, hop);
    }

    private static RemoteConfig.FailurePolicy parseFailurePolicy(String value, RemoteConfig.FailurePolicy fallback) {
        if (value == null) return fallback;
        return switch (value.toLowerCase()) {
            case "failopen" -> RemoteConfig.FailurePolicy.FAIL_OPEN;
            case "failclosed" -> RemoteConfig.FailurePolicy.FAIL_CLOSED;
            default -> fallback;
        };
    }
}
