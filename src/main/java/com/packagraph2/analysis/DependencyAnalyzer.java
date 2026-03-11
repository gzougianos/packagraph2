package com.packagraph2.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.packagraph2.model.ClassInfo;
import com.packagraph2.model.DependencyGraph;
import com.packagraph2.model.ImportDetail;
import com.packagraph2.model.PackageNode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Analyzes Java source files using JavaParser to build a package dependency graph.
 */
public class DependencyAnalyzer {

    private final JavaParser parser = new JavaParser();

    /**
     * Analyzes the given source directories and returns a dependency graph.
     */
    public DependencyGraph analyze(List<String> sourceDirectories) throws IOException {
        Set<String> internalPackages = new LinkedHashSet<>();
        // Map: source package -> set of imported packages
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        // Map: package -> list of class info
        Map<String, List<ClassInfo>> packageClasses = new LinkedHashMap<>();
        // Map: fromPackage -> toPackage -> list of import details
        Map<String, Map<String, List<ImportDetail>>> edgeDetails = new LinkedHashMap<>();

        for (String sourceDir : sourceDirectories) {
            analyzeSourceDirectory(Path.of(sourceDir), internalPackages, dependencies, packageClasses, edgeDetails);
        }

        return buildGraph(internalPackages, dependencies, packageClasses, edgeDetails);
    }

    private void analyzeSourceDirectory(Path sourceDir, Set<String> internalPackages,
                                        Map<String, Set<String>> dependencies,
                                        Map<String, List<ClassInfo>> packageClasses,
                                        Map<String, Map<String, List<ImportDetail>>> edgeDetails) throws IOException {
        if (!Files.exists(sourceDir)) {
            return;
        }

        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    analyzeFile(file, internalPackages, dependencies, packageClasses, edgeDetails);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (dirName.equals(".git") || dirName.equals("target") || dirName.equals("build")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void analyzeFile(Path file, Set<String> internalPackages,
                             Map<String, Set<String>> dependencies,
                             Map<String, List<ClassInfo>> packageClasses,
                             Map<String, Map<String, List<ImportDetail>>> edgeDetails) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return;
            }

            CompilationUnit cu = result.getResult().get();

            // Get the package of this file
            String packageName = cu.getPackageDeclaration()
                    .map(PackageDeclaration::getNameAsString)
                    .orElse("(default)");

            internalPackages.add(packageName);

            // Collect type declarations from this file
            List<ClassInfo> classes = packageClasses.computeIfAbsent(packageName, k -> new ArrayList<>());
            for (TypeDeclaration<?> type : cu.getTypes()) {
                classes.add(extractClassInfo(type));
            }

            // The primary type name in this file (used as sourceClass for edge details)
            String sourceClass = file.getFileName().toString().replace(".java", "");

            // Get all imports
            Set<String> importedPackages = dependencies.computeIfAbsent(packageName, k -> new LinkedHashSet<>());

            for (ImportDeclaration imp : cu.getImports()) {
                String importName = imp.getNameAsString();
                String importedPackage = extractPackageName(importName, imp.isAsterisk());

                if (importedPackage != null && !importedPackage.equals(packageName)) {
                    importedPackages.add(importedPackage);

                    // Track the specific import detail
                    String importedClass = imp.isAsterisk() ? "*" : extractClassName(importName);
                    edgeDetails
                            .computeIfAbsent(packageName, k -> new LinkedHashMap<>())
                            .computeIfAbsent(importedPackage, k -> new ArrayList<>())
                            .add(new ImportDetail(sourceClass, importedClass));
                }
            }
        } catch (IOException e) {
            // Skip files we can't parse
        }
    }

    private ClassInfo extractClassInfo(TypeDeclaration<?> type) {
        String name = type.getNameAsString();
        String kind = resolveKind(type);
        String scope = resolveScope(type);
        return new ClassInfo(name, kind, scope);
    }

    private String resolveKind(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof AnnotationDeclaration) {
            return "annotation";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? "interface" : "class";
        }
        return "class";
    }

    private String resolveScope(TypeDeclaration<?> type) {
        var modifiers = type.getModifiers();
        for (var mod : modifiers) {
            if (mod.getKeyword() == Modifier.Keyword.PUBLIC) return "public";
            if (mod.getKeyword() == Modifier.Keyword.PROTECTED) return "protected";
            if (mod.getKeyword() == Modifier.Keyword.PRIVATE) return "private";
        }
        return "package-private";
    }

    /**
     * Extracts the package name from an import statement.
     * For "import com.example.MyClass" returns "com.example".
     * For "import com.example.*" returns "com.example".
     */
    private String extractPackageName(String importName, boolean isAsterisk) {
        if (isAsterisk) {
            // import com.example.* -> package is com.example
            return importName;
        }
        // import com.example.MyClass -> package is com.example
        int lastDot = importName.lastIndexOf('.');
        if (lastDot > 0) {
            return importName.substring(0, lastDot);
        }
        return null;
    }

    private String extractClassName(String importName) {
        int lastDot = importName.lastIndexOf('.');
        if (lastDot >= 0) {
            return importName.substring(lastDot + 1);
        }
        return importName;
    }

    private DependencyGraph buildGraph(Set<String> internalPackages,
                                       Map<String, Set<String>> dependencies,
                                       Map<String, List<ClassInfo>> packageClasses,
                                       Map<String, Map<String, List<ImportDetail>>> edgeDetails) {
        DependencyGraph graph = new DependencyGraph();
        graph.setPackageClasses(packageClasses);
        graph.setEdgeDetails(edgeDetails);

        // Add all internal packages as nodes
        for (String pkg : internalPackages) {
            graph.addNode(new PackageNode(pkg, false));
        }

        // Add edges and external package nodes
        for (var entry : dependencies.entrySet()) {
            String fromPackage = entry.getKey();
            for (String toPackage : entry.getValue()) {
                // If the target package is not internal, add it as external
                if (!internalPackages.contains(toPackage)) {
                    graph.addNode(new PackageNode(toPackage, true));
                }
                graph.addEdge(fromPackage, toPackage);
            }
        }

        return graph;
    }
}
