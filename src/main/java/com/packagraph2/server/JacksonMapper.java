package com.packagraph2.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packagraph2.project.ProjectManager;
import io.javalin.json.JsonMapper;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Bridges Javalin's JsonMapper to our shared Jackson ObjectMapper.
 */
public class JacksonMapper implements JsonMapper {

    private final ObjectMapper mapper = ProjectManager.getMapper();

    @NotNull
    @Override
    public String toJsonString(@NotNull Object obj, @NotNull Type type) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @NotNull
    @Override
    public <T> T fromJsonString(@NotNull String json, @NotNull Type type) {
        try {
            return mapper.readValue(json, mapper.constructType(type));
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }

    @NotNull
    @Override
    public <T> T fromJsonStream(@NotNull InputStream json, @NotNull Type type) {
        try {
            return mapper.readValue(json, mapper.constructType(type));
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }
}
