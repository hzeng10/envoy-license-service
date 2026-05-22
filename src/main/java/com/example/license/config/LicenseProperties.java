package com.example.license.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 许可证服务配置属性，前缀为 {@code license}，通过 application.yml 或环境变量注入。 */
@Validated
@ConfigurationProperties(prefix = "license")
public class LicenseProperties {

    private String rulesFile = "/etc/license/license-rules.yml";
    private int watchIntervalSeconds = 5;
    private int grpcPort = 9191;
    private boolean grpcReflection = false;

    /** 返回规则文件路径，默认为 ConfigMap 挂载位置 {@code /etc/license/license-rules.yml}。 */
    public String getRulesFile() { return rulesFile; }
    /** 设置规则文件路径。 */
    public void setRulesFile(String rulesFile) { this.rulesFile = rulesFile; }

    /** 返回文件轮询间隔（秒），默认 5 秒。 */
    public int getWatchIntervalSeconds() { return watchIntervalSeconds; }
    /** 设置文件轮询间隔（秒）。 */
    public void setWatchIntervalSeconds(int watchIntervalSeconds) { this.watchIntervalSeconds = watchIntervalSeconds; }

    /** 返回 gRPC 服务监听端口，默认 9191。 */
    public int getGrpcPort() { return grpcPort; }
    /** 设置 gRPC 服务监听端口。 */
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }

    /** 返回是否开启 gRPC 服务反射（用于 grpcurl 调试），默认关闭。 */
    public boolean isGrpcReflection() { return grpcReflection; }
    /** 设置是否开启 gRPC 服务反射。 */
    public void setGrpcReflection(boolean grpcReflection) { this.grpcReflection = grpcReflection; }
}
