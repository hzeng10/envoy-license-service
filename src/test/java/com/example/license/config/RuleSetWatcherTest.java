package com.example.license.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RuleSetWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsMtimeChangeAndReloads() throws IOException, InterruptedException {
        Path file = writeSingleRuleFile(tempDir, "original-token");

        Environment env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);
        RuleSetLoader loader = new RuleSetLoader(env, Set.of("default-header-token"));
        RuleSet initial = loader.load(file.toString());
        RuleSetHolder holder = new RuleSetHolder(initial);

        RuleSetWatcher watcher = new RuleSetWatcher(
                file.toString(), 1, loader, holder, new SimpleMeterRegistry());
        watcher.start();

        // Modify the file
        Thread.sleep(1100);
        writeSingleRuleFile(tempDir, "updated-token");

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    CompiledRule rule = holder.current().rules().get(0);
                    assertThat(rule.config().get("expectedToken")).isEqualTo("updated-token");
                });

        watcher.stop();
    }

    @Test
    void parseFailureKeepsCurrentRuleSet() throws IOException, InterruptedException {
        Path file = writeSingleRuleFile(tempDir, "stable-token");

        Environment env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);
        RuleSetLoader loader = new RuleSetLoader(env, Set.of("default-header-token"));
        RuleSet initial = loader.load(file.toString());
        RuleSetHolder holder = new RuleSetHolder(initial);

        RuleSetWatcher watcher = new RuleSetWatcher(
                file.toString(), 1, loader, holder, new SimpleMeterRegistry());
        watcher.start();

        Thread.sleep(1100);
        // Write invalid YAML
        Files.writeString(file, "rules: [{]\n");
        Thread.sleep(2000);

        // Rule set should be unchanged
        assertThat(holder.current().rules().get(0).config().get("expectedToken"))
                .isEqualTo("stable-token");

        watcher.stop();
    }

    private static Path writeSingleRuleFile(Path dir, String token) throws IOException {
        Path file = dir.resolve("rules.yml");
        Files.writeString(file, """
                rules:
                  - id: test
                    match: { path: /api/** }
                    config:
                      expectedToken: %s
                """.formatted(token));
        return file;
    }
}
