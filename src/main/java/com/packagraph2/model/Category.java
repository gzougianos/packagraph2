package com.packagraph2.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public class Category {

    private String id;
    private String name;
    private String color; // hex color, e.g. "#e74c3c"

    public Category() {
        this.id = UUID.randomUUID().toString();
    }

    @JsonCreator
    public Category(@JsonProperty("id") String id,
                    @JsonProperty("name") String name,
                    @JsonProperty("color") String color) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.color = color;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
