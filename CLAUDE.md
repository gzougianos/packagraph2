# packagraph2

## Overview

packagraph2 is a Java package dependency browser/visualizer for Java codebases. It parses Java source code and renders interactive package dependency graphs. It can be used both as an interactive web UI and as a CLI tool for CI pipelines.

## Architecture

### Core Library (Java)
- **Analysis engine**: Uses **JavaParser** to parse Java source files (no compilation/build required)
- **Rule engine**: Applies grouping and hiding rules to the dependency graph; carries edge details through all transformations (copy/hide/group)
- **Graph model**: Represents packages as nodes, dependencies as directed edges, with per-edge import details
- **Project file manager**: Reads/writes `.pg2` project files
- **Graphviz DOT generator**: Converts the graph model to Graphviz DOT format; returns both DOT string and processed edge details via `DotResult` record

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
| Header:                      |                  |
| [Title] [path] [git-info]   | [Undo/Redo/Zoom] | [Re-analyze] [Export] [Save]
+------------------------------+------------------+
|                              | Project Location |
|                              | [path] [Open dir]|
|   Graphviz-rendered SVG      |------------------|
|   (zoom/pan/select)          | Graph Options    |
|                              | Categories       |
|                              | Grouping Rules   |
|                              | Hide Rules       |
|   [Legend overlay]           | Selection        |
|                              | Packages         |
|                              | Modules / Sources|
+------------------------------+------------------+
```

### Processing Pipeline (DOT generation)
The order of operations when generating a graph:
1. Apply rules (grouping + hiding) via RuleEngine
2. Remove external dependencies (if `includeExternalDependencies` is off)
3. Apply transitive reduction (if enabled)
4. Detect circular dependencies (if highlighting enabled)
5. Generate DOT string with node/edge styling

## Key Concepts

### Graph Nodes
- A node is either a **Java package** or a **group** (a user-defined aggregation of packages)
- Each Java package is a separate node (e.g., `com.app.service.user` and `com.app.service.order` are separate nodes)
- Java packages do NOT have hierarchical parent/child relationships in the graph — if the user wants to treat them as related, they use grouping rules
- Nodes can be **internal** (found in analyzed source) or **external** (referenced via imports but not in source)

### Dependencies
- An arrow from A to B means "A depends on B"
- Dependencies include both internal project packages and external library packages (e.g., `java.awt`, `org.springframework.*`)
- Dependencies are plain arrows (no weight/count decoration)
- Each edge tracks **import details**: which source class imports which target class (fully qualified names)
- **Static imports** are handled correctly: `import static pkg.Class.member` resolves to package `pkg`, not `pkg.Class`

### Rules
- **Grouping rules**: Collapse multiple packages into a single node (e.g., `org.springframework.*` -> "Spring")
- **Hiding rules**: Hide packages matching a pattern (e.g., hide all `java.*`)
- Rules are toggleable (enable/disable without deleting)
- Rules are saved in the project file
- Edge details are properly carried through rule transformations (merged on grouping, removed on hiding)

### Configurable Options (Graph Options panel)
- **Graph direction**: Top-to-bottom, bottom-to-top, left-to-right, right-to-left
- **Highlight circular dependencies**: On/off — cyclic edges shown in red
- **Common prefix trimming**: Trim common prefix for readability (e.g., show `service.user` instead of `com.fancyapp.service.user`)
- **Transitive reduction**: Hide edges implied by transitive dependencies (A→B→C means A→C is redundant)
- **Include external dependencies**: Toggle to show/hide all external packages as a shortcut

### Categories
- User-defined color categories (e.g., "JPA", "Spring") with a color picker
- Assigned to grouping rules — grouped nodes inherit the category color
- Shown in the legend overlay when active

### Package Comments
- Right-click any node → "Add comment..." / "Edit comment..."
- Comments are stored in the project config as a `Map<String, String>` (package name → text)
- Commented nodes show a **pencil indicator (✎)** appended to the label and a **thicker border** (`penwidth=2.5`)
- Comments appear in the **tooltip** on hover (gold italic text)
- Comments work on both original packages and grouped nodes
- Fully integrated with undo/redo

## Interactions

### Graph Interactions
- **Click node**: Toggle highlight (dims unconnected nodes/edges)
- **Ctrl+Click node**: Add/remove from selection
- **Right-click node**: Context menu (hide, group, highlight, select deps/dependents, show classes, comment, copy package name)
- **Click edge**: Show edge details dialog (list of source → imported class pairs)
- **Shift+drag**: Rectangle selection of multiple nodes
- **Mouse wheel**: Zoom in/out toward cursor
- **Drag**: Pan the graph
- **Escape**: Clear highlights and selection

### Context Menu Actions
- Hide this package / Hide all with same prefix
- Group by prefix...
- Highlight dependencies / Highlight dependents
- Select dependencies / Select dependents / Add to selection
- Show all classes (internal packages only — shows class name, kind, scope)
- Add comment... / Edit comment...
- Copy package name (copies to clipboard)

### Keyboard Shortcuts
- **Ctrl+Z**: Undo
- **Ctrl+Y / Ctrl+Shift+Z**: Redo
- **Ctrl+S**: Save project
- **Escape**: Clear highlight / selection

## Project File

- Extension: `.pg2`
- Format: JSON
- Stored next to the analyzed codebase (e.g., `fancyapp/myproject.pg2`)
- Contains:
  - Project name
  - Root directory path of the analyzed codebase
  - All source directories (discovered) and selected source directories (active)
  - Dependency graph (cached analysis result, including `packageClasses` and `edgeDetails`)
  - Grouping rules (with optional category assignment)
  - Hiding rules
  - Categories (id, name, color)
  - Comments (package name → text)
  - Graph options (direction, circular highlighting, prefix trimming, transitive reduction, include external)
  - Git info (repo URL, branch) — if cloned from remote

## Features

### Clone from Git
- Clone a remote repository (GitHub, Bitbucket, etc.) directly from the UI
- Supports shallow clone (`--depth 1`) with optional branch selection
- User chooses temp directory or custom directory for clone target
- After cloning, scans for source directories and lets user select which to analyze
- Git repo URL and branch displayed in the header after the project name

### Export
- **SVG**: Vector export with optional background (transparent/white/dark)
- **PNG / PNG @2x**: Raster export via SVG→Canvas pipeline, configurable scale and background
- **DOT**: Raw Graphviz DOT source file
- **JSON**: Graph data as formatted JSON

### Edge Details
- Click any edge to see which imports cause that dependency
- Shows fully qualified class names: `source.Class → imported.Class`
- Details are carried through rule transformations (grouping merges them, hiding removes them)

### Class Inspector
- Right-click an internal package → "Show all classes"
- Shows each class with its **kind** (class/interface/enum/record/annotation) and **scope** (public/protected/private/package-private)
- Backward-compatible with old `.pg2` files that have string-only class data

### Open Directory
- Sidebar shows the full `.pg2` file path at the top
- "Open dir" button opens the project directory in the system file manager (Explorer/Finder/xdg-open)

### Legend
- Shows in the bottom-left overlay when 2+ distinct node types are visible
- Dynamically updates based on: internal nodes, external nodes (if included), category-colored nodes
- Only shows entries for types actually present in the graph

## UI Color Scheme (Dark Theme)

### Backgrounds
- **Page background**: `#1a1a2e`
- **Header / Sidebar**: `#16213e`
- **Dialogs / Cards**: `#2a2a3e`
- **Input fields**: `#1a1a2e` with `#444` border

