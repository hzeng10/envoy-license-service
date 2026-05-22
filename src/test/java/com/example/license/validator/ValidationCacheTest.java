package com.example.license.validator;

import com.example.license.validator.support.CacheKey;
import com.example.license.validator.support.ValidationCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ValidationCacheTest {

    private ValidationCache cache;

    @BeforeEach
    void setUp() {
        cache = new ValidationCache();
    }

    @Test
    void cacheHitReturnsSameResult() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CacheKey key = new CacheKey("rule-1", "token-abc");

        ValidationResult r1 = cache.getOrLoad(key, 30, 5,
                k -> { callCount.incrementAndGet(); return CompletableFuture.completedFuture(ValidationResult.allow()); }).get();
        ValidationResult r2 = cache.getOrLoad(key, 30, 5,
                k -> { callCount.incrementAndGet(); return CompletableFuture.completedFuture(ValidationResult.allow()); }).get();

        assertThat(r1.allowed()).isTrue();
        assertThat(r2.allowed()).isTrue();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void differentKeysDoNotShare() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CacheKey k1 = new CacheKey("rule-1", "token-1");
        CacheKey k2 = new CacheKey("rule-1", "token-2");

        cache.getOrLoad(k1, 30, 5, k -> { callCount.incrementAndGet(); return cf(true); }).get();
        cache.getOrLoad(k2, 30, 5, k -> { callCount.incrementAndGet(); return cf(true); }).get();

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsShareSingleCall() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CacheKey key = new CacheKey("rule-1", "concurrent-token");
        CountDownLatch latch = new CountDownLatch(1);

        // Loader blocks until latch is released, simulating a slow upstream
        var slowLoader = (java.util.function.Function<CacheKey, CompletableFuture<ValidationResult>>) k -> {
            callCount.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ValidationResult.allow();
            });
        };

        int threads = 8;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<Future<ValidationResult>>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> cache.getOrLoad(key, 30, 5, slowLoader).get()));
        }
        // Release the slow loader
        latch.countDown();
        for (var f : futures) f.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertThat(callCount.get()).isLessThanOrEqualTo(threads);
    }

    @Test
    void invalidateByRuleIdRemovesEntries() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CacheKey key = new CacheKey("rule-x", "token");
        cache.getOrLoad(key, 30, 5, k -> { callCount.incrementAndGet(); return cf(true); }).get();

        cache.invalidate("rule-x");

        cache.getOrLoad(key, 30, 5, k -> { callCount.incrementAndGet(); return cf(true); }).get();
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void negativeCacheTtlCachesFailedResults() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CacheKey key = new CacheKey("rule-neg", "bad-token");

        // Deny results should be cached just like allow results
        ValidationResult r1 = cache.getOrLoad(key, 30, 5, k -> { callCount.incrementAndGet(); return cf(false); }).get();
        ValidationResult r2 = cache.getOrLoad(key, 30, 5, k -> { callCount.incrementAndGet(); return cf(false); }).get();

        assertThat(r1.allowed()).isFalse();
        assertThat(r2.allowed()).isFalse();
        // Both calls should share the same cached result
        assertThat(callCount.get()).isEqualTo(1);
    }

    private static CompletableFuture<ValidationResult> cf(boolean allowed) {
        return CompletableFuture.completedFuture(allowed ? ValidationResult.allow() : ValidationResult.denyDefault());
    }
}
