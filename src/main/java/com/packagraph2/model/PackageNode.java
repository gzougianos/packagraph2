package com.packagraph2.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PackageNode {

    private String name;
    private boolean external;
    private String categoryId;

    public PackageNode() {
    }

    @JsonCreator
    public PackageNode(@JsonProperty("name") String name,
                       @JsonProperty("external") boolean external) {
        this.name = name;
        this.external = external;
    }

    public PackageNode(String name, boolean external, String categoryId) {
        this.name = name;
        this.external = external;
        this.categoryId = categoryId;
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

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
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
