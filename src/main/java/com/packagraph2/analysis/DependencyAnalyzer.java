package com.packagraph2.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes Java source files using JavaParser to build a package dependency graph.
 */
public class DependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzer.class);

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*?)\\s*;", Pattern.MULTILINE);

    private final JavaParser parser;

    public DependencyAnalyzer() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        this.parser = new JavaParser(config);
    }

    /**
     * Analyzes the given source directories and returns a dependency graph.
     */
    public DependencyGraph analyze(List<String> sourceDirectories) throws IOException {
        log.info("Starting analysis of {} source directories", sourceDirectories.size());

        // Phase 1: Regex-based scan to collect ALL internal package names.
        // This is robust even if JavaParser fails to parse certain files (e.g., records, sealed classes).
        Set<String> internalPackages = new LinkedHashSet<>();
        for (String sourceDir : sourceDirectories) {
            collectInternalPackages(Path.of(sourceDir), internalPackages);
        }
        log.info("Phase 1 (regex): found {} internal packages", internalPackages.size());

        // Phase 2: JavaParser-based analysis for dependencies and class details.
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Map<String, List<ClassInfo>> packageClasses = new LinkedHashMap<>();
        Map<String, Map<String, List<ImportDetail>>> edgeDetails = new LinkedHashMap<>();

        for (String sourceDir : sourceDirectories) {
            analyzeSourceDirectory(Path.of(sourceDir), internalPackages, dependencies, packageClasses, edgeDetails);
        }

        // Phase 3: Post-process — resolve dependency targets against known internal packages.
        // The uppercase heuristic in extractPackageName may over-strip for internal packages
        // (e.g., "com.app.Model" → "com.app" when "com.app.Model" is actually a package).
        // Re-check each dependency target: if not already internal, try to match against known packages.
        postProcessDependencies(internalPackages, dependencies, edgeDetails);

        DependencyGraph graph = buildGraph(internalPackages, dependencies, packageClasses, edgeDetails);
        log.info("Analysis complete: {} packages ({} internal, {} external), {} edges",
                graph.getNodes().size(),
                internalPackages.size(),
                graph.getNodes().size() - internalPackages.size(),
                graph.getEdges().size());
        return graph;
    }

    /**
     * Collects all internal package names using a simple regex scan.
     * This is robust against JavaParser failures — even if a file can't be parsed,
     * its package declaration is still captured.
     */
    private void collectInternalPackages(Path sourceDir, Set<String> internalPackages) throws IOException {
        if (!Files.exists(sourceDir)) return;

        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    try {
                        String content = Files.readString(file);
                        Matcher matcher = PACKAGE_PATTERN.matcher(content);
                        if (matcher.find()) {
                            internalPackages.add(matcher.group(1));
                        } else {
                            internalPackages.add("(default)");
                        }
                    } catch (IOException e) {
                        log.debug("Could not read file for package scan: {}", file, e);
                    }
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
                log.warn("Failed to parse file: {} — problems: {}", file, result.getProblems());
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
                String importedPackage = extractPackageName(importName, imp.isAsterisk(), imp.isStatic(), internalPackages);

                if (importedPackage != null && !importedPackage.equals(packageName)) {
                    importedPackages.add(importedPackage);

                    // For static imports, the class is one level above the member
                    String fqImportedClass;
                    if (imp.isAsterisk()) {
                        fqImportedClass = importedPackage + ".*";
                    } else if (imp.isStatic()) {
                        // importName is pkg.Class.member — strip member to get pkg.Class
                        int lastDot = importName.lastIndexOf('.');
                        fqImportedClass = lastDot > 0 ? importName.substring(0, lastDot) : importName;
                    } else {
                        fqImportedClass = importName;
                    }
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

    /**
     * Extracts the package name from an import statement.
     * First applies structural stripping (based on static/asterisk flags), then tries to match
     * against known internal packages. Falls back to lowercase convention trimming for external packages.
     * (Logic ported from packagraph's JavaClass.java)
     */
    private String extractPackageName(String importName, boolean isAsterisk, boolean isStatic,
                                      Set<String> internalPackages) {
        // Step 1: Structural stripping based on import type.
        // This removes class/member names from the import to get a candidate package name.
        String packageName = adaptImport(importName, isAsterisk, isStatic);
        if (packageName == null || packageName.isEmpty()) return null;

        // Step 2: Try to match against known internal packages.
        // Check exact match first, then walk prefixes for edge cases (e.g., inner classes).
        if (internalPackages.contains(packageName)) {
            return packageName;
        }
        String candidate = packageName;
        while (true) {
            int lastDot = candidate.lastIndexOf('.');
            if (lastDot <= 0) break;
            candidate = candidate.substring(0, lastDot);
            if (internalPackages.contains(candidate)) {
                return candidate;
            }
        }

        // Step 3: No internal match — apply lowercase convention for external packages.
        return keepOnlyLowerCase(packageName);
    }

    /**
     * Structurally strips class/member names from an import based on its type.
     * Without bytecode interpretation, the parser only tells us whether the import
     * is static and/or has an asterisk. We strip accordingly:
     * <ul>
     *   <li>{@code import java.io.*} → parser gives {@code java.io} → use as-is</li>
     *   <li>{@code import java.util.HashMap} → parser gives {@code java.util.HashMap} → trim class name</li>
     *   <li>{@code import static java.lang.System.setErr} → parser gives {@code java.lang.System.setErr} → trim method + class</li>
     *   <li>{@code import static javax.swing.SwingUtilities.*} → parser gives {@code javax.swing.SwingUtilities} → trim class name</li>
     * </ul>
     */
    private String adaptImport(String importName, boolean isAsterisk, boolean isStatic) {
        // import java.io.*, library gives: java.io
        if (!isStatic && isAsterisk) {
            return importName;
        }
        // import java.util.HashMap, library gives: java.util.HashMap
        // So need to trim className
        if (!isStatic) {
            return trimUpToLastDot(importName);
        }
        // import static java.lang.System.setErr, library gives: java.lang.System.setErr
        // So need to trim method name + class name
        if (!isAsterisk) {
            return trimUpToLastDot(trimUpToLastDot(importName));
        }
        // import static javax.swing.SwingUtilities.*, library gives: javax.swing.SwingUtilities
        // So need to trim class name
        return trimUpToLastDot(importName);
    }

    /**
     * If the package name still contains uppercase-starting segments (inner classes, etc.),
     * trims from the first such segment. This handles edge cases like:
     * {@code import static java.lang.System.SomeInnerClass.SOME_STATIC_VAR}
     * where after structural stripping we may still have {@code java.lang.System}.
     * We assume the standard lowercase package-name convention and trim accordingly.
     */
    private String keepOnlyLowerCase(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        if (followsLowercaseConvention(packageName)) return packageName;

        String trimmed = trimUpToFirstUppercase(packageName);
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean followsLowercaseConvention(String name) {
        for (String part : name.split("\\.")) {
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    private String trimUpToFirstUppercase(String input) {
        for (int i = 1; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c) && input.charAt(i - 1) == '.') {
                return input.substring(0, i);
            }
        }
        return input;
    }

    private String trimUpToLastDot(String str) {
        if (str == null || !str.contains(".")) return str;
        return str.substring(0, str.lastIndexOf('.'));
    }

    private String extractClassName(String importName) {
        int lastDot = importName.lastIndexOf('.');
        if (lastDot >= 0) {
            return importName.substring(lastDot + 1);
        }
        return importName;
    }

    /**
     * Post-processes dependencies: if a dependency target is not recognized as internal
     * but a known internal package is a prefix of it, remap the dependency to that package.
     * This catches cases where the heuristic extracted too much (e.g., kept a class name as
     * part of the package) or too little.
     */
    private void postProcessDependencies(Set<String> internalPackages,
                                         Map<String, Set<String>> dependencies,
                                         Map<String, Map<String, List<ImportDetail>>> edgeDetails) {
        for (var entry : dependencies.entrySet()) {
            String fromPkg = entry.getKey();
            Set<String> originalDeps = entry.getValue();
            Set<String> fixedDeps = new LinkedHashSet<>();

            for (String dep : originalDeps) {
                if (internalPackages.contains(dep)) {
                    fixedDeps.add(dep);
                    continue;
                }
                // Try to find a matching internal package by progressively stripping trailing segments
                String candidate = dep;
                String matched = null;
                while (true) {
                    int lastDot = candidate.lastIndexOf('.');
                    if (lastDot <= 0) break;
                    candidate = candidate.substring(0, lastDot);
                    if (internalPackages.contains(candidate)) {
                        matched = candidate;
                        break;
                    }
                }
                if (matched != null && !matched.equals(fromPkg)) {
                    log.debug("Remapped dependency {} -> {} (matched internal package)", dep, matched);
                    fixedDeps.add(matched);
                    // Remap edge details too
                    Map<String, List<ImportDetail>> fromEdges = edgeDetails.get(fromPkg);
                    if (fromEdges != null && fromEdges.containsKey(dep)) {
                        List<ImportDetail> details = fromEdges.remove(dep);
                        fromEdges.computeIfAbsent(matched, k -> new ArrayList<>()).addAll(details);
                    }
                } else {
                    fixedDeps.add(dep);
                }
            }
            entry.setValue(fixedDeps);
        }
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
