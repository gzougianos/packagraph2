package com.packagraph2.dot;

import com.packagraph2.model.*;
import com.packagraph2.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Graphviz DOT format strings from a dependency graph.
 */
public class DotGenerator {

    private static final Logger log = LoggerFactory.getLogger(DotGenerator.class);

    private final RuleEngine ruleEngine = new RuleEngine();

    public record DotResult(String dot, Map<String, Map<String, List<ImportDetail>>> edgeDetails) {}

    /**
     * Generates a DOT string from the given project configuration.
     * Applies rules, detects cycles, and formats the graph.
     * Returns both the DOT string and the processed edge details.
     */
    public DotResult generate(DependencyGraph rawGraph, ProjectConfig config) {
        log.debug("Generating DOT: direction={}, circular={}, trimPrefix={}, transitiveReduction={}",
                config.getGraphDirection(), config.isHighlightCircularDependencies(),
                config.isTrimCommonPrefix(), config.isTransitiveReduction());

        // Apply rules to get the filtered graph
        DependencyGraph graph = ruleEngine.applyRules(
                rawGraph,
                config.getGroupingRules(),
                config.getHideRules());

        // Apply transitive reduction if enabled
        if (config.isTransitiveReduction()) {
            int edgesBefore = graph.getEdges().size();
            applyTransitiveReduction(graph);
            log.debug("Transitive reduction removed {} edges", edgesBefore - graph.getEdges().size());
        }

        // Detect circular dependencies if highlighting is enabled
        Set<Dependency> cyclicEdges = config.isHighlightCircularDependencies()
                ? ruleEngine.detectCircularDependencies(graph)
                : Set.of();

        // Build category color lookup
        Map<String, String> categoryColors = config.getCategories().stream()
                .collect(Collectors.toMap(Category::getId, Category::getColor, (a, b) -> a));

        // Find common prefix for trimming
        String commonPrefix = config.isTrimCommonPrefix()
                ? findCommonPrefix(graph)
                : "";

        StringBuilder dot = new StringBuilder();
        dot.append("digraph packages {\n");
        dot.append("  rankdir=").append(config.getGraphDirection().getDotValue()).append(";\n");
        dot.append("  node [shape=box, style=filled, fontname=\"Helvetica\", fontsize=11];\n");
        dot.append("  edge [fontname=\"Helvetica\", fontsize=9];\n");
        dot.append("  bgcolor=\"transparent\";\n");
        dot.append("  pad=0.5;\n");
        dot.append("  nodesep=0.6;\n");
        dot.append("  ranksep=0.8;\n");
        dot.append("\n");

        // Add nodes
        for (PackageNode node : graph.getNodes()) {
            String label = trimPrefix(node.getName(), commonPrefix);
            String nodeId = sanitizeId(node.getName());

            dot.append("  ").append(nodeId).append(" [");
            dot.append("label=\"").append(escapeLabel(label)).append("\"");

            // Check if node has a category color
            String catColor = null;
            if (node.getCategoryId() != null) {
                catColor = categoryColors.get(node.getCategoryId());
            }

            if (catColor != null) {
                String borderColor = darkenColor(catColor, 0.3);
                dot.append(", fillcolor=\"").append(catColor).append("\"");
                dot.append(", color=\"").append(borderColor).append("\"");
                dot.append(", fontcolor=\"#2c3e50\"");
            } else if (node.isExternal()) {
                dot.append(", fillcolor=\"#3a3a3a\", color=\"#666666\", fontcolor=\"#aaaaaa\", style=\"filled,dashed\"");
            } else {
                dot.append(", fillcolor=\"#d4e6f1\", color=\"#2980b9\", fontcolor=\"#1a3a5c\"");
            }

            dot.append("];\n");
        }

        dot.append("\n");

        // Add edges
        for (Dependency edge : graph.getEdges()) {
            String fromId = sanitizeId(edge.getFromPackage());
            String toId = sanitizeId(edge.getToPackage());

            dot.append("  ").append(fromId).append(" -> ").append(toId);

            if (cyclicEdges.contains(edge)) {
                dot.append(" [color=\"#e74c3c\", penwidth=2.0, style=bold]");
            } else {
                dot.append(" [color=\"#7f8c8d\"]");
            }

            dot.append(";\n");
        }

        dot.append("}\n");

        log.debug("DOT generated: {} nodes, {} edges", graph.getNodes().size(), graph.getEdges().size());
        return new DotResult(dot.toString(), graph.getEdgeDetails());
    }

