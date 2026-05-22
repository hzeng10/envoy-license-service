package com.example.license.config;

import com.example.license.validator.LicenseValidator;
import com.example.license.validator.ValidatorRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Creates the {@link RuleSet}, {@link RuleSetHolder}, {@link RuleSetLoader}, and {@link RuleSetWatcher} beans.
 */
@Configuration
public class LicenseConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LicenseConfiguration.class);

    /** 创建规则加载器，传入 Spring 环境（用于变量替换）和已注册校验器名称集合。 */
    @Bean
    public RuleSetLoader ruleSetLoader(Environment env, ValidatorRegistry validatorRegistry) {
        return new RuleSetLoader(env, validatorRegistry.names());
    }

    /** 启动时加载规则文件并创建持有者；文件不存在时回退到 classpath 内置默认规则。 */
    @Bean
    public RuleSetHolder ruleSetHolder(LicenseProperties properties, RuleSetLoader loader) {
        String rulesFile = properties.getRulesFile();
        RuleSet initial;
        if (Files.exists(Path.of(rulesFile))) {
            try {
                initial = loader.load(rulesFile);
                log.info("Loaded license rules from '{}'", rulesFile);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load license rules from '" + rulesFile + "'", e);
            }
        } else {
            log.warn("Rules file '{}' not found; loading bundled default rules", rulesFile);
            try {
                initial = loader.load(defaultRulesPath());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load bundled default license rules", e);
            }
        }
        return new RuleSetHolder(initial);
    }

    /** 创建规则文件热重载监视器，按配置的轮询间隔检测文件变更并原子替换规则集。 */
    @Bean
    public RuleSetWatcher ruleSetWatcher(LicenseProperties properties, RuleSetLoader loader,
                                         RuleSetHolder holder, MeterRegistry meterRegistry) {
        return new RuleSetWatcher(properties.getRulesFile(), properties.getWatchIntervalSeconds(),
                loader, holder, meterRegistry);
    }

    private static String defaultRulesPath() throws IOException {
        var resource = LicenseConfiguration.class.getClassLoader()
                .getResourceAsStream("license-rules.yml");
        if (resource == null) throw new IOException("Bundled license-rules.yml not found on classpath");
        Path tmp = Files.createTempFile("license-rules", ".yml");
        tmp.toFile().deleteOnExit();
        Files.copy(resource, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return tmp.toString();
    }
}
