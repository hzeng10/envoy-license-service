package com.example.license.config.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RulesFileDto {

    private DefaultsDto defaults = new DefaultsDto();
    private List<RuleDto> rules = new ArrayList<>();

    public DefaultsDto getDefaults() { return defaults; }
    public void setDefaults(DefaultsDto defaults) { this.defaults = defaults; }

    public List<RuleDto> getRules() { return rules; }
    public void setRules(List<RuleDto> rules) { this.rules = rules; }
}
