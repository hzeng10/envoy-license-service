package com.example.license.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DefaultsDto {

    @JsonProperty("unmatchedPolicy")
    private String unmatchedPolicy = "allow";

    @JsonProperty("cacheTtlSeconds")
    private int cacheTtlSeconds = 30;

    @JsonProperty("negativeCacheTtlSeconds")
    private int negativeCacheTtlSeconds = 5;

    @JsonProperty("flushCacheOnReload")
    private boolean flushCacheOnReload = false;

    @JsonProperty("remote")
    private RemoteConfigDto remote;

    @JsonProperty("deny")
    private DenyConfigDto deny;

    public String getUnmatchedPolicy() { return unmatchedPolicy; }
    public void setUnmatchedPolicy(String unmatchedPolicy) { this.unmatchedPolicy = unmatchedPolicy; }

    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }

    public int getNegativeCacheTtlSeconds() { return negativeCacheTtlSeconds; }
    public void setNegativeCacheTtlSeconds(int negativeCacheTtlSeconds) { this.negativeCacheTtlSeconds = negativeCacheTtlSeconds; }

    public boolean isFlushCacheOnReload() { return flushCacheOnReload; }
    public void setFlushCacheOnReload(boolean flushCacheOnReload) { this.flushCacheOnReload = flushCacheOnReload; }

    public RemoteConfigDto getRemote() { return remote; }
    public void setRemote(RemoteConfigDto remote) { this.remote = remote; }

    public DenyConfigDto getDeny() { return deny; }
    public void setDeny(DenyConfigDto deny) { this.deny = deny; }
}
