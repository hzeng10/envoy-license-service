package com.example.license.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CircuitBreakerConfigDto {

    @JsonProperty("failureRateThreshold")
    private float failureRateThreshold;

    @JsonProperty("slidingWindowSize")
    private int slidingWindowSize;

    @JsonProperty("openStateSeconds")
    private int openStateSeconds;

    @JsonProperty("halfOpenPermitted")
    private int halfOpenPermitted;

    public float getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

    public int getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

    public int getOpenStateSeconds() { return openStateSeconds; }
    public void setOpenStateSeconds(int openStateSeconds) { this.openStateSeconds = openStateSeconds; }

    public int getHalfOpenPermitted() { return halfOpenPermitted; }
    public void setHalfOpenPermitted(int halfOpenPermitted) { this.halfOpenPermitted = halfOpenPermitted; }
}
