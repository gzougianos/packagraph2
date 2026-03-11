package com.packagraph2.model;

import java.util.*;

public class DependencyGraph {

    private Set<PackageNode> nodes = new LinkedHashSet<>();
    private Set<Dependency> edges = new LinkedHashSet<>();
    private Map<String, List<ClassInfo>> packageClasses = new LinkedHashMap<>();

    public DependencyGraph() {
    }

    public void addNode(PackageNode node) {
        nodes.add(node);
    }

    public void addEdge(Dependency edge) {
        edges.add(edge);
    }

    public void addEdge(String from, String to) {
        edges.add(new Dependency(from, to));
    }

    public PackageNode findNode(String name) {
        return nodes.stream()
                .filter(n -> n.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public Set<PackageNode> getNodes() {
        return nodes;
    }

    public void setNodes(Set<PackageNode> nodes) {
        this.nodes = nodes;
    }

    public Set<Dependency> getEdges() {
        return edges;
    }

    public void setEdges(Set<Dependency> edges) {
        this.edges = edges;
    }

    public Map<String, List<ClassInfo>> getPackageClasses() {
        return packageClasses;
    }

    public void setPackageClasses(Map<String, List<ClassInfo>> packageClasses) {
        this.packageClasses = packageClasses;
    }

    public void addClass(String packageName, ClassInfo classInfo) {
        packageClasses.computeIfAbsent(packageName, k -> new ArrayList<>()).add(classInfo);
    }

    public Set<String> getInternalPackageNames() {
        var result = new LinkedHashSet<String>();
        for (var node : nodes) {
            if (!node.isExternal()) {
                result.add(node.getName());
            }
        }
        return result;
    }
}
