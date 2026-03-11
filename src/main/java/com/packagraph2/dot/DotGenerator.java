package com.packagraph2.dot;

import com.packagraph2.model.*;
import com.packagraph2.rules.RuleEngine;

import java.util.Set;

/**
 * Generates Graphviz DOT format strings from a dependency graph.
 */
public class DotGenerator {

    private final RuleEngine ruleEngine = new RuleEngine();

    /**
     * Generates a DOT string from the given project configuration.
     * Applies rules, detects cycles, and formats the graph.
     */
    public String generate(DependencyGraph rawGraph, ProjectConfig config) {
        // Apply rules to get the filtered graph
        DependencyGraph graph = ruleEngine.applyRules(
                rawGraph,
                config.getGroupingRules(),
                config.getHideRules());

        // Detect circular dependencies if highlighting is enabled
        Set<Dependency> cyclicEdges = config.isHighlightCircularDependencies()
                ? ruleEngine.detectCircularDependencies(graph)
                : Set.of();

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

            if (node.isExternal()) {
                dot.append(", fillcolor=\"#e8e8e8\", color=\"#999999\", fontcolor=\"#666666\"");
            } else {
                dot.append(", fillcolor=\"#d4e6f1\", color=\"#2980b9\", fontcolor=\"#2c3e50\"");
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
        return dot.toString();
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
}
