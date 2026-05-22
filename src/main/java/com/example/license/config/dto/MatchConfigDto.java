package com.example.license.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchConfigDto {

    @JsonProperty("path")
    private String path;

    @JsonProperty("methods")
    private List<String> methods = new ArrayList<>();

    @JsonProperty("hosts")
    private List<String> hosts = new ArrayList<>();

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }

    public List<String> getHosts() { return hosts; }
    public void setHosts(List<String> hosts) { this.hosts = hosts; }
}
