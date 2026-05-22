package com.example.license.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "license")
public class LicenseProperties {

    private String rulesFile = "/etc/license/license-rules.yml";
    private int watchIntervalSeconds = 5;
    private int grpcPort = 9191;
    private boolean grpcReflection = false;

    public String getRulesFile() { return rulesFile; }
    public void setRulesFile(String rulesFile) { this.rulesFile = rulesFile; }

    public int getWatchIntervalSeconds() { return watchIntervalSeconds; }
    public void setWatchIntervalSeconds(int watchIntervalSeconds) { this.watchIntervalSeconds = watchIntervalSeconds; }

    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }

    public boolean isGrpcReflection() { return grpcReflection; }
    public void setGrpcReflection(boolean grpcReflection) { this.grpcReflection = grpcReflection; }
}
