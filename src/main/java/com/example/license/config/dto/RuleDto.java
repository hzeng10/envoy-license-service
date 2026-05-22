package com.example.license.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuleDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("match")
    private MatchConfigDto match;

    @JsonProperty("validator")
    private String validator = "default-header-token";

    @JsonProperty("config")
    private Map<String, Object> config = new HashMap<>();

    @JsonProperty("cacheTtlSeconds")
    private Integer cacheTtlSeconds;

    @JsonProperty("negativeCacheTtlSeconds")
    private Integer negativeCacheTtlSeconds;

    @JsonProperty("remote")
    private RemoteConfigDto remote;

    @JsonProperty("deny")
    private DenyConfigDto deny;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public MatchConfigDto getMatch() { return match; }
    public void setMatch(MatchConfigDto match) { this.match = match; }

    public String getValidator() { return validator; }
    public void setValidator(String validator) { this.validator = validator; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public Integer getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(Integer cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }

    public Integer getNegativeCacheTtlSeconds() { return negativeCacheTtlSeconds; }
    public void setNegativeCacheTtlSeconds(Integer negativeCacheTtlSeconds) { this.negativeCacheTtlSeconds = negativeCacheTtlSeconds; }

    public RemoteConfigDto getRemote() { return remote; }
    public void setRemote(RemoteConfigDto remote) { this.remote = remote; }

    public DenyConfigDto getDeny() { return deny; }
    public void setDeny(DenyConfigDto deny) { this.deny = deny; }
}
