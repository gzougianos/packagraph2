package com.packagraph2.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PackageNode {

    private String name;
    private boolean external;

    public PackageNode() {
    }

    @JsonCreator
    public PackageNode(@JsonProperty("name") String name,
                       @JsonProperty("external") boolean external) {
        this.name = name;
        this.external = external;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackageNode other)) return false;
        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name + (external ? " (ext)" : "");
    }
}
