package com.example.license;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 许可证校验服务主启动类，加载所有 @ConfigurationProperties 并启动 Spring 容器。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LicenseServiceApplication {

    /** 应用程序入口，启动 Spring Boot 容器。 */
    public static void main(String[] args) {
        SpringApplication.run(LicenseServiceApplication.class, args);
    }
}
