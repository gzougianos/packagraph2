package com.packagraph2.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public class HideRule {

    private String id;
    private String pattern;
    private boolean enabled;

    public HideRule() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
    }

    @JsonCreator
    public HideRule(@JsonProperty("id") String id,
                    @JsonProperty("pattern") String pattern,
                    @JsonProperty("enabled") boolean enabled) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.pattern = pattern;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
