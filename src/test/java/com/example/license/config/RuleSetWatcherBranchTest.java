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

import static org.assertj.core.api.Assertions.assertThat;

class RuleSetWatcherBranchTest {

    @TempDir
    Path tempDir;

    @Test
    void stopBeforeStartDoesNotThrow() {
        RuleSetWatcher watcher = buildWatcher(tempDir.resolve("nonexistent.yml"));
        // Should not throw even if never started
        watcher.stop();
        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    void doesNothingWhenFileDoesNotExist() throws InterruptedException, IOException {
        Path missing = tempDir.resolve("missing-rules.yml");
        RuleSetLoader loader = Mockito.mock(RuleSetLoader.class);
        Environment env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);

        RuleSet initial = buildMinimalRuleSet(tempDir);
        RuleSetHolder holder = new RuleSetHolder(initial);

        RuleSetWatcher watcher = new RuleSetWatcher(
                missing.toString(), 1, loader, holder, new SimpleMeterRegistry());
        watcher.start();
        Thread.sleep(1500);
        watcher.stop();

        // loader should never have been called since file doesn't exist
        Mockito.verify(loader, Mockito.never()).load(Mockito.anyString());
    }

    @Test
    void noReloadWhenMtimeUnchanged() throws IOException, InterruptedException {
        Path file = tempDir.resolve("rules.yml");
        Files.writeString(file, singleRuleYaml("token-1"));

        Environment env = Mockito.mock(Environment.class);
        Mockito.when(env.getProperty(Mockito.anyString())).thenReturn(null);
        RuleSetLoader loader = new RuleSetLoader(env, Set.of("default-header-token"));
        RuleSet initial = loader.load(file.toString());
        RuleSetHolder holder = new RuleSetHolder(initial);

        // Spy the holder to count how many times set() is called
        RuleSetHolder spyHolder = Mockito.spy(holder);

        RuleSetWatcher watcher = new RuleSetWatcher(
                file.toString(), 1, loader, spyHolder, new SimpleMeterRegistry());
        watcher.start();
        Thread.sleep(3500); // wait for 3 poll cycles
        watcher.stop();

        // set() called once (on first load detecting the file) but not again
        // since mtime doesn't change between polls
        Mockito.verify(spyHolder, Mockito.atMost(2)).set(Mockito.any());
    }

    private static RuleSet buildMinimalRuleSet(Path dir) {
        try {
            Path file = dir.resolve("initial.yml");
            Files.writeString(file, singleRuleYaml("initial"));
            Environment env = Mockito.mock(Environment.class);
            return new RuleSetLoader(env, Set.of("default-header-token")).load(file.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String singleRuleYaml(String token) {
        return """
                rules:
                  - id: r1
                    match: { path: /api/** }
                    config:
                      expectedToken: %s
                """.formatted(token);
    }

    private RuleSetWatcher buildWatcher(Path rulesPath) {
        Environment env = Mockito.mock(Environment.class);
        RuleSetLoader loader = new RuleSetLoader(env, Set.of("default-header-token"));
        RuleSet initial = new RuleSet(java.util.List.of(), DenyConfig.DEFAULT, true);
        // Can't build with empty rules - create a mock holder
        RuleSetHolder holder = Mockito.mock(RuleSetHolder.class);
        Mockito.when(holder.current()).thenReturn(
                new RuleSet(java.util.List.of(), DenyConfig.DEFAULT, true));
        return new RuleSetWatcher(rulesPath.toString(), 1, loader, holder, new SimpleMeterRegistry());
    }
}
