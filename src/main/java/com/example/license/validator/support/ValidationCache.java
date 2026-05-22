package com.example.license.validator.support;

import com.example.license.validator.ValidationResult;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Async LRU cache for validation results. Coalesces concurrent lookups with identical keys into
 * a single upstream call, preventing cache stampedes.
 *
 * <p>The cache is global. TTL is applied at the entry level by wrapping the
 * CompletableFuture with expiry metadata stored in {@link TimedResult}.
 */
@Component
public class ValidationCache {

    private static final int MAX_SIZE = 100_000;

    private final AsyncCache<CacheKey, TimedResult> cache;

    public ValidationCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .buildAsync();
    }

    /**
     * 若缓存中存在未过期的结果则直接返回；否则调用 {@code loader} 生成新结果并写入缓存。
     * 并发相同 key 的请求共享同一个 in-flight Future，防止缓存击穿。
     *
     * @param key         缓存键（ruleId + token）
     * @param ttlSeconds  校验通过结果的 TTL（秒）
     * @param negTtlSec   校验拒绝结果的 TTL（秒）
     * @param loader      缓存未命中时调用的异步加载函数，不得返回 null
     */
    public CompletableFuture<ValidationResult> getOrLoad(
            CacheKey key, int ttlSeconds, int negTtlSec,
            Function<CacheKey, CompletableFuture<ValidationResult>> loader) {

        return cache.get(key, (k, executor) -> {
            CompletableFuture<TimedResult> cf = new CompletableFuture<>();
            loader.apply(k).whenComplete((result, err) -> {
                if (err != null) {
                    cf.completeExceptionally(err);
                } else {
                    long ttl = result.allowed() ? ttlSeconds : negTtlSec;
                    cf.complete(new TimedResult(result, System.nanoTime() + Duration.ofSeconds(ttl).toNanos()));
                }
            });
            return cf;
        }).thenCompose(timed -> {
            if (timed.isExpired()) {
                cache.synchronous().invalidate(key);
                return getOrLoad(key, ttlSeconds, negTtlSec, loader);
            }
            return CompletableFuture.completedFuture(timed.result());
        });
    }

    /** 删除与指定规则 ID 关联的所有缓存条目（通常在规则热重载后按需调用）。 */
    public void invalidate(String ruleId) {
        cache.synchronous().asMap().keySet().removeIf(k -> k.ruleId().equals(ruleId));
    }

    /** 清空全部缓存条目。 */
    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }

    record TimedResult(ValidationResult result, long expiresAtNanos) {
        boolean isExpired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }
}
