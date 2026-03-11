package com.packagraph2.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.packagraph2.analysis.DependencyAnalyzer;
import com.packagraph2.analysis.SourceScanner;
import com.packagraph2.dot.DotGenerator;
import com.packagraph2.model.DependencyGraph;
import com.packagraph2.model.ProjectConfig;
import com.packagraph2.project.ProjectManager;
import com.packagraph2.project.RecentProjectsManager;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WebServer {

    private final int port;
    private final String projectFile;
    private final ProjectManager projectManager = new ProjectManager();
    private final RecentProjectsManager recentManager = new RecentProjectsManager();
    private final SourceScanner sourceScanner = new SourceScanner();
    private final DependencyAnalyzer analyzer = new DependencyAnalyzer();
    private final DotGenerator dotGenerator = new DotGenerator();
    private Javalin app;

    public WebServer(int port, String projectFile) {
        this.port = port;
        this.projectFile = projectFile;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.jsonMapper(new JacksonMapper());
        });

        // API routes
        app.post("/api/scan-sources", this::scanSources);
        app.post("/api/analyze", this::analyze);
        app.post("/api/dot", this::generateDot);
        app.post("/api/project/save", this::saveProject);
        app.post("/api/project/open", this::openProject);
        app.get("/api/project/initial", this::getInitialProject);
        app.get("/api/project/recent", this::getRecentProjects);
        app.post("/api/browse", this::browseDirectory);

        app.start(port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private void scanSources(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String rootDir = body.get("rootDirectory").asText();
            List<String> roots = sourceScanner.detectSourceRoots(rootDir);
            ctx.json(Map.of("sourceDirectories", roots));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void analyze(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            List<String> dirs = new ArrayList<>();
            body.get("sourceDirectories").forEach(n -> dirs.add(n.asText()));
            DependencyGraph graph = analyzer.analyze(dirs);
            ctx.json(graph);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void generateDot(Context ctx) {
        try {
            ProjectConfig config = ProjectManager.getMapper().readValue(ctx.body(), ProjectConfig.class);
            if (config.getGraph() == null) {
                ctx.status(400).json(Map.of("error", "No graph data provided"));
                return;
            }
            String dot = dotGenerator.generate(config.getGraph(), config);
            ctx.json(Map.of("dot", dot));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void saveProject(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            ProjectConfig config = ProjectManager.getMapper().treeToValue(body.get("config"), ProjectConfig.class);
            projectManager.save(config, filePath);
            recentManager.addRecentProject(config.getName(), filePath);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void openProject(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            ProjectConfig config = projectManager.load(filePath);
            recentManager.addRecentProject(config.getName(), filePath);
            ctx.json(Map.of("config", config, "filePath", filePath));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void getInitialProject(Context ctx) {
        try {
            if (projectFile != null && !projectFile.isBlank()) {
                ProjectConfig config = projectManager.load(projectFile);
                recentManager.addRecentProject(config.getName(), projectFile);
                ctx.json(Map.of("config", config, "filePath", projectFile));
            } else {
                ctx.json(Map.of());
            }
        } catch (Exception e) {
            ctx.json(Map.of());
        }
    }

    /**
     * GET /api/project/recent
     * Returns the list of recently opened projects.
     */
    private void getRecentProjects(Context ctx) {
        ctx.json(recentManager.getRecentProjects());
    }

    /**
     * POST /api/browse
     * Body: { "directory": "/path/to/dir" }
     * Returns: { "current": "/path/to/dir", "parent": "/path/to", "entries": [...] }
     * Each entry: { "name": "...", "path": "...", "type": "directory"|"pg2file"|"file" }
     */
    private void browseDirectory(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String dirPath = body.has("directory") && !body.get("directory").asText().isBlank()
                    ? body.get("directory").asText()
                    : System.getProperty("user.home");

            Path dir = Path.of(dirPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                ctx.status(400).json(Map.of("error", "Not a directory: " + dir));
                return;
            }

            List<Map<String, String>> entries = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    // Skip hidden files/dirs
                    if (name.startsWith(".")) continue;

                    if (Files.isDirectory(entry)) {
                        entries.add(Map.of(
                                "name", name,
                                "path", entry.toAbsolutePath().normalize().toString(),
                                "type", "directory"
                        ));
                    } else if (name.endsWith(".pg2")) {
                        entries.add(Map.of(
                                "name", name,
                                "path", entry.toAbsolutePath().normalize().toString(),
                                "type", "pg2file"
                        ));
                    }
                }
            }

            // Sort: directories first, then pg2 files, both alphabetical
            entries.sort((a, b) -> {
                int typeCompare = a.get("type").compareTo(b.get("type"));
                if (typeCompare != 0) return typeCompare;
                return a.get("name").compareToIgnoreCase(b.get("name"));
            });

            String parent = dir.getParent() != null ? dir.getParent().toString() : null;
            Map<String, Object> result = new HashMap<>();
            result.put("current", dir.toString());
            result.put("parent", parent);
            result.put("entries", entries);
            ctx.json(result);
        } catch (IOException e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
