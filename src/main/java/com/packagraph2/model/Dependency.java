package com.packagraph2.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Dependency {

    private String fromPackage;
    private String toPackage;

    public Dependency() {
    }

    @JsonCreator
    public Dependency(@JsonProperty("fromPackage") String fromPackage,
                      @JsonProperty("toPackage") String toPackage) {
        this.fromPackage = fromPackage;
        this.toPackage = toPackage;
    }

    public String getFromPackage() {
        return fromPackage;
    }

    public void setFromPackage(String fromPackage) {
        this.fromPackage = fromPackage;
    }

    public String getToPackage() {
        return toPackage;
    }

    public void setToPackage(String toPackage) {
        this.toPackage = toPackage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Dependency other)) return false;
        return fromPackage.equals(other.fromPackage) && toPackage.equals(other.toPackage);
    }

    @Override
    public int hashCode() {
        return 31 * fromPackage.hashCode() + toPackage.hashCode();
    }

    @Override
    public String toString() {
        return fromPackage + " -> " + toPackage;
    }
}
