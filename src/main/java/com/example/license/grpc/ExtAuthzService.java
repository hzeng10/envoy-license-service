package com.example.license.grpc;

import com.example.license.config.CompiledRule;
import com.example.license.config.RuleSetHolder;
import com.example.license.response.DenyResponseBuilder;
import com.example.license.validator.LicenseValidator;
import com.example.license.validator.ValidatorRegistry;
import com.example.license.validator.ValidationRequest;
import com.example.license.validator.ValidationResult;
import com.example.license.validator.support.CacheKey;
import com.example.license.validator.support.ValidationCache;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Envoy ext_authz gRPC service implementation. The {@code check} method is the hot path.
 *
 * <p>Threading: called on Netty event-loop threads. Must never block — all async work is
 * chained on {@link java.util.concurrent.CompletableFuture} and replied via the
 * thread-safe {@link StreamObserver}.
 */
@Component
public class ExtAuthzService extends AuthorizationGrpc.AuthorizationImplBase {

    private static final Logger log = LoggerFactory.getLogger(ExtAuthzService.class);

    private final RuleSetHolder ruleSetHolder;
    private final ValidatorRegistry validatorRegistry;
    private final ValidationCache cache;
    private final DenyResponseBuilder denyBuilder;
    private final Counter allowCounter;
    private final Counter denyCounter;
    private final Counter unmatchedCounter;
    private final Timer checkTimer;

    /** 注入依赖并初始化 Micrometer 计数器（allow/deny/unmatched）和耗时计时器。 */
    public ExtAuthzService(RuleSetHolder ruleSetHolder,
                           ValidatorRegistry validatorRegistry,
                           ValidationCache cache,
                           DenyResponseBuilder denyBuilder,
                           MeterRegistry meterRegistry) {
        this.ruleSetHolder = ruleSetHolder;
        this.validatorRegistry = validatorRegistry;
        this.cache = cache;
        this.denyBuilder = denyBuilder;
        this.allowCounter = Counter.builder("license.check.result")
                .tag("decision", "allow").register(meterRegistry);
        this.denyCounter = Counter.builder("license.check.result")
                .tag("decision", "deny").register(meterRegistry);
        this.unmatchedCounter = Counter.builder("license.check.result")
                .tag("decision", "unmatched").register(meterRegistry);
        this.checkTimer = Timer.builder("license.check.duration")
                .description("Time spent processing a check request")
                .register(meterRegistry);
    }

    /**
     * Envoy ext_authz 鉴权入口（热路径）。匹配规则后异步调用校验器，通过 CompletableFuture 回调写回响应，
     * 全程不阻塞 Netty 事件循环线程。
     */
    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        Timer.Sample sample = Timer.start();

        AttributeContext.HttpRequest http = request.getAttributes().getRequest().getHttp();
        String path = http.getPath();
        String method = http.getMethod();
        Map<String, String> headers = http.getHeadersMap();

        CompiledRule rule = ruleSetHolder.current().match(path, method);

        if (rule == null) {
            if (ruleSetHolder.current().unmatchedAllow()) {
                unmatchedCounter.increment();
                sample.stop(checkTimer);
                respond(responseObserver, denyBuilder.allow());
            } else {
                denyCounter.increment();
                sample.stop(checkTimer);
                respond(responseObserver, denyBuilder.deny(ValidationResult.denyDefault(), ruleSetHolder.current().defaultDeny()));
            }
            return;
        }

        final CompiledRule finalRule = rule;
        String tokenHeaderName = (String) finalRule.config().getOrDefault("headerName", "x-license-token");
        String token = headers.getOrDefault(tokenHeaderName.toLowerCase(), "");

        ValidationRequest vr = new ValidationRequest(path, method, headers, finalRule);
        LicenseValidator validator = validatorRegistry.get(finalRule.validatorName());
        CacheKey cacheKey = new CacheKey(finalRule.id(), token);

        cache.getOrLoad(cacheKey, finalRule.cacheTtlSeconds(), finalRule.negativeCacheTtlSeconds(),
                        k -> validator.validate(vr))
                .whenComplete((result, err) -> {
                    sample.stop(checkTimer);
                    if (err != null) {
                        log.error("Validation error for rule '{}': {}", finalRule.id(), err.getMessage(), err);
                        respond(responseObserver, denyBuilder.deny(ValidationResult.denyDefault(), finalRule.denyConfig()));
                        denyCounter.increment();
                        return;
                    }
                    if (result.allowed()) {
                        allowCounter.increment();
                        respond(responseObserver, denyBuilder.allow());
                    } else {
                        denyCounter.increment();
                        respond(responseObserver, denyBuilder.deny(result, finalRule.denyConfig()));
                    }
                });
    }

    private static void respond(StreamObserver<CheckResponse> obs, CheckResponse response) {
        obs.onNext(response);
        obs.onCompleted();
    }
}
