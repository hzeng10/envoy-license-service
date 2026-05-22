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
     * Returns a cached result if still valid, or invokes {@code loader} to compute a fresh one.
     *
     * @param key         cache key (ruleId + token)
     * @param ttlSeconds  positive-result TTL in seconds
     * @param negTtlSec   negative-result TTL in seconds
     * @param loader      async function called on cache miss; must not return null
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

    public void invalidate(String ruleId) {
        cache.synchronous().asMap().keySet().removeIf(k -> k.ruleId().equals(ruleId));
    }

    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }

    record TimedResult(ValidationResult result, long expiresAtNanos) {
        boolean isExpired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }
}
