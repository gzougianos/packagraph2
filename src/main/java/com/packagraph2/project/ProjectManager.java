package com.packagraph2.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.packagraph2.model.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages loading and saving of .pg2 project files.
 */
public class ProjectManager {

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Saves the project configuration to a .pg2 file.
     */
    public void save(ProjectConfig config, String filePath) throws IOException {
        Path path = Path.of(filePath);
        mapper.writeValue(path.toFile(), config);
    }

    /**
     * Loads a project configuration from a .pg2 file.
     */
    public ProjectConfig load(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IOException("Project file not found: " + filePath);
        }
        if (!path.toFile().isFile()) {
            throw new IOException("Project is not a file: " + filePath);
        }
        return mapper.readValue(path.toFile(), ProjectConfig.class);
    }

    /**
     * Returns the ObjectMapper for use in API serialization.
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}