    private String findCommonPrefix(DependencyGraph graph) {
        String commonPrefix = null;

        for (PackageNode node : graph.getNodes()) {
            if (node.isExternal()) {
                continue; // Only consider internal packages for prefix
            }
            String name = node.getName();
            if (commonPrefix == null) {
                commonPrefix = name;
            } else {
                commonPrefix = commonPrefix(commonPrefix, name);
            }
        }

        if (commonPrefix == null || commonPrefix.isEmpty()) {
            return "";
        }

        // Trim to last dot to get a full package segment
        int lastDot = commonPrefix.lastIndexOf('.');
        if (lastDot > 0) {
            return commonPrefix.substring(0, lastDot + 1);
        }

        return "";
    }

    private String commonPrefix(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        int i = 0;
        while (i < minLen && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    private String trimPrefix(String name, String prefix) {
        if (!prefix.isEmpty() && name.startsWith(prefix)) {
            return name.substring(prefix.length());
        }
        return name;
    }

    /**
     * Sanitizes a package name to be a valid DOT identifier.
     */
    private String sanitizeId(String name) {
        return "\"" + name.replace("\"", "\\\"") + "\"";
    }

    private String escapeLabel(String label) {
        return label.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Removes edges that are implied by transitive dependencies.
     * An edge A→C is redundant if there exists a path A→...→C of length >= 2.
     */
    private void applyTransitiveReduction(DependencyGraph graph) {
        // Build adjacency map
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Dependency edge : graph.getEdges()) {
            adjacency.computeIfAbsent(edge.getFromPackage(), k -> new HashSet<>())
                    .add(edge.getToPackage());
        }

        Set<Dependency> redundant = new HashSet<>();

        for (Dependency edge : graph.getEdges()) {
            String from = edge.getFromPackage();
            String to = edge.getToPackage();

            // Check if 'from' can reach 'to' through any other neighbor
            Set<String> neighbors = adjacency.getOrDefault(from, Set.of());
            for (String mid : neighbors) {
                if (mid.equals(to)) continue;
                if (canReach(mid, to, adjacency, new HashSet<>())) {
                    redundant.add(edge);
                    break;
                }
            }
        }

        graph.getEdges().removeAll(redundant);

        // Also remove corresponding edge details
        var details = graph.getEdgeDetails();
        for (Dependency edge : redundant) {
            var inner = details.get(edge.getFromPackage());
            if (inner != null) {
                inner.remove(edge.getToPackage());
            }
        }
    }

    private boolean canReach(String from, String target,
                             Map<String, Set<String>> adjacency, Set<String> visited) {
        if (!visited.add(from)) return false;
        for (String neighbor : adjacency.getOrDefault(from, Set.of())) {
            if (neighbor.equals(target)) return true;
            if (canReach(neighbor, target, adjacency, visited)) return true;
        }
        return false;
    }

    /**
     * Darkens a hex color by the given factor (0.0 = no change, 1.0 = black).
     */
    private String darkenColor(String hex, double factor) {
        try {
            hex = hex.replace("#", "");
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            r = (int) (r * (1 - factor));
            g = (int) (g * (1 - factor));
            b = (int) (b * (1 - factor));
            return String.format("#%02x%02x%02x", r, g, b);
        } catch (Exception e) {
            return "#666666";
        }
    }
}
