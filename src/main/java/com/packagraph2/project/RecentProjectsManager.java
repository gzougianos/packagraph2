package com.packagraph2.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a list of recently opened projects stored in ~/.packagraph2/recent-projects.json.
 */
public class RecentProjectsManager {

    private static final int MAX_RECENT = 20;
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".packagraph2");
    private static final Path RECENT_FILE = CONFIG_DIR.resolve("recent-projects.json");
    private static final ObjectMapper mapper = ProjectManager.getMapper();

    public record RecentProject(String name, String filePath, long lastOpened) {}

    public List<RecentProject> getRecentProjects() {
        try {
            if (Files.exists(RECENT_FILE)) {
                return mapper.readValue(RECENT_FILE.toFile(), new TypeReference<>() {});
            }
        } catch (IOException e) {
            // Corrupted file, start fresh
        }
        return new ArrayList<>();
    }

    public void addRecentProject(String name, String filePath) {
        List<RecentProject> list = new ArrayList<>(getRecentProjects());

        // Remove existing entry with same path
        list.removeIf(p -> p.filePath().equals(filePath));

        // Add at the top
        list.addFirst(new RecentProject(
                name != null ? name : Path.of(filePath).getFileName().toString(),
                filePath,
                System.currentTimeMillis()
        ));

        // Trim to max
        if (list.size() > MAX_RECENT) {
            list = list.subList(0, MAX_RECENT);
        }

        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(RECENT_FILE.toFile(), list);
        } catch (IOException e) {
            // Best effort
        }
    }
}
