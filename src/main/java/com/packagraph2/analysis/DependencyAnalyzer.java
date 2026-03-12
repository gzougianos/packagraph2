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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Analyzes Java source files using JavaParser to build a package dependency graph.
 */
public class DependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzer.class);

    private final JavaParser parser = new JavaParser();

    /**
     * Analyzes the given source directories and returns a dependency graph.
     */
    public DependencyGraph analyze(List<String> sourceDirectories) throws IOException {
        log.info("Starting analysis of {} source directories", sourceDirectories.size());
        Set<String> internalPackages = new LinkedHashSet<>();
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Map<String, List<ClassInfo>> packageClasses = new LinkedHashMap<>();
        Map<String, Map<String, List<ImportDetail>>> edgeDetails = new LinkedHashMap<>();

        for (String sourceDir : sourceDirectories) {
            analyzeSourceDirectory(Path.of(sourceDir), internalPackages, dependencies, packageClasses, edgeDetails);
        }

        DependencyGraph graph = buildGraph(internalPackages, dependencies, packageClasses, edgeDetails);
        log.info("Analysis complete: {} packages ({} internal, {} external), {} edges",
                graph.getNodes().size(),
                internalPackages.size(),
                graph.getNodes().size() - internalPackages.size(),
                graph.getEdges().size());
        return graph;
    }

    private void analyzeSourceDirectory(Path sourceDir, Set<String> internalPackages,
                                        Map<String, Set<String>> dependencies,
                                        Map<String, List<ClassInfo>> packageClasses,
                                        Map<String, Map<String, List<ImportDetail>>> edgeDetails) throws IOException {
        if (!Files.exists(sourceDir)) {
            log.warn("Source directory does not exist, skipping: {}", sourceDir);
            return;
        }

        log.info("Analyzing source directory: {}", sourceDir);
        int[] fileCount = {0};

        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    fileCount[0]++;
                    analyzeFile(file, internalPackages, dependencies, packageClasses, edgeDetails);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (dirName.equals(".git") || dirName.equals("target") || dirName.equals("build")) {
                    log.debug("Skipping directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Analyzed {} .java files in {}", fileCount[0], sourceDir);
    }

    private void analyzeFile(Path file, Set<String> internalPackages,
                             Map<String, Set<String>> dependencies,
                             Map<String, List<ClassInfo>> packageClasses,
                             Map<String, Map<String, List<ImportDetail>>> edgeDetails) {
        log.debug("Parsing file: {}", file);
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.warn("Failed to parse file: {}", file);
                return;
            }

            CompilationUnit cu = result.getResult().get();

            String packageName = cu.getPackageDeclaration()
                    .map(PackageDeclaration::getNameAsString)
                    .orElse("(default)");

            internalPackages.add(packageName);

            List<ClassInfo> classes = packageClasses.computeIfAbsent(packageName, k -> new ArrayList<>());
            for (TypeDeclaration<?> type : cu.getTypes()) {
                ClassInfo ci = extractClassInfo(type);
                classes.add(ci);
                log.debug("  Found {} {} {}.{}", ci.getScope(), ci.getKind(), packageName, ci.getName());
            }

            String sourceClassName = file.getFileName().toString().replace(".java", "");
            String fqSourceClass = packageName.equals("(default)") ? sourceClassName : packageName + "." + sourceClassName;

            Set<String> importedPackages = dependencies.computeIfAbsent(packageName, k -> new LinkedHashSet<>());

            for (ImportDeclaration imp : cu.getImports()) {
                String importName = imp.getNameAsString();
                String importedPackage = extractPackageName(importName, imp.isAsterisk());

                if (importedPackage != null && !importedPackage.equals(packageName)) {
                    importedPackages.add(importedPackage);

                    String fqImportedClass = imp.isAsterisk() ? importedPackage + ".*" : importName;
                    edgeDetails
                            .computeIfAbsent(packageName, k -> new LinkedHashMap<>())
                            .computeIfAbsent(importedPackage, k -> new ArrayList<>())
                            .add(new ImportDetail(fqSourceClass, fqImportedClass));
                }
            }
        } catch (IOException e) {
            log.warn("Could not read file: {}", file, e);
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

    private String extractPackageName(String importName, boolean isAsterisk) {
        if (isAsterisk) {
            return importName;
        }
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

        for (String pkg : internalPackages) {
            graph.addNode(new PackageNode(pkg, false));
        }

        for (var entry : dependencies.entrySet()) {
            String fromPackage = entry.getKey();
            for (String toPackage : entry.getValue()) {
                if (!internalPackages.contains(toPackage)) {
                    graph.addNode(new PackageNode(toPackage, true));
                }
                graph.addEdge(fromPackage, toPackage);
            }
        }

        return graph;
    }
}
