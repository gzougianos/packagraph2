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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

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
        log.info("Starting web server on port {}", port);

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
        app.post("/api/git/clone", this::gitClone);
        app.post("/api/open-directory", this::openDirectory);

        app.start(port);
        log.info("Web server started at http://localhost:{}", port);
    }

    public void stop() {
        if (app != null) {
            log.info("Stopping web server");
            app.stop();
        }
    }

    private void scanSources(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String rootDir = body.get("rootDirectory").asText();
            log.info("API scan-sources: rootDirectory={}", rootDir);
            List<String> roots = sourceScanner.detectSourceRoots(rootDir);
            ctx.json(Map.of("sourceDirectories", roots));
        } catch (Exception e) {
            log.error("Error scanning sources", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void analyze(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            List<String> dirs = new ArrayList<>();
            body.get("sourceDirectories").forEach(n -> dirs.add(n.asText()));
            log.info("API analyze: {} source directories", dirs.size());
            DependencyGraph graph = analyzer.analyze(dirs);
            ctx.json(graph);
        } catch (Exception e) {
            log.error("Error analyzing", e);
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
            log.debug("API dot: generating graph for project '{}'", config.getName());
            var result = dotGenerator.generate(config.getGraph(), config);
            ctx.json(Map.of("dot", result.dot(), "edgeDetails", result.edgeDetails()));
        } catch (Exception e) {
            log.error("Error generating DOT", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void saveProject(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            ProjectConfig config = ProjectManager.getMapper().treeToValue(body.get("config"), ProjectConfig.class);
            log.info("API save: saving project '{}' to {}", config.getName(), filePath);
            projectManager.save(config, filePath);
            recentManager.addRecentProject(config.getName(), filePath);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error saving project", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void openProject(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            log.info("API open: loading project from {}", filePath);
            ProjectConfig config = projectManager.load(filePath);
            recentManager.addRecentProject(config.getName(), filePath);
            ctx.json(Map.of("config", config, "filePath", filePath));
        } catch (Exception e) {
            log.error("Error opening project", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void getInitialProject(Context ctx) {
        try {
            if (projectFile != null && !projectFile.isBlank()) {
                log.info("Loading initial project: {}", projectFile);
                ProjectConfig config = projectManager.load(projectFile);
                recentManager.addRecentProject(config.getName(), projectFile);
                ctx.json(Map.of("config", config, "filePath", projectFile));
            } else {
                ctx.json(Map.of());
            }
        } catch (Exception e) {
            log.warn("Could not load initial project '{}': {}", projectFile, e.getMessage());
            ctx.json(Map.of());
        }
    }

    private void getRecentProjects(Context ctx) {
        ctx.json(recentManager.getRecentProjects());
    }

    private void browseDirectory(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String dirPath = body.has("directory") && !body.get("directory").asText().isBlank()
                    ? body.get("directory").asText()
                    : System.getProperty("user.home");

            Path dir = Path.of(dirPath).toAbsolutePath().normalize();
            log.debug("API browse: {}", dir);
            if (!Files.isDirectory(dir)) {
                ctx.status(400).json(Map.of("error", "Not a directory: " + dir));
                return;
            }

            List<Map<String, String>> entries = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
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
            log.error("Error browsing directory", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void gitClone(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String repoUrl = body.get("repoUrl").asText().trim();
            String branch = body.has("branch") && !body.get("branch").asText().isBlank()
                    ? body.get("branch").asText().trim() : null;
            String targetDir = body.has("targetDirectory") && !body.get("targetDirectory").asText().isBlank()
                    ? body.get("targetDirectory").asText().trim() : null;
            if ("null".equalsIgnoreCase(targetDir)){
                targetDir = null;
            }

            if (repoUrl.isBlank()) {
                ctx.status(400).json(Map.of("error", "Repository URL is required"));
                return;
            }

            log.info("API git/clone: repo={}, branch={}, target={}", repoUrl, branch, targetDir);

            // Determine clone target
            Path cloneTarget;
            if (targetDir != null) {
                cloneTarget = Path.of(targetDir);
                if (Files.exists(cloneTarget)) {
                    try (var entries = Files.list(cloneTarget)) {
                        if (entries.findAny().isPresent()) {
                            ctx.status(400).json(Map.of("error", "Target directory is not empty: " + targetDir));
                            return;
                        }
                    }
                }
                Files.createDirectories(cloneTarget);
            } else {
                String repoName = extractRepoName(repoUrl);
                cloneTarget = Files.createTempDirectory("packagraph2-" + repoName + "-");
                log.info("Using temp directory: {}", cloneTarget);
            }

            // Build git clone command
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("clone");
            cmd.add("--depth");
            cmd.add("1");
            if (branch != null) {
                cmd.add("--branch");
                cmd.add(branch);
            }
            cmd.add(repoUrl);
            cmd.add(cloneTarget.toString());

            log.info("Running: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("Git clone failed (exit code {}): {}", exitCode, output);
                ctx.status(500).json(Map.of("error", "Git clone failed: " + output));
                return;
            }

            log.info("Git clone successful to {}", cloneTarget);

            // Detect actual branch name
            String actualBranch = detectBranch(cloneTarget);
            if (actualBranch == null && branch != null) {
                actualBranch = branch;
            }

            ctx.json(Map.of(
                    "directory", cloneTarget.toString(),
                    "branch", actualBranch != null ? actualBranch : "unknown"
            ));
        } catch (Exception e) {
            log.error("Error cloning git repository", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private String extractRepoName(String repoUrl) {
        String name = repoUrl;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "");
    }

    private void openDirectory(Context ctx) {
        try {
            JsonNode body = ProjectManager.getMapper().readTree(ctx.body());
            String filePath = body.get("filePath").asText();
            Path dir = Path.of(filePath).getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                ctx.status(400).json(Map.of("error", "Directory not found: " + dir));
                return;
            }
            log.info("API open-directory: {}", dir);

            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer", dir.toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", dir.toString());
            } else {
                pb = new ProcessBuilder("xdg-open", dir.toString());
            }
            pb.start();
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error opening directory", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private String detectBranch(Path repoDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 ? output : null;
        } catch (Exception e) {
            log.debug("Could not detect branch in {}", repoDir, e);
            return null;
        }
    }
}
