package com.packagraph2.analysis;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a root directory for Java source roots.
 * Detects source roots by reading package declarations and working backwards
 * from the file path.
 */
public class SourceScanner {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*?)\\s*;", Pattern.MULTILINE);

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "build", "node_modules", ".idea", ".gradle",
            ".mvn", ".settings", "bin", "out", ".vscode", ".metadata"
    );

    /**
     * Scans the given root directory and returns a list of detected source roots.
     * A source root is the directory that corresponds to the root of the package hierarchy
     * (e.g., src/main/java).
     */
    public List<String> detectSourceRoots(String rootDirectory) throws IOException {
        Path root = Path.of(rootDirectory).toAbsolutePath().normalize();
        Set<String> sourceRoots = new LinkedHashSet<>();
        List<Path> javaFiles = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (SKIP_DIRS.contains(dirName) || dirName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        for (Path javaFile : javaFiles) {
            String sourceRoot = detectSourceRoot(javaFile);
            if (sourceRoot != null) {
                sourceRoots.add(sourceRoot);
            }
        }

        return new ArrayList<>(sourceRoots);
    }

    /**
     * Given a .java file, reads its package declaration and computes the source root.
     * For example, if the file is at /project/src/main/java/com/example/Foo.java
     * and declares package com.example, the source root is /project/src/main/java.
     */
    private String detectSourceRoot(Path javaFile) {
        try {
            String content = Files.readString(javaFile);
            Matcher matcher = PACKAGE_PATTERN.matcher(content);
            if (!matcher.find()) {
                // No package declaration = default package, source root is the file's directory
                return javaFile.getParent().toAbsolutePath().normalize().toString();
            }

            String packageName = matcher.group(1);
            String packagePath = packageName.replace('.', '/');
            Path filePath = javaFile.toAbsolutePath().normalize();
            String filePathStr = filePath.getParent().toString().replace('\\', '/');

            if (filePathStr.endsWith(packagePath)) {
                String sourceRoot = filePathStr.substring(0, filePathStr.length() - packagePath.length());
                // Remove trailing slash
                if (sourceRoot.endsWith("/")) {
                    sourceRoot = sourceRoot.substring(0, sourceRoot.length() - 1);
                }
                return sourceRoot;
            }
        } catch (IOException e) {
            // Skip files we can't read
        }
        return null;
    }
}
