# packagraph2

## Overview

packagraph2 is a Java package dependency browser/visualizer for Java codebases. It parses Java source code and renders interactive package dependency graphs. It can be used both as an interactive web UI and as a CLI tool for CI pipelines.

## Architecture

### Core Library (Java)
- **Analysis engine**: Uses **JavaParser** to parse Java source files (no compilation/build required)
- **Rule engine**: Applies grouping and hiding rules to the dependency graph
- **Graph model**: Represents packages as nodes, dependencies as directed edges
- **Project file manager**: Reads/writes `.pg2` project files
- **Graphviz DOT generator**: Converts the graph model to Graphviz DOT format

### Two Modes

**UI mode** (`packagraph2 serve --project fancyapp.pg2`):
- Local web server using **Javalin** (lightweight embedded server)
- Frontend: plain vanilla JS + **viz.js** (Graphviz compiled to WebAssembly)
- Renders an SVG graph with a side panel for rules/configuration
- Graph re-renders in the browser when rules change (via viz.js WASM)
- Explicit save button to persist changes to the project file

**CLI mode** (`packagraph2 export --project fancyapp.pg2 --format svg -o graph.svg`):
- Reads the project file, applies rules, generates DOT, renders via Graphviz
- Outputs PNG or SVG
- Designed for CI pipeline usage
- Produces identical output to the web UI (same Graphviz engine)

### UI Layout
```
+------------------------------+------------------+
|                              |  Rules Panel     |
|   Graphviz-rendered SVG      |  - Add group     |
|   (zoom/pan supported)       |  - Add hide rule |
|                              |  - Toggle rules  |
|                              |  - Module select |
|                              |  - Re-analyze    |
|                              |  - Save button   |
+------------------------------+------------------+
```

## Key Concepts

### Graph Nodes
- A node is either a **Java package** or a **group** (a user-defined aggregation of packages)
- Each Java package is a separate node (e.g., `com.app.service.user` and `com.app.service.order` are separate nodes)
- Java packages do NOT have hierarchical parent/child relationships in the graph — if the user wants to treat them as related, they use grouping rules

### Dependencies
- An arrow from A to B means "A depends on B"
- Dependencies include both internal project packages and external library packages (e.g., `java.awt`, `org.springframework.*`)
- Dependencies are plain arrows (no weight/count decoration)

### Rules
- **Grouping rules**: Collapse multiple packages into a single node (e.g., `org.springframework.*` -> "Spring")
- **Hiding rules**: Hide packages matching a pattern (e.g., hide all `java.*`)
- Rules are toggleable (enable/disable without deleting)
- Rules are saved in the project file

### Configurable Options
- **Graph direction**: Top-to-bottom, left-to-right, etc. (configurable)
- **Highlight circular dependencies**: Configurable on/off
- **Common prefix trimming**: Configurable — trim common prefix for readability (e.g., show `service.user` instead of `com.fancyapp.service.user`)

## Project File

- Extension: `.pg2`
- Format: JSON
- Stored next to the analyzed codebase (e.g., `fancyapp/.pg2` or `fancyapp/myproject.pg2`)
- Contains:
  - Root directory path of the analyzed codebase
  - Selected source directories (directories containing .java files)
  - Dependency graph (cached analysis result)
  - Grouping rules
  - Hiding rules
  - Graph direction preference
  - Circular dependency highlighting preference
  - Common prefix trimming preference

## Tech Stack

- **Language**: Java 25
- **Build tool**: Maven
- **Source analysis**: JavaParser
- **Web server (UI mode)**: Javalin
- **Frontend**: Plain vanilla JS + viz.js (Graphviz WASM)
- **Graph rendering**: Graphviz (DOT format) — same engine for both UI and CLI
- **CLI image export**: Graphviz (installed on system for CLI mode)

## Multi-Module Support

- Supports multi-module Java projects
- Module discovery: Scans for directories containing `.java` files (does NOT parse Maven/Gradle build files)
- User can select which source directories to include in the analysis

## Future Features (not for initial implementation)

- Multiple views per project (different rule sets for the same codebase)
