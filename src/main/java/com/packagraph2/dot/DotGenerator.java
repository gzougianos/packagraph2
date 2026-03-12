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

        // Remove external dependencies if disabled
        if (!config.isIncludeExternalDependencies()) {
            Set<String> externalNames = new HashSet<>();
            graph.getNodes().removeIf(node -> {
                if (node.isExternal()) {
                    externalNames.add(node.getName());
                    return true;
                }
                return false;
            });
            graph.getEdges().removeIf(edge ->
                    externalNames.contains(edge.getFromPackage()) ||
                    externalNames.contains(edge.getToPackage()));
            log.debug("Excluded {} external packages", externalNames.size());
        }

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

        // Build comments lookup
        Map<String, String> comments = config.getComments() != null ? config.getComments() : Map.of();

        // Add nodes
        for (PackageNode node : graph.getNodes()) {
            String label = trimPrefix(node.getName(), commonPrefix);
            String nodeId = sanitizeId(node.getName());

            boolean hasComment = comments.containsKey(node.getName())
                    && !comments.get(node.getName()).isBlank();

            // Append pencil indicator for commented nodes
            if (hasComment) {
                label = label + " \u270E";
            }

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

            // Add thicker border for commented nodes
            if (hasComment) {
                dot.append(", penwidth=2.5");
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
     * Uses SCC-based approach: edges within cycles are never removed,
     * only cross-component edges that are transitively implied are removed.
     */
    private void applyTransitiveReduction(DependencyGraph graph) {
        // Build adjacency map
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Dependency edge : graph.getEdges()) {
            adjacency.computeIfAbsent(edge.getFromPackage(), k -> new HashSet<>())
                    .add(edge.getToPackage());
        }

        // Collect all nodes
        Set<String> allNodes = new HashSet<>();
        for (Dependency edge : graph.getEdges()) {
            allNodes.add(edge.getFromPackage());
            allNodes.add(edge.getToPackage());
        }

        // Step 1: Find strongly connected components (Kosaraju's algorithm)
        Map<String, Integer> nodeToScc = computeSccs(allNodes, adjacency);

        // Step 2: Build condensed DAG of SCCs
        Map<Integer, Set<Integer>> condensedAdj = new HashMap<>();
        for (Dependency edge : graph.getEdges()) {
            int fromScc = nodeToScc.getOrDefault(edge.getFromPackage(), -1);
            int toScc = nodeToScc.getOrDefault(edge.getToPackage(), -1);
            if (fromScc != toScc) {
                condensedAdj.computeIfAbsent(fromScc, k -> new HashSet<>()).add(toScc);
            }
        }

        // Step 3: Apply transitive reduction on the condensed DAG
        Set<Long> redundantSccEdges = new HashSet<>();
        for (var entry : condensedAdj.entrySet()) {
            int fromScc = entry.getKey();
            for (int toScc : entry.getValue()) {
                // Check if fromScc can reach toScc through another SCC neighbor
                for (int mid : entry.getValue()) {
                    if (mid == toScc) continue;
                    if (canReachScc(mid, toScc, condensedAdj, new HashSet<>())) {
                        redundantSccEdges.add(sccEdgeKey(fromScc, toScc));
                        break;
                    }
                }
            }
        }

        // Step 4: Remove original edges that correspond to redundant condensed edges
        Set<Dependency> redundant = new HashSet<>();
        for (Dependency edge : graph.getEdges()) {
            int fromScc = nodeToScc.getOrDefault(edge.getFromPackage(), -1);
            int toScc = nodeToScc.getOrDefault(edge.getToPackage(), -1);
            // Only remove cross-component edges; never remove intra-SCC edges
            if (fromScc != toScc && redundantSccEdges.contains(sccEdgeKey(fromScc, toScc))) {
                redundant.add(edge);
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

    private long sccEdgeKey(int from, int to) {
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    /**
     * Computes strongly connected components using Kosaraju's algorithm.
     * Returns a map from node name to SCC index.
     */
    private Map<String, Integer> computeSccs(Set<String> allNodes, Map<String, Set<String>> adjacency) {
        // Pass 1: compute finish order via iterative DFS
        List<String> finishOrder = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String node : allNodes) {
            if (!visited.contains(node)) {
                dfsIterative(node, adjacency, visited, finishOrder);
            }
        }

        // Build reverse adjacency
        Map<String, Set<String>> reverseAdj = new HashMap<>();
        for (var entry : adjacency.entrySet()) {
            for (String to : entry.getValue()) {
                reverseAdj.computeIfAbsent(to, k -> new HashSet<>()).add(entry.getKey());
            }
        }

        // Pass 2: process in reverse finish order on the reversed graph
        Map<String, Integer> nodeToScc = new HashMap<>();
        visited.clear();
        int sccId = 0;
        for (int i = finishOrder.size() - 1; i >= 0; i--) {
            String node = finishOrder.get(i);
            if (!visited.contains(node)) {
                List<String> component = new ArrayList<>();
                dfsIterative(node, reverseAdj, visited, component);
                for (String member : component) {
                    nodeToScc.put(member, sccId);
                }
                sccId++;
            }
        }
        return nodeToScc;
    }

    /**
     * Iterative DFS that appends nodes to the output list in finish order.
     */
    private void dfsIterative(String start, Map<String, Set<String>> adj,
                              Set<String> visited, List<String> output) {
        Deque<String[]> stack = new ArrayDeque<>();
        // Each frame: [node, "enter"] or [node, "exit"]
        stack.push(new String[]{start, "enter"});
        while (!stack.isEmpty()) {
            String[] frame = stack.pop();
            String node = frame[0];
            if (frame[1].equals("exit")) {
                output.add(node);
                continue;
            }
            if (!visited.add(node)) continue;
            stack.push(new String[]{node, "exit"});
            for (String neighbor : adj.getOrDefault(node, Set.of())) {
                if (!visited.contains(neighbor)) {
                    stack.push(new String[]{neighbor, "enter"});
                }
            }
        }
    }

    private boolean canReachScc(int from, int target,
                                Map<Integer, Set<Integer>> adj, Set<Integer> visited) {
        if (!visited.add(from)) return false;
        for (int neighbor : adj.getOrDefault(from, Set.of())) {
            if (neighbor == target) return true;
            if (canReachScc(neighbor, target, adj, visited)) return true;
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
