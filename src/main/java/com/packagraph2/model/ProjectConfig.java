package com.packagraph2.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectConfig {

    private String name;
    private String rootDirectory;
    private List<String> allSourceDirectories = new ArrayList<>();
    private List<String> sourceDirectories = new ArrayList<>();
    private List<Category> categories = new ArrayList<>();
    private List<GroupingRule> groupingRules = new ArrayList<>();
    private List<HideRule> hideRules = new ArrayList<>();
    private GraphDirection graphDirection = GraphDirection.TOP_TO_BOTTOM;
    private boolean highlightCircularDependencies = true;
    private boolean trimCommonPrefix = false;
    private boolean transitiveReduction = false;
    private boolean includeExternalDependencies = true;
    private Map<String, String> comments = new HashMap<>();
    private String gitRepoUrl;
    private String gitBranch;
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

    public List<String> getAllSourceDirectories() {
        return allSourceDirectories;
    }

    public void setAllSourceDirectories(List<String> allSourceDirectories) {
        this.allSourceDirectories = allSourceDirectories;
    }

    public List<String> getSourceDirectories() {
        return sourceDirectories;
    }

    public void setSourceDirectories(List<String> sourceDirectories) {
        this.sourceDirectories = sourceDirectories;
    }

    public List<Category> getCategories() {
        return categories;
    }

    public void setCategories(List<Category> categories) {
        this.categories = categories;
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

    public boolean isTransitiveReduction() {
        return transitiveReduction;
    }

    public void setTransitiveReduction(boolean transitiveReduction) {
        this.transitiveReduction = transitiveReduction;
    }

    public boolean isIncludeExternalDependencies() {
        return includeExternalDependencies;
    }

    public void setIncludeExternalDependencies(boolean includeExternalDependencies) {
        this.includeExternalDependencies = includeExternalDependencies;
    }

    public Map<String, String> getComments() {
        return comments;
    }

    public void setComments(Map<String, String> comments) {
        this.comments = comments;
    }

    public String getGitRepoUrl() {
        return gitRepoUrl;
    }

    public void setGitRepoUrl(String gitRepoUrl) {
        this.gitRepoUrl = gitRepoUrl;
    }

    public String getGitBranch() {
        return gitBranch;
    }

    public void setGitBranch(String gitBranch) {
        this.gitBranch = gitBranch;
    }

    public DependencyGraph getGraph() {
        return graph;
    }

    public void setGraph(DependencyGraph graph) {
        this.graph = graph;
    }
}
