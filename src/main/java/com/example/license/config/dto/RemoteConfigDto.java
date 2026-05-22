package com.example.license.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteConfigDto {

    @JsonProperty("timeoutMs")
    private int timeoutMs;

    @JsonProperty("bulkheadMaxConcurrent")
    private int bulkheadMaxConcurrent;

    @JsonProperty("circuitBreaker")
    private CircuitBreakerConfigDto circuitBreaker;

    @JsonProperty("onFailure")
    private String onFailure;

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getBulkheadMaxConcurrent() { return bulkheadMaxConcurrent; }
    public void setBulkheadMaxConcurrent(int bulkheadMaxConcurrent) { this.bulkheadMaxConcurrent = bulkheadMaxConcurrent; }

    public CircuitBreakerConfigDto getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerConfigDto circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    public String getOnFailure() { return onFailure; }
    public void setOnFailure(String onFailure) { this.onFailure = onFailure; }
}
