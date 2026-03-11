package com.packagraph2.cli;

import com.packagraph2.dot.DotGenerator;
import com.packagraph2.model.ProjectConfig;
import com.packagraph2.project.ProjectManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "export", description = "Export the dependency graph to an image file")
public class ExportCommand implements Runnable {

    @Option(names = "--project", required = true, description = "Path to a .pg2 project file")
    String projectFile;

    @Option(names = "--format", defaultValue = "svg", description = "Output format: svg, png, pdf (default: svg)")
    String format;

    @Option(names = {"-o", "--output"}, required = true, description = "Output file path")
    String outputFile;

    @Option(names = "--dot-only", description = "Only generate the DOT file without rendering")
    boolean dotOnly;

    @Override
    public void run() {
        try {
            ProjectManager pm = new ProjectManager();
            ProjectConfig config = pm.load(projectFile);

            if (config.getGraph() == null) {
                System.err.println("Error: Project has no analyzed graph. Run 'serve' first to analyze the project.");
                System.exit(1);
            }

            DotGenerator dotGen = new DotGenerator();
            String dot = dotGen.generate(config.getGraph(), config);

            if (dotOnly) {
                Files.writeString(Path.of(outputFile), dot);
                System.out.println("DOT file written to: " + outputFile);
                return;
            }

            // Render using system Graphviz
            renderWithGraphviz(dot, format, outputFile);

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void renderWithGraphviz(String dot, String format, String output) {
        try {
            ProcessBuilder pb = new ProcessBuilder("dot", "-T" + format, "-o", output);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Write DOT to stdin
            process.getOutputStream().write(dot.getBytes());
            process.getOutputStream().close();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                System.err.println("Graphviz error: " + error);
                System.exit(1);
            }

            System.out.println("Graph exported to: " + output);

        } catch (IOException e) {
            if (e.getMessage().contains("No such file") || e.getMessage().contains("Cannot run")) {
                System.err.println("Error: Graphviz 'dot' command not found. Install Graphviz to export images.");
                System.err.println("Use --dot-only to export just the DOT file instead.");
            } else {
                System.err.println("Error running Graphviz: " + e.getMessage());
            }
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Export interrupted.");
            System.exit(1);
        }
    }
}
