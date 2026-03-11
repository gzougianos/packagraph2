package com.packagraph2.cli;

import com.packagraph2.server.WebServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.net.URI;

@Command(name = "serve", description = "Start the interactive web UI")
public class ServeCommand implements Runnable {

    @Option(names = "--project", description = "Path to a .pg2 project file to open")
    String projectFile;

    @Option(names = "--port", defaultValue = "8090", description = "Server port (default: 8090)")
    int port;

    @Option(names = "--no-browser", description = "Don't open the browser automatically")
    boolean noBrowser;

    @Override
    public void run() {
        try {
            WebServer server = new WebServer(port, projectFile);
            server.start();

            String url = "http://localhost:" + port;
            System.out.println("packagraph2 server started at " + url);

            if (!noBrowser) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(new URI(url));
                    } else {
                        System.out.println("Open your browser and navigate to: " + url);
                    }
                } catch (Exception e) {
                    System.out.println("Open your browser and navigate to: " + url);
                }
            }

            System.out.println("Press Ctrl+C to stop.");

            // Keep the main thread alive
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Error starting server: " + e.getMessage());
            System.exit(1);
        }
    }
}
