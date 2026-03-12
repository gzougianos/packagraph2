package com.packagraph2.cli;

import com.packagraph2.server.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.net.URI;

@Command(name = "serve", description = "Start the interactive web UI")
public class ServeCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ServeCommand.class);

    @Option(names = "--project", description = "Path to a .pg2 project file to open")
    String projectFile;

    @Option(names = "--port", defaultValue = "8090", description = "Server port (default: 8090)")
    int port;

    @Option(names = "--no-browser", description = "Don't open the browser automatically")
    boolean noBrowser;

    @Override
    public void run() {
        try {
            log.info("Starting packagraph2 serve command (port={}, project={})", port, projectFile);

            WebServer server = new WebServer(port, projectFile);
            server.start();

            String url = "http://localhost:" + port;

            if (!noBrowser) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        log.info("Opening browser at {}", url);
                        Desktop.getDesktop().browse(new URI(url));
                    } else {
                        log.info("Desktop browse not supported. Open your browser and navigate to: {}", url);
                    }
                } catch (Exception e) {
                    log.warn("Could not open browser: {}", e.getMessage());
                    log.info("Open your browser and navigate to: {}", url);
                }
            }

            log.info("Press Ctrl+C to stop.");

            // Keep the main thread alive
            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Error starting server", e);
            System.exit(1);
        }
    }
}
