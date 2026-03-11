package com.packagraph2.rules;

import com.packagraph2.model.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Applies grouping and hiding rules to a dependency graph,
 * producing a new filtered/transformed graph.
 */
public class RuleEngine {

    /**
     * Applies all enabled rules to the given graph and returns a new transformed graph.
     */
    public DependencyGraph applyRules(DependencyGraph original,
                                       List<GroupingRule> groupingRules,
                                       List<HideRule> hideRules) {
        DependencyGraph result = copyGraph(original);

        // Apply hide rules first
        for (HideRule rule : hideRules) {
            if (rule.isEnabled()) {
                applyHideRule(result, rule);
            }
        }

        // Apply grouping rules
        for (GroupingRule rule : groupingRules) {
            if (rule.isEnabled()) {
                applyGroupingRule(result, rule);
            }
        }

        // Remove self-referencing edges (can appear after grouping)
        result.getEdges().removeIf(e -> e.getFromPackage().equals(e.getToPackage()));

        return result;
    }

    private void applyHideRule(DependencyGraph graph, HideRule rule) {
        Pattern pattern = globToRegex(rule.getPattern());
        Set<String> hiddenPackages = new HashSet<>();

        graph.getNodes().removeIf(node -> {
            if (pattern.matcher(node.getName()).matches()) {
                hiddenPackages.add(node.getName());
                return true;
            }
            return false;
        });

        // Remove edges connected to hidden packages
        graph.getEdges().removeIf(edge ->
                hiddenPackages.contains(edge.getFromPackage()) ||
                hiddenPackages.contains(edge.getToPackage()));
    }

    private void applyGroupingRule(DependencyGraph graph, GroupingRule rule) {
        Pattern pattern = globToRegex(rule.getPattern());
        Set<String> matchedPackages = new HashSet<>();

        for (PackageNode node : graph.getNodes()) {
            if (pattern.matcher(node.getName()).matches()) {
                matchedPackages.add(node.getName());
            }
        }

        if (matchedPackages.isEmpty()) {
            return;
        }

        // Remove matched nodes and add a group node
        boolean allExternal = graph.getNodes().stream()
                .filter(n -> matchedPackages.contains(n.getName()))
                .allMatch(PackageNode::isExternal);

        graph.getNodes().removeIf(n -> matchedPackages.contains(n.getName()));
        graph.addNode(new PackageNode(rule.getDisplayName(), allExternal, rule.getCategoryId()));

        // Rewrite edges: replace matched package names with the group display name
        Set<Dependency> newEdges = new LinkedHashSet<>();
        for (Dependency edge : graph.getEdges()) {
            String from = matchedPackages.contains(edge.getFromPackage())
                    ? rule.getDisplayName() : edge.getFromPackage();
            String to = matchedPackages.contains(edge.getToPackage())
                    ? rule.getDisplayName() : edge.getToPackage();
            newEdges.add(new Dependency(from, to));
        }
        graph.setEdges(newEdges);
    }

    /**
     * Converts a glob pattern like "org.springframework.*" to a regex.
     * Supports * (any sequence within a segment) and ** (any sequence including dots).
     */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // ** matches anything including dots
                    regex.append(".*");
                    i += 2;
                } else {
                    // * matches anything except dots (single segment)
                    regex.append("[^.]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^.]");
                i++;
            } else if (c == '.') {
                regex.append("\\.");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile("^" + regex + "$");
    }

    private DependencyGraph copyGraph(DependencyGraph original) {
        DependencyGraph copy = new DependencyGraph();
        for (PackageNode node : original.getNodes()) {
            copy.addNode(new PackageNode(node.getName(), node.isExternal()));
        }
        for (Dependency edge : original.getEdges()) {
            copy.addEdge(new Dependency(edge.getFromPackage(), edge.getToPackage()));
        }
        return copy;
    }

    /**
     * Detects circular dependencies in the graph.
     * Returns a set of edges that are part of cycles.
     */
    public Set<Dependency> detectCircularDependencies(DependencyGraph graph) {
        Set<Dependency> cyclicEdges = new LinkedHashSet<>();
        Map<String, Set<String>> adjacency = new HashMap<>();

        for (Dependency edge : graph.getEdges()) {
            adjacency.computeIfAbsent(edge.getFromPackage(), k -> new HashSet<>())
                    .add(edge.getToPackage());
        }

        // For each node, do a DFS to see if it can reach itself
        for (PackageNode node : graph.getNodes()) {
            Set<String> visited = new HashSet<>();
            Set<String> reachable = new HashSet<>();
            findReachable(node.getName(), adjacency, visited, reachable);

            // If node can reach itself, it's in a cycle
            if (reachable.contains(node.getName())) {
                // Find which edges from this node are part of the cycle
                Set<String> targets = adjacency.getOrDefault(node.getName(), Set.of());
                for (String target : targets) {
                    // Check if target can reach back to this node
                    Set<String> targetVisited = new HashSet<>();
                    Set<String> targetReachable = new HashSet<>();
                    findReachable(target, adjacency, targetVisited, targetReachable);
                    if (targetReachable.contains(node.getName())) {
                        cyclicEdges.add(new Dependency(node.getName(), target));
                    }
                }
            }
        }

        return cyclicEdges;
    }

    private void findReachable(String node, Map<String, Set<String>> adjacency,
                               Set<String> visited, Set<String> reachable) {
        for (String neighbor : adjacency.getOrDefault(node, Set.of())) {
            if (visited.add(neighbor)) {
                reachable.add(neighbor);
                findReachable(neighbor, adjacency, visited, reachable);
            }
        }
    }
}