### Graph Node Colors (Graphviz DOT)
- **Internal package**: fill `#d4e6f1`, border `#2980b9`, font `#1a3a5c`
- **External package**: fill `#3a3a3a`, border `#666666`, font `#aaaaaa`, dashed border style
- **Category-colored**: fill = category color, border = darkened by 30%, font `#2c3e50`
- **Cyclic edges**: `#e74c3c` (red), `penwidth=2.0`, bold
- **Normal edges**: `#7f8c8d` (gray)
- **Commented nodes**: `penwidth=2.5`, pencil ✎ appended to label

### UI Accents
- **Primary blue**: `#2980b9` (buttons, focus borders, selection)
- **Hover blue**: `#3498db`
- **Save dirty indicator**: `#e67e22` (orange, pulsing)
- **Danger/delete**: `#e74c3c`
- **Success toast**: `#27ae60` left border
- **Error toast**: `#e74c3c` left border
- **Info toast**: `#2980b9` left border
- **Selection highlight**: `#f39c12` (orange border on selected nodes)

### Class Inspector Badge Colors
- class: `#5dade2`, interface: `#a8e6cf`, enum: `#f0b27a`, record: `#d7bde2`, annotation: `#f9e79f`
- public: `#82e0aa`, protected: `#f9e79f`, private: `#f1948a`, package-private: `#aab7b8`

### Tooltip / Comment
- Comment text in tooltip: `#f0d78c` (gold italic)

## Tech Stack

- **Language**: Java 25
- **Build tool**: Maven
- **Source analysis**: JavaParser
- **Web server (UI mode)**: Javalin
- **Logging**: SLF4J + Logback (`com.packagraph2` at DEBUG, Javalin at INFO, Jetty at WARN)
- **Frontend**: Plain vanilla JS + viz.js (Graphviz WASM)
- **Graph rendering**: Graphviz (DOT format) — same engine for both UI and CLI
- **CLI image export**: Graphviz (installed on system for CLI mode)

## Multi-Module Support

- Supports multi-module Java projects
- Module discovery: Scans for directories containing `.java` files (does NOT parse Maven/Gradle build files)
- User can select which source directories to include in the analysis
- Module selector appears in sidebar when 2+ source directories exist
- Test directories are unchecked by default during project creation

## API Endpoints

- `POST /api/scan-sources` — Scan a root directory for Java source roots
- `POST /api/analyze` — Analyze selected source directories, returns DependencyGraph
- `POST /api/dot` — Generate DOT + edge details from a ProjectConfig
- `POST /api/project/save` — Save project config to a `.pg2` file
- `POST /api/project/open` — Load a `.pg2` file
- `GET /api/project/initial` — Load the project specified via CLI `--project` flag
- `GET /api/project/recent` — List recently opened projects
- `POST /api/browse` — Browse directories (for file open dialog)
- `POST /api/git/clone` — Clone a git repository (shallow, optional branch)
- `POST /api/open-directory` — Open a directory in the system file manager

## Undo/Redo System

Snapshots capture: categories, groupingRules, hideRules, graphDirection, highlightCircularDependencies, trimCommonPrefix, transitiveReduction, includeExternalDependencies, comments. The graph data itself is NOT part of undo/redo (it only changes on re-analyze).

## Future Features (not for initial implementation)

- Multiple views per project (different rule sets for the same codebase)
