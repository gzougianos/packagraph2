package com.packagraph2.model;

import java.util.ArrayList;
import java.util.List;

public class ProjectConfig {

    private String name;
    private String rootDirectory;
    private List<String> sourceDirectories = new ArrayList<>();
    private List<GroupingRule> groupingRules = new ArrayList<>();
    private List<HideRule> hideRules = new ArrayList<>();
    private GraphDirection graphDirection = GraphDirection.TOP_TO_BOTTOM;
    private boolean highlightCircularDependencies = true;
    private boolean trimCommonPrefix = false;
    private DependencyGraph graph;

    public ProjectConfig() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public List<String> getSourceDirectories() {
        return sourceDirectories;
    }

    public void setSourceDirectories(List<String> sourceDirectories) {
        this.sourceDirectories = sourceDirectories;
    }

    public List<GroupingRule> getGroupingRules() {
        return groupingRules;
    }

    public void setGroupingRules(List<GroupingRule> groupingRules) {
        this.groupingRules = groupingRules;
    }

    public List<HideRule> getHideRules() {
        return hideRules;
    }

    public void setHideRules(List<HideRule> hideRules) {
        this.hideRules = hideRules;
    }

    public GraphDirection getGraphDirection() {
        return graphDirection;
    }

    public void setGraphDirection(GraphDirection graphDirection) {
        this.graphDirection = graphDirection;
    }

    public boolean isHighlightCircularDependencies() {
        return highlightCircularDependencies;
    }

    public void setHighlightCircularDependencies(boolean highlightCircularDependencies) {
        this.highlightCircularDependencies = highlightCircularDependencies;
    }

    public boolean isTrimCommonPrefix() {
        return trimCommonPrefix;
    }

    public void setTrimCommonPrefix(boolean trimCommonPrefix) {
        this.trimCommonPrefix = trimCommonPrefix;
    }

    public DependencyGraph getGraph() {
        return graph;
    }

    public void setGraph(DependencyGraph graph) {
        this.graph = graph;
    }
}
