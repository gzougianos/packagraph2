package com.packagraph2.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public class GroupingRule {

    private String id;
    private String pattern;
    private String displayName;
    private boolean enabled;

    public GroupingRule() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
    }

    @JsonCreator
    public GroupingRule(@JsonProperty("id") String id,
                       @JsonProperty("pattern") String pattern,
                       @JsonProperty("displayName") String displayName,
                       @JsonProperty("enabled") boolean enabled) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.pattern = pattern;
        this.displayName = displayName;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
