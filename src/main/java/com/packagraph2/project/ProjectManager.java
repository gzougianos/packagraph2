package com.packagraph2.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.packagraph2.model.ProjectConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages loading and saving of .pg2 project files.
 */
public class ProjectManager {

    private static final Logger log = LoggerFactory.getLogger(ProjectManager.class);

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Saves the project configuration to a .pg2 file.
     */
    public void save(ProjectConfig config, String filePath) throws IOException {
        Path path = Path.of(filePath);
        log.info("Saving project '{}' to {}", config.getName(), path);
        mapper.writeValue(path.toFile(), config);
        log.debug("Project saved successfully ({} bytes)", Files.size(path));
    }

    /**
     * Loads a project configuration from a .pg2 file.
     */
    public ProjectConfig load(String filePath) throws IOException {
        Path path = Path.of(filePath);
        log.info("Loading project from {}", path);
        if (!Files.exists(path)) {
            throw new IOException("Project file not found: " + filePath);
        }
        if (!path.toFile().isFile()) {
            throw new IOException("Project is not a file: " + filePath);
        }
        ProjectConfig config = mapper.readValue(path.toFile(), ProjectConfig.class);
        log.info("Loaded project '{}': {} packages, {} grouping rules, {} hide rules",
                config.getName(),
                config.getGraph() != null ? config.getGraph().getNodes().size() : 0,
                config.getGroupingRules().size(),
                config.getHideRules().size());
        return config;
    }

    /**
     * Returns the ObjectMapper for use in API serialization.
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}
