package com.example.license.validator;

import java.util.concurrent.CompletableFuture;

/**
 * Extension point for license validation logic. Implementations are Spring beans registered by name.
 *
 * <p>Implementations MUST be:
 * <ul>
 *   <li>Thread-safe — called concurrently from Netty event-loop threads</li>
 *   <li>Non-blocking — must not park the calling thread; return an already-completed future for
 *       in-process logic</li>
 * </ul>
 */
public interface LicenseValidator {

    /**
     * 返回校验器的唯一名称，与 {@code license-rules.yml} 中规则的 {@code validator} 字段对应。
     * 该名称在服务重启间必须保持不变。
     */
    String name();

    /**
     * 异步校验请求，永远不返回 {@code null}。
     * 对于纯内存逻辑，实现应直接返回 {@link java.util.concurrent.CompletableFuture#completedFuture}
     * 以避免线程切换开销。
     *
     * @param request 包含请求路径、方法、请求头及匹配规则配置的校验上下文
     * @return 完成时携带 {@link ValidationResult#allow()} 或 {@link ValidationResult#deny} 结果的 Future
     */
    CompletableFuture<ValidationResult> validate(ValidationRequest request);
}
