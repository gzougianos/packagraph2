package com.packagraph2;

import com.packagraph2.cli.ServeCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "packagraph2",
        description = "Java package dependency browser and visualizer",
        subcommands = {
                ServeCommand.class
        },
        mixinStandardHelpOptions = true,
        version = "packagraph2 1.0.0"
)
public class Main implements Runnable {

    public static void main(String[] args) {
        if (args == null || args.length == 0){
            args = new String[]{"serve"};
        }
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
