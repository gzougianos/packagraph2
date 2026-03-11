package com.packagraph2.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.packagraph2.analysis.DependencyAnalyzer;
import com.packagraph2.analysis.SourceScanner;
import com.packagraph2.dot.DotGenerator;
import com.packagraph2.model.DependencyGraph;
import com.packagraph2.model.ProjectConfig;
import com.packagraph2.project.ProjectManager;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.util.List;
import java.util.Map;

public class WebServer {

    private final int port;
    private final String projectFile;
    private final ProjectManager projectManager = new ProjectManager();
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

        app.start(port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    /**
     * POST /api/scan-sources
     * Body: { "rootDirectory": "/path/to/project" }
     * Returns: { "sourceDirectories": [...] }
     */
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

    /**
     * POST /api/analyze
     * Body: { "sourceDirectories": [...] }
     * Returns: DependencyGraph
     */
    private void analyze(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            List<String> dirs = new java.util.ArrayList<>();
            body.get("sourceDirectories").forEach(n -> dirs.add(n.asText()));
            DependencyGraph graph = analyzer.analyze(dirs);
            ctx.json(graph);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/dot
     * Body: ProjectConfig (with graph, rules, and display options)
     * Returns: { "dot": "digraph..." }
     */
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

    /**
     * POST /api/project/save
     * Body: { "filePath": "...", "config": { ProjectConfig } }
     */
    private void saveProject(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            ProjectConfig config = ProjectManager.getMapper().treeToValue(body.get("config"), ProjectConfig.class);
            projectManager.save(config, filePath);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/project/open
     * Body: { "filePath": "..." }
     * Returns: ProjectConfig
     */
    private void openProject(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            ProjectConfig config = projectManager.load(filePath);
            ctx.json(Map.of("config", config, "filePath", filePath));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/project/initial
     * Returns the project file passed via --project flag, if any.
     */
    private void getInitialProject(Context ctx) {
        try {
            if (projectFile != null && !projectFile.isBlank()) {
                ProjectConfig config = projectManager.load(projectFile);
                ctx.json(Map.of("config", config, "filePath", projectFile));
            } else {
                ctx.json(Map.of());
            }
        } catch (Exception e) {
            ctx.json(Map.of());
        }
    }
}
