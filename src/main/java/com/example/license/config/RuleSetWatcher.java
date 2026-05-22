package com.example.license.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls the rules file for modification time changes and triggers an atomic swap of the active {@link RuleSet}.
 * Uses mtime polling (not WatchService) to reliably detect Kubernetes ConfigMap symlink rotations.
 */
public class RuleSetWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RuleSetWatcher.class);

    private final String rulesFile;
    private final int intervalSeconds;
    private final RuleSetLoader loader;
    private final RuleSetHolder holder;
    private final Counter reloadSuccessCounter;
    private final Counter reloadFailedCounter;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "rule-watcher"));
    private ScheduledFuture<?> task;
    private FileTime lastModified;
    private volatile boolean running = false;

    /** 初始化文件监视器，注册成功/失败计数器到 Micrometer。 */
    public RuleSetWatcher(String rulesFile, int intervalSeconds, RuleSetLoader loader,
                          RuleSetHolder holder, MeterRegistry meterRegistry) {
        this.rulesFile = rulesFile;
        this.intervalSeconds = intervalSeconds;
        this.loader = loader;
        this.holder = holder;
        this.reloadSuccessCounter = Counter.builder("license.rule.reload.success")
                .description("Number of successful rule reloads")
                .register(meterRegistry);
        this.reloadFailedCounter = Counter.builder("license.rule.reload.failed")
                .description("Number of failed rule reloads")
                .register(meterRegistry);
    }

    /** 启动定时轮询任务，以固定间隔检查规则文件的最后修改时间。 */
    @Override
    public void start() {
        running = true;
        task = scheduler.scheduleWithFixedDelay(this::pollFile, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Rule watcher started, polling '{}' every {}s", rulesFile, intervalSeconds);
    }

    /** 取消轮询任务并关闭调度线程池。 */
    @Override
    public void stop() {
        running = false;
        if (task != null) task.cancel(false);
        scheduler.shutdown();
        log.info("Rule watcher stopped");
    }

    /** 返回轮询任务是否处于运行状态。 */
    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollFile() {
        try {
            Path path = Path.of(rulesFile);
            if (!Files.exists(path)) return;
            FileTime mtime = Files.getLastModifiedTime(path);
            if (lastModified != null && mtime.equals(lastModified)) return;

            RuleSet updated = loader.load(rulesFile);
            holder.set(updated);
            lastModified = mtime;
            reloadSuccessCounter.increment();
            log.info("Rules reloaded from '{}'", rulesFile);
        } catch (Exception e) {
            reloadFailedCounter.increment();
            log.error("Failed to reload rules from '{}' — keeping current rule set: {}", rulesFile, e.getMessage(), e);
        }
    }
}
