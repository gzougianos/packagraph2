// =============================================================================
// State
// =============================================================================
let state = {
    filePath: null,
    config: {
        name: '',
        rootDirectory: '',
        sourceDirectories: [],
        categories: [],
        groupingRules: [],
        hideRules: [],
        graphDirection: 'TOP_TO_BOTTOM',
        highlightCircularDependencies: true,
        trimCommonPrefix: false,
        graph: null
    }
};

let vizInstance = null;
let renderTimeout = null;
let selectedNodes = new Set();
let highlightedNode = null;
let contextMenuNode = null;

// Zoom/pan state
let zoom = { scale: 1, x: 0, y: 0 };
let isPanning = false;
let panStart = { x: 0, y: 0 };
let panOffset = { x: 0, y: 0 };

// Drag-to-select state
let isSelecting = false;
let selectStart = { x: 0, y: 0 };

// Undo/redo
let undoStack = [];
let redoStack = [];

// Dirty (unsaved changes) tracking
let dirty = false;

// Tooltip
let tooltipEl = null;

// =============================================================================
// Initialization
// =============================================================================
document.addEventListener('DOMContentLoaded', async () => {
    vizInstance = await Viz.instance();

    try {
        const resp = await fetch('/api/project/initial');
        const data = await resp.json();
        if (data.config) {
            state.config = data.config;
            state.filePath = data.filePath;
            showMainApp();
        }
    } catch (e) {
        // No initial project
    }

    loadRecentProjects();
    initSidebarResizer();
    initGraphInteraction();
    initKeyboardShortcuts();
    initBeforeUnload();
});

// =============================================================================
// Landing Page
// =============================================================================
function showCreateProject() {
    document.getElementById('create-dialog').style.display = 'flex';
    document.getElementById('create-name').focus();
}

function showOpenProject() {
    document.getElementById('open-dialog').style.display = 'flex';
    document.getElementById('open-btn').disabled = true;
    browseDirectory('');
}

function hideDialogs() {
    document.getElementById('create-dialog').style.display = 'none';
    document.getElementById('open-dialog').style.display = 'none';
    document.getElementById('group-dialog').style.display = 'none';
}

// =============================================================================
// Recent Projects
// =============================================================================
async function loadRecentProjects() {
    try {
        const resp = await fetch('/api/project/recent');
        const projects = await resp.json();

        const container = document.getElementById('recent-projects');
        const list = document.getElementById('recent-projects-list');

        if (!projects || projects.length === 0) {
            container.style.display = 'none';
            return;
        }

        container.style.display = 'block';
        list.innerHTML = '';

        projects.forEach(proj => {
            const div = document.createElement('div');
            div.className = 'recent-project-item';
            div.onclick = () => openProjectByPath(proj.filePath);

            const timeAgo = formatTimeAgo(proj.lastOpened);
            div.innerHTML =
                '<div style="flex:1;min-width:0">' +
                    '<div class="recent-project-name">' + escapeHtml(proj.name) + '</div>' +
                    '<div class="recent-project-path">' + escapeHtml(proj.filePath) + '</div>' +
                '</div>' +
                '<span class="recent-project-time">' + timeAgo + '</span>';
            list.appendChild(div);
        });
    } catch (e) {
        // Ignore
    }
}

function formatTimeAgo(timestamp) {
    const diff = Date.now() - timestamp;
    const minutes = Math.floor(diff / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return minutes + 'm ago';
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + 'h ago';
    const days = Math.floor(hours / 24);
    if (days < 30) return days + 'd ago';
    return new Date(timestamp).toLocaleDateString();
}

async function openProjectByPath(filePath) {
    showLoading('Opening project...');
    try {
        const resp = await fetch('/api/project/open', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ filePath: filePath })
        });
        const data = await resp.json();

        if (data.error) {
            toast('Error: ' + data.error, 'error');
            return;
        }

        state.config = data.config;
        state.filePath = data.filePath;
        hideDialogs();
        pushUndo();
        markClean();
        showMainApp();
    } catch (e) {
        toast('Error opening: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// =============================================================================
// File Browser
// =============================================================================
let selectedBrowserFile = null;

async function browseDirectory(dirPath) {
    try {
        const resp = await fetch('/api/browse', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ directory: dirPath })
        });
        const data = await resp.json();

        if (data.error) {
            toast('Error browsing: ' + data.error, 'error');
            return;
        }

        document.getElementById('open-path').value = data.current;
        selectedBrowserFile = null;
        document.getElementById('open-btn').disabled = true;

        const list = document.getElementById('file-browser-list');
        list.innerHTML = '';

        // Parent directory entry
        if (data.parent) {
            const parentDiv = document.createElement('div');
            parentDiv.className = 'file-entry file-entry-parent';
            parentDiv.innerHTML =
                '<span class="file-entry-icon dir">&uarr;</span>' +
                '<span class="file-entry-name">..</span>';
            parentDiv.ondblclick = () => browseDirectory(data.parent);
            parentDiv.onclick = () => {
                document.getElementById('open-path').value = data.parent;
                selectedBrowserFile = null;
                document.getElementById('open-btn').disabled = true;
                clearBrowserSelection();
            };
            list.appendChild(parentDiv);
        }

        data.entries.forEach(entry => {
            const div = document.createElement('div');
            div.className = 'file-entry';

            if (entry.type === 'directory') {
                div.innerHTML =
                    '<span class="file-entry-icon dir">&#128193;</span>' +
                    '<span class="file-entry-name">' + escapeHtml(entry.name) + '</span>';
                div.ondblclick = () => browseDirectory(entry.path);
                div.onclick = () => {
                    document.getElementById('open-path').value = entry.path;
                    selectedBrowserFile = null;
                    document.getElementById('open-btn').disabled = true;
                    clearBrowserSelection();
                };
            } else {
                // pg2 file
                div.innerHTML =
                    '<span class="file-entry-icon pg2">&#128202;</span>' +
                    '<span class="file-entry-name">' + escapeHtml(entry.name) + '</span>';
                div.onclick = () => {
                    clearBrowserSelection();
                    div.classList.add('selected');
                    selectedBrowserFile = entry.path;
                    document.getElementById('open-path').value = entry.path;
                    document.getElementById('open-btn').disabled = false;
                };
                div.ondblclick = () => {
                    selectedBrowserFile = entry.path;
                    document.getElementById('open-path').value = entry.path;
                    openProject();
                };
            }

            list.appendChild(div);
        });

        if (data.entries.length === 0 && !data.parent) {
            list.innerHTML = '<div style="padding:12px;color:#666;font-size:0.85rem">Empty directory</div>';
        }
    } catch (e) {
        toast('Error browsing: ' + e.message, 'error');
    }
}

function clearBrowserSelection() {
    document.querySelectorAll('#file-browser-list .file-entry.selected').forEach(el => {
        el.classList.remove('selected');
    });
}

function browseOrOpen() {
    const path = document.getElementById('open-path').value.trim();
    if (!path) return;

    if (path.endsWith('.pg2')) {
        selectedBrowserFile = path;
        document.getElementById('open-btn').disabled = false;
        openProject();
    } else {
        browseDirectory(path);
    }
}

// =============================================================================
// Create Project
// =============================================================================
async function scanSources() {
    const rootDir = document.getElementById('create-root').value.trim();
    if (!rootDir) return;

    showLoading('Scanning for source directories...');
    try {
        const resp = await fetch('/api/scan-sources', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rootDirectory: rootDir })
        });
        const data = await resp.json();

        if (data.error) {
            toast('Error: ' + data.error, 'error');
            return;
        }

        const list = document.getElementById('source-dirs-list');
        list.innerHTML = '';

        if (data.sourceDirectories.length === 0) {
            list.innerHTML = '<p style="color:#888;font-size:0.85rem">No Java source directories found.</p>';
        } else {
            const scanPrefix = findCommonPathPrefix(data.sourceDirectories);
            data.sourceDirectories.forEach(dir => {
                const isTest = dir.toLowerCase().includes('test');
                const shortName = scanPrefix ? dir.substring(scanPrefix.length) : dir;
                const label = document.createElement('label');
                label.innerHTML = '<input type="checkbox" value="' + escapeHtml(dir) + '"' +
                    (isTest ? '' : ' checked') + '> ' + escapeHtml(shortName);
                list.appendChild(label);
            });
        }

        document.getElementById('source-dirs-section').style.display = 'block';
        document.getElementById('create-btn').disabled = data.sourceDirectories.length === 0;
    } catch (e) {
        toast('Error scanning: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function createProject() {
    const name = document.getElementById('create-name').value.trim();
    const rootDir = document.getElementById('create-root').value.trim();
    if (!name || !rootDir) {
        toast('Please fill in all fields.', 'error');
        return;
    }

    const checkboxes = document.querySelectorAll('#source-dirs-list input[type=checkbox]:checked');
    const selectedDirs = Array.from(checkboxes).map(cb => cb.value);

    if (selectedDirs.length === 0) {
        toast('Select at least one source directory.', 'error');
        return;
    }

    showLoading('Analyzing source code...');
    try {
        const resp = await fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceDirectories: selectedDirs })
        });
        const graph = await resp.json();

        if (graph.error) {
            toast('Error: ' + graph.error, 'error');
            return;
        }

        // Gather all scanned dirs (checked + unchecked)
        const allCheckboxes = document.querySelectorAll('#source-dirs-list input[type=checkbox]');
        const allDirs = Array.from(allCheckboxes).map(cb => cb.value);

        state.config = {
            name: name,
            rootDirectory: rootDir,
            allSourceDirectories: allDirs,
            sourceDirectories: selectedDirs,
            categories: [],
            groupingRules: [],
            hideRules: [],
            graphDirection: 'TOP_TO_BOTTOM',
            highlightCircularDependencies: true,
            trimCommonPrefix: false,
            graph: graph
        };
        state.filePath = rootDir.replace(/\/$/, '') + '/' + name + '.pg2';

        hideDialogs();
        pushUndo();
        markClean();
        showMainApp();
    } catch (e) {
        toast('Error analyzing: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// =============================================================================
// Open Project
// =============================================================================
async function openProject() {
    const filePath = selectedBrowserFile || document.getElementById('open-path').value.trim();
    if (!filePath || !filePath.endsWith('.pg2')) {
        toast('Please select a .pg2 file.', 'error');
        return;
    }
    await openProjectByPath(filePath);
}

// =============================================================================
// Main App
// =============================================================================
function showMainApp() {
    document.getElementById('landing-page').style.display = 'none';
    document.getElementById('main-app').style.display = 'flex';
    document.getElementById('main-app').style.flexDirection = 'column';
    document.getElementById('project-title').textContent = state.config.name || 'packagraph2';

    document.getElementById('opt-direction').value = state.config.graphDirection;
    document.getElementById('opt-circular').checked = state.config.highlightCircularDependencies;
    document.getElementById('opt-trim-prefix').checked = state.config.trimCommonPrefix;

    renderCategoriesList();
    renderRulesList();
    renderPackageList();
    renderLegend();
    renderModuleSelector();
    renderGraph();
}

// =============================================================================
// Graph Rendering
// =============================================================================
async function renderGraph() {
    if (!state.config.graph) return;

    if (renderTimeout) clearTimeout(renderTimeout);
    renderTimeout = setTimeout(async () => {
        try {
            const resp = await fetch('/api/dot', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(state.config)
            });
            const data = await resp.json();

            if (data.error) {
                console.error('DOT error:', data.error);
                toast('Render error: ' + data.error, 'error');
                return;
            }

            const svg = vizInstance.renderSVGElement(data.dot);
            const container = document.getElementById('graph-container');
            container.innerHTML = '';
            container.appendChild(svg);

            // Remove fixed dimensions so SVG scales with container
            svg.removeAttribute('width');
            svg.removeAttribute('height');
            svg.style.width = '100%';
            svg.style.height = '100%';

            // Attach interaction to SVG nodes
            attachSvgInteraction(svg);

            // Re-apply zoom
            applyZoom();

            // Re-apply selection highlights
            applySelectionHighlights();

        } catch (e) {
            console.error('Render error:', e);
        }
    }, 150);
}

// =============================================================================
// SVG Interaction
// =============================================================================
function attachSvgInteraction(svg) {
    const nodes = svg.querySelectorAll('.node');

    nodes.forEach(node => {
        const titleEl = node.querySelector('title');
        if (!titleEl) return;
        const packageName = titleEl.textContent;

        // Click to highlight dependencies
        node.addEventListener('click', (e) => {
            e.stopPropagation();
            if (e.ctrlKey || e.metaKey) {
                toggleNodeSelection(packageName);
            } else {
                toggleHighlight(packageName);
            }
        });

        // Right-click for context menu
        node.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            e.stopPropagation();
            showContextMenu(e.clientX, e.clientY, packageName);
        });

        // Hover for tooltip
        node.addEventListener('mouseenter', (e) => showTooltip(e, packageName));
        node.addEventListener('mouseleave', hideTooltip);
        node.addEventListener('mousemove', moveTooltip);
    });
}

function getNodePackageName(nodeEl) {
    const titleEl = nodeEl.querySelector('title');
    return titleEl ? titleEl.textContent : null;
}

// =============================================================================
// Highlight Dependencies
// =============================================================================
function toggleHighlight(packageName) {
    const container = document.getElementById('graph-container');

    if (highlightedNode === packageName) {
        // Toggle off
        highlightedNode = null;
        container.classList.remove('highlight-mode');
        container.querySelectorAll('.node, .edge').forEach(el => el.classList.remove('dimmed'));
        return;
    }

    highlightedNode = packageName;
    const connected = getConnectedPackages(packageName);
    connected.add(packageName);

    container.classList.add('highlight-mode');

    // Dim non-connected nodes
    container.querySelectorAll('.node').forEach(node => {
        const name = getNodePackageName(node);
        node.classList.toggle('dimmed', !connected.has(name));
    });

    // Dim non-connected edges
    container.querySelectorAll('.edge').forEach(edge => {
        const titleEl = edge.querySelector('title');
        if (!titleEl) { edge.classList.add('dimmed'); return; }
        const parts = titleEl.textContent.split('->');
        if (parts.length !== 2) { edge.classList.add('dimmed'); return; }
        const from = parts[0].trim();
        const to = parts[1].trim();
        edge.classList.toggle('dimmed', !(from === packageName || to === packageName));
    });
}

function getConnectedPackages(packageName) {
    const connected = new Set();
    if (!state.config.graph) return connected;

    for (const edge of state.config.graph.edges) {
        if (edge.fromPackage === packageName) connected.add(edge.toPackage);
        if (edge.toPackage === packageName) connected.add(edge.fromPackage);
    }
    return connected;
}

function getDependencies(packageName) {
    const deps = new Set();
    if (!state.config.graph) return deps;
    for (const edge of state.config.graph.edges) {
        if (edge.fromPackage === packageName) deps.add(edge.toPackage);
    }
    return deps;
}

function getDependents(packageName) {
    const deps = new Set();
    if (!state.config.graph) return deps;
    for (const edge of state.config.graph.edges) {
        if (edge.toPackage === packageName) deps.add(edge.fromPackage);
    }
    return deps;
}

// =============================================================================
// Node Selection
// =============================================================================
function toggleNodeSelection(packageName) {
    if (selectedNodes.has(packageName)) {
        selectedNodes.delete(packageName);
    } else {
        selectedNodes.add(packageName);
    }
    applySelectionHighlights();
    updateSelectionUI();
}

function clearSelection() {
    selectedNodes.clear();
    applySelectionHighlights();
    updateSelectionUI();
}

function applySelectionHighlights() {
    const container = document.getElementById('graph-container');
    container.querySelectorAll('.node').forEach(node => {
        const name = getNodePackageName(node);
        node.classList.toggle('selected', selectedNodes.has(name));
    });
}

function updateSelectionUI() {
    const section = document.getElementById('selection-section');
    const countEl = document.getElementById('selection-count');
    const listEl = document.getElementById('selection-list');

    if (selectedNodes.size === 0) {
        section.style.display = 'none';
        return;
    }

    section.style.display = 'block';
    countEl.textContent = selectedNodes.size;

    listEl.innerHTML = '';
    for (const name of [...selectedNodes].sort()) {
        const div = document.createElement('div');
        div.className = 'package-item internal';
        div.textContent = name;
        div.onclick = () => toggleNodeSelection(name);
        listEl.appendChild(div);
    }
}

function hideSelected() {
    if (selectedNodes.size === 0) return;
    pushUndo();
    for (const name of selectedNodes) {
        state.config.hideRules.push({
            id: crypto.randomUUID(),
            pattern: name,
            enabled: true
        });
    }
    clearSelection();
    renderRulesList();
    renderGraph();
}

function groupSelected() {
    if (selectedNodes.size < 2) {
        toast('Select at least 2 packages to group.', 'info');
        return;
    }
    // Find common prefix
    const names = [...selectedNodes];
    let prefix = names[0];
    for (let i = 1; i < names.length; i++) {
        while (!names[i].startsWith(prefix)) {
            const lastDot = prefix.lastIndexOf('.');
            if (lastDot === -1) { prefix = ''; break; }
            prefix = prefix.substring(0, lastDot);
        }
    }

    const pattern = prefix ? prefix + '.**' : names.join('|');
    document.getElementById('group-dialog-pattern').value = pattern;
    document.getElementById('group-dialog-name').value = '';
    document.getElementById('group-dialog').style.display = 'flex';
    document.getElementById('group-dialog-name').focus();
}

// =============================================================================
// Drag-to-Select
// =============================================================================
function initDragSelect() {
    const panel = document.getElementById('graph-panel');
    const rect = document.getElementById('selection-rect');

    panel.addEventListener('mousedown', (e) => {
        if (e.button !== 0 || e.target.closest('.node') || e.ctrlKey || e.metaKey) return;

        // Check if shift is held for selection mode
        if (e.shiftKey) {
            isSelecting = true;
            selectStart = { x: e.clientX, y: e.clientY };
            rect.style.display = 'block';
            rect.style.left = e.clientX + 'px';
            rect.style.top = e.clientY + 'px';
            rect.style.width = '0';
            rect.style.height = '0';
            panel.classList.add('selecting');
            e.preventDefault();
            return;
        }

        // Otherwise pan
        isPanning = true;
        panStart = { x: e.clientX - panOffset.x, y: e.clientY - panOffset.y };
        panel.classList.add('panning');
        e.preventDefault();
    });

    document.addEventListener('mousemove', (e) => {
        if (isSelecting) {
            const x = Math.min(e.clientX, selectStart.x);
            const y = Math.min(e.clientY, selectStart.y);
            const w = Math.abs(e.clientX - selectStart.x);
            const h = Math.abs(e.clientY - selectStart.y);
            rect.style.left = x + 'px';
            rect.style.top = y + 'px';
            rect.style.width = w + 'px';
            rect.style.height = h + 'px';
        }
        if (isPanning) {
            panOffset.x = e.clientX - panStart.x;
            panOffset.y = e.clientY - panStart.y;
            applyZoom();
        }
    });

    document.addEventListener('mouseup', (e) => {
        if (isSelecting) {
            isSelecting = false;
            rect.style.display = 'none';
            panel.classList.remove('selecting');
            selectNodesInRect(selectStart, { x: e.clientX, y: e.clientY });
        }
        if (isPanning) {
            isPanning = false;
            panel.classList.remove('panning');
        }
    });
}

function selectNodesInRect(start, end) {
    const selRect = {
        left: Math.min(start.x, end.x),
        top: Math.min(start.y, end.y),
        right: Math.max(start.x, end.x),
        bottom: Math.max(start.y, end.y)
    };

    const container = document.getElementById('graph-container');
    container.querySelectorAll('.node').forEach(node => {
        const bbox = node.getBoundingClientRect();
        const cx = bbox.left + bbox.width / 2;
        const cy = bbox.top + bbox.height / 2;

        if (cx >= selRect.left && cx <= selRect.right && cy >= selRect.top && cy <= selRect.bottom) {
            const name = getNodePackageName(node);
            if (name) selectedNodes.add(name);
        }
    });

    applySelectionHighlights();
    updateSelectionUI();
}

// =============================================================================
// Zoom / Pan
// =============================================================================
function initZoomPan() {
    const panel = document.getElementById('graph-panel');

    panel.addEventListener('wheel', (e) => {
        e.preventDefault();
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        const newScale = Math.max(0.1, Math.min(5, zoom.scale * delta));

        // Zoom toward mouse position
        const rect = panel.getBoundingClientRect();
        const mx = e.clientX - rect.left;
        const my = e.clientY - rect.top;

        panOffset.x = mx - (mx - panOffset.x) * (newScale / zoom.scale);
        panOffset.y = my - (my - panOffset.y) * (newScale / zoom.scale);
        zoom.scale = newScale;

        applyZoom();
    }, { passive: false });
}

function applyZoom() {
    const viewport = document.getElementById('graph-viewport');
    viewport.style.transform = 'translate(' + panOffset.x + 'px, ' + panOffset.y + 'px) scale(' + zoom.scale + ')';
    document.getElementById('zoom-level').textContent = Math.round(zoom.scale * 100) + '%';
}

function zoomIn() {
    zoom.scale = Math.min(5, zoom.scale * 1.2);
    applyZoom();
}

function zoomOut() {
    zoom.scale = Math.max(0.1, zoom.scale * 0.8);
    applyZoom();
}

function zoomReset() {
    zoom.scale = 1;
    panOffset = { x: 0, y: 0 };
    applyZoom();
}

// =============================================================================
// Context Menu
// =============================================================================
function showContextMenu(x, y, packageName) {
    contextMenuNode = packageName;
    const menu = document.getElementById('context-menu');

    // Position
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';
    menu.style.display = 'block';

    // Adjust if it goes off screen
    const rect = menu.getBoundingClientRect();
    if (rect.right > window.innerWidth) {
        menu.style.left = (x - rect.width) + 'px';
    }
    if (rect.bottom > window.innerHeight) {
        menu.style.top = (y - rect.height) + 'px';
    }

    // Show/hide "add to selection"
    const addToSel = document.getElementById('ctx-add-to-selection');
    addToSel.textContent = selectedNodes.has(packageName)
        ? 'Remove from selection' : 'Add to selection';

    // Show/hide "Show all classes" (only for internal packages)
    const node = state.config.graph.nodes.find(n => n.name === packageName);
    const isInternal = node && !node.external;
    const hasClasses = isInternal && state.config.graph.packageClasses
        && state.config.graph.packageClasses[packageName]
        && state.config.graph.packageClasses[packageName].length > 0;
    document.getElementById('ctx-classes-sep').style.display = hasClasses ? '' : 'none';
    document.getElementById('ctx-show-classes').style.display = hasClasses ? '' : 'none';
}

function hideContextMenu() {
    document.getElementById('context-menu').style.display = 'none';
    contextMenuNode = null;
}

document.addEventListener('click', (e) => {
    if (!e.target.closest('.context-menu')) {
        hideContextMenu();
    }
});

document.addEventListener('contextmenu', (e) => {
    if (!e.target.closest('.node')) {
        hideContextMenu();
    }
});

function ctxHidePackage() {
    if (!contextMenuNode) return;
    pushUndo();
    state.config.hideRules.push({
        id: crypto.randomUUID(),
        pattern: contextMenuNode,
        enabled: true
    });
    renderRulesList();
    renderGraph();
    hideContextMenu();
    toast('Hidden: ' + contextMenuNode, 'info');
}

function ctxHideByPrefix() {
    if (!contextMenuNode) return;
    const lastDot = contextMenuNode.lastIndexOf('.');
    if (lastDot === -1) return ctxHidePackage();

    const prefix = contextMenuNode.substring(0, lastDot);
    pushUndo();
    state.config.hideRules.push({
        id: crypto.randomUUID(),
        pattern: prefix + '.**',
        enabled: true
    });
    renderRulesList();
    renderGraph();
    hideContextMenu();
    toast('Hidden: ' + prefix + '.**', 'info');
}

function ctxGroupByPrefix() {
    if (!contextMenuNode) return;
    const lastDot = contextMenuNode.lastIndexOf('.');
    const prefix = lastDot > 0 ? contextMenuNode.substring(0, lastDot) : contextMenuNode;

    document.getElementById('group-dialog-pattern').value = prefix + '.**';
    document.getElementById('group-dialog-name').value = '';
    document.getElementById('group-dialog').style.display = 'flex';
    document.getElementById('group-dialog-name').focus();
    hideContextMenu();
}

function ctxShowDependencies() {
    if (!contextMenuNode) return;
    toggleHighlight(contextMenuNode);
    hideContextMenu();
}

function ctxShowDependents() {
    if (!contextMenuNode) return;
    // Highlight only dependents
    const container = document.getElementById('graph-container');
    const dependents = getDependents(contextMenuNode);
    dependents.add(contextMenuNode);

    highlightedNode = contextMenuNode;
    container.classList.add('highlight-mode');

    container.querySelectorAll('.node').forEach(node => {
        const name = getNodePackageName(node);
        node.classList.toggle('dimmed', !dependents.has(name));
    });

    container.querySelectorAll('.edge').forEach(edge => {
        const titleEl = edge.querySelector('title');
        if (!titleEl) { edge.classList.add('dimmed'); return; }
        const parts = titleEl.textContent.split('->');
        if (parts.length !== 2) { edge.classList.add('dimmed'); return; }
        const to = parts[1].trim();
        edge.classList.toggle('dimmed', to !== contextMenuNode);
    });

    hideContextMenu();
}

function ctxSelectDependencies() {
    if (!contextMenuNode) return;
    const deps = getDependencies(contextMenuNode);
    deps.forEach(d => selectedNodes.add(d));
    applySelectionHighlights();
    updateSelectionUI();
    hideContextMenu();
}

function ctxSelectDependents() {
    if (!contextMenuNode) return;
    const deps = getDependents(contextMenuNode);
    deps.forEach(d => selectedNodes.add(d));
    applySelectionHighlights();
    updateSelectionUI();
    hideContextMenu();
}

function ctxAddToSelection() {
    if (!contextMenuNode) return;
    if (selectedNodes.has(contextMenuNode)) {
        selectedNodes.delete(contextMenuNode);
    } else {
        selectedNodes.add(contextMenuNode);
    }
    applySelectionHighlights();
    updateSelectionUI();
    hideContextMenu();
}

function ctxShowClasses() {
    if (!contextMenuNode) return;
    const classes = (state.config.graph.packageClasses || {})[contextMenuNode] || [];
    const title = document.getElementById('classes-dialog-title');
    title.textContent = contextMenuNode;
    const list = document.getElementById('classes-dialog-list');
    list.innerHTML = '';
    const sorted = [...classes].sort((a, b) => {
        const nameA = typeof a === 'string' ? a : a.name;
        const nameB = typeof b === 'string' ? b : b.name;
        return nameA.localeCompare(nameB);
    });
    for (const cls of sorted) {
        // Support both old string format and new ClassInfo format
        const name = typeof cls === 'string' ? cls : cls.name;
        const kind = typeof cls === 'string' ? null : cls.kind;
        const scope = typeof cls === 'string' ? null : cls.scope;

        const div = document.createElement('div');
        div.className = 'class-item';

        const nameSpan = document.createElement('span');
        nameSpan.className = 'class-name';
        nameSpan.textContent = name;
        div.appendChild(nameSpan);

        if (kind) {
            const kindSpan = document.createElement('span');
            kindSpan.className = 'class-kind class-kind-' + kind;
            kindSpan.textContent = kind;
            div.appendChild(kindSpan);
        }

        if (scope) {
            const scopeSpan = document.createElement('span');
            scopeSpan.className = 'class-scope class-scope-' + scope.replace('-', '');
            scopeSpan.textContent = scope;
            div.appendChild(scopeSpan);
        }

        list.appendChild(div);
    }
    document.getElementById('classes-dialog').style.display = 'flex';
    hideContextMenu();
}

// Group dialog callbacks
function cancelGroupDialog() {
    document.getElementById('group-dialog').style.display = 'none';
}

function confirmGroupDialog() {
    const pattern = document.getElementById('group-dialog-pattern').value.trim();
    const name = document.getElementById('group-dialog-name').value.trim();
    const categoryId = document.getElementById('group-dialog-category').value || null;
    if (!pattern || !name) {
        toast('Pattern and name are required.', 'error');
        return;
    }
    pushUndo();
    state.config.groupingRules.push({
        id: crypto.randomUUID(),
        pattern: pattern,
        displayName: name,
        enabled: true,
        categoryId: categoryId
    });
    clearSelection();
    renderCategoriesList();
    renderRulesList();
    renderLegend();
    renderGraph();
    document.getElementById('group-dialog').style.display = 'none';
    toast('Group created: ' + name, 'success');
}

// =============================================================================
// Tooltip
// =============================================================================
function showTooltip(e, packageName) {
    if (!tooltipEl) {
        tooltipEl = document.createElement('div');
        tooltipEl.className = 'node-tooltip';
        document.body.appendChild(tooltipEl);
    }

    const deps = getDependencies(packageName);
    const dependents = getDependents(packageName);
    const node = state.config.graph.nodes.find(n => n.name === packageName);
    const type = node && node.external ? 'External' : 'Internal';

    tooltipEl.innerHTML =
        '<div class="tooltip-title">' + escapeHtml(packageName) + '</div>' +
        '<div class="tooltip-row">' + type + ' package</div>' +
        '<div class="tooltip-row">Dependencies: ' + deps.size + '</div>' +
        '<div class="tooltip-row">Dependents: ' + dependents.size + '</div>';

    tooltipEl.style.display = 'block';
    positionTooltip(e);
}

function moveTooltip(e) {
    if (tooltipEl) positionTooltip(e);
}

function positionTooltip(e) {
    if (!tooltipEl) return;
    let x = e.clientX + 14;
    let y = e.clientY + 14;

    const rect = tooltipEl.getBoundingClientRect();
    if (x + 280 > window.innerWidth) x = e.clientX - 280;
    if (y + 100 > window.innerHeight) y = e.clientY - 100;

    tooltipEl.style.left = x + 'px';
    tooltipEl.style.top = y + 'px';
}

function hideTooltip() {
    if (tooltipEl) tooltipEl.style.display = 'none';
}

// =============================================================================
// Undo / Redo
// =============================================================================
function pushUndo() {
    const snapshot = JSON.stringify({
        categories: state.config.categories,
        groupingRules: state.config.groupingRules,
        hideRules: state.config.hideRules,
        graphDirection: state.config.graphDirection,
        highlightCircularDependencies: state.config.highlightCircularDependencies,
        trimCommonPrefix: state.config.trimCommonPrefix
    });
    undoStack.push(snapshot);
    redoStack = [];
    markDirty();
    updateUndoRedoButtons();
}

function markDirty() {
    dirty = true;
    updateDirtyIndicator();
}

function markClean() {
    dirty = false;
    updateDirtyIndicator();
}

function updateDirtyIndicator() {
    const title = document.getElementById('project-title');
    const saveBtn = document.getElementById('btn-save');
    const baseName = state.config.name || 'packagraph2';

    if (title) {
        title.textContent = dirty ? baseName + ' *' : baseName;
    }
    if (saveBtn) {
        saveBtn.classList.toggle('btn-save-dirty', dirty);
    }
    document.title = dirty ? 'packagraph2 *' : 'packagraph2';
}

function undo() {
    if (undoStack.length === 0) return;
    const current = JSON.stringify({
        categories: state.config.categories,
        groupingRules: state.config.groupingRules,
        hideRules: state.config.hideRules,
        graphDirection: state.config.graphDirection,
        highlightCircularDependencies: state.config.highlightCircularDependencies,
        trimCommonPrefix: state.config.trimCommonPrefix
    });
    redoStack.push(current);

    const snapshot = JSON.parse(undoStack.pop());
    state.config.categories = snapshot.categories;
    state.config.groupingRules = snapshot.groupingRules;
    state.config.hideRules = snapshot.hideRules;
    state.config.graphDirection = snapshot.graphDirection;
    state.config.highlightCircularDependencies = snapshot.highlightCircularDependencies;
    state.config.trimCommonPrefix = snapshot.trimCommonPrefix;

    syncUIFromConfig();
    renderRulesList();
    renderGraph();
    updateUndoRedoButtons();
}

function redo() {
    if (redoStack.length === 0) return;
    const current = JSON.stringify({
        categories: state.config.categories,
        groupingRules: state.config.groupingRules,
        hideRules: state.config.hideRules,
        graphDirection: state.config.graphDirection,
        highlightCircularDependencies: state.config.highlightCircularDependencies,
        trimCommonPrefix: state.config.trimCommonPrefix
    });
    undoStack.push(current);

    const snapshot = JSON.parse(redoStack.pop());
    state.config.categories = snapshot.categories;
    state.config.groupingRules = snapshot.groupingRules;
    state.config.hideRules = snapshot.hideRules;
    state.config.graphDirection = snapshot.graphDirection;
    state.config.highlightCircularDependencies = snapshot.highlightCircularDependencies;
    state.config.trimCommonPrefix = snapshot.trimCommonPrefix;

    syncUIFromConfig();
    renderRulesList();
    renderGraph();
    updateUndoRedoButtons();
}

function updateUndoRedoButtons() {
    document.getElementById('btn-undo').disabled = undoStack.length === 0;
    document.getElementById('btn-redo').disabled = redoStack.length === 0;
}

function syncUIFromConfig() {
    document.getElementById('opt-direction').value = state.config.graphDirection;
    document.getElementById('opt-circular').checked = state.config.highlightCircularDependencies;
    document.getElementById('opt-trim-prefix').checked = state.config.trimCommonPrefix;
    renderCategoriesList();
    renderLegend();
}

// =============================================================================
// Config Changes
// =============================================================================
function onConfigChanged() {
    pushUndo();
    state.config.graphDirection = document.getElementById('opt-direction').value;
    state.config.highlightCircularDependencies = document.getElementById('opt-circular').checked;
    state.config.trimCommonPrefix = document.getElementById('opt-trim-prefix').checked;
    renderGraph();
}

// =============================================================================
// Grouping Rules
// =============================================================================
function addGroupingRule() {
    const pattern = document.getElementById('group-pattern').value.trim();
    const displayName = document.getElementById('group-name').value.trim();
    const categoryId = document.getElementById('group-category').value || null;
    if (!pattern || !displayName) return;

    pushUndo();
    state.config.groupingRules.push({
        id: crypto.randomUUID(),
        pattern: pattern,
        displayName: displayName,
        enabled: true,
        categoryId: categoryId
    });

    document.getElementById('group-pattern').value = '';
    document.getElementById('group-name').value = '';

    renderRulesList();
    renderLegend();
    renderGraph();
}

function toggleGroupingRule(id) {
    pushUndo();
    const rule = state.config.groupingRules.find(r => r.id === id);
    if (rule) {
        rule.enabled = !rule.enabled;
        renderRulesList();
        renderLegend();
        renderGraph();
    }
}

function removeGroupingRule(id) {
    pushUndo();
    state.config.groupingRules = state.config.groupingRules.filter(r => r.id !== id);
    renderRulesList();
    renderLegend();
    renderGraph();
}

// =============================================================================
// Hide Rules
// =============================================================================
function addHideRule() {
    const pattern = document.getElementById('hide-pattern').value.trim();
    if (!pattern) return;

    pushUndo();
    state.config.hideRules.push({
        id: crypto.randomUUID(),
        pattern: pattern,
        enabled: true
    });

    document.getElementById('hide-pattern').value = '';

    renderRulesList();
    renderGraph();
}

function toggleHideRule(id) {
    pushUndo();
    const rule = state.config.hideRules.find(r => r.id === id);
    if (rule) {
        rule.enabled = !rule.enabled;
        renderRulesList();
        renderGraph();
    }
}

function removeHideRule(id) {
    pushUndo();
    state.config.hideRules = state.config.hideRules.filter(r => r.id !== id);
    renderRulesList();
    renderGraph();
}

// =============================================================================
// Rules List Rendering
// =============================================================================
function renderRulesList() {
    // Grouping rules
    const groupList = document.getElementById('grouping-rules-list');
    groupList.innerHTML = '';
    state.config.groupingRules.forEach(rule => {
        const cat = rule.categoryId ? state.config.categories.find(c => c.id === rule.categoryId) : null;
        const swatchHtml = cat
            ? '<span class="category-swatch" style="background:' + escapeHtml(cat.color) + '" title="' + escapeHtml(cat.name) + '"></span>'
            : '';

        const div = document.createElement('div');
        div.className = 'rule-item';
        div.innerHTML =
            '<input type="checkbox" ' + (rule.enabled ? 'checked' : '') +
            ' onchange="toggleGroupingRule(\'' + rule.id + '\')">' +
            swatchHtml +
            '<span class="rule-text ' + (rule.enabled ? '' : 'disabled') + '" title="' + escapeHtml(rule.pattern) + '">' +
            escapeHtml(rule.pattern) + '</span>' +
            '<span class="rule-arrow">&rarr;</span>' +
            '<span class="rule-display-name ' + (rule.enabled ? '' : 'disabled') + '">' +
            escapeHtml(rule.displayName) + '</span>' +
            '<button onclick="removeGroupingRule(\'' + rule.id + '\')" class="btn btn-danger btn-small">&times;</button>';
        groupList.appendChild(div);
    });

    // Hide rules
    const hideList = document.getElementById('hide-rules-list');
    hideList.innerHTML = '';
    state.config.hideRules.forEach(rule => {
        const div = document.createElement('div');
        div.className = 'rule-item';
        div.innerHTML =
            '<input type="checkbox" ' + (rule.enabled ? 'checked' : '') +
            ' onchange="toggleHideRule(\'' + rule.id + '\')">' +
            '<span class="rule-text ' + (rule.enabled ? '' : 'disabled') + '" title="' + escapeHtml(rule.pattern) + '">' +
            escapeHtml(rule.pattern) + '</span>' +
            '<button onclick="removeHideRule(\'' + rule.id + '\')" class="btn btn-danger btn-small">&times;</button>';
        hideList.appendChild(div);
    });
}

// =============================================================================
// Categories
// =============================================================================
function addCategory() {
    const name = document.getElementById('cat-name').value.trim();
    const color = document.getElementById('cat-color').value;
    if (!name) return;

    pushUndo();
    state.config.categories.push({
        id: crypto.randomUUID(),
        name: name,
        color: color
    });

    document.getElementById('cat-name').value = '';
    renderCategoriesList();
    renderLegend();
    updateCategoryDropdowns();
}

function removeCategory(id) {
    pushUndo();
    state.config.categories = state.config.categories.filter(c => c.id !== id);
    // Remove categoryId from any grouping rules that used this category
    state.config.groupingRules.forEach(r => {
        if (r.categoryId === id) r.categoryId = null;
    });
    renderCategoriesList();
    renderRulesList();
    renderLegend();
    updateCategoryDropdowns();
    renderGraph();
}

function renderCategoriesList() {
    const list = document.getElementById('categories-list');
    const countEl = document.getElementById('category-count');
    if (!list) return;

    list.innerHTML = '';
    countEl.textContent = state.config.categories.length;

    state.config.categories.forEach(cat => {
        const div = document.createElement('div');
        div.className = 'category-item';
        div.innerHTML =
            '<span class="category-swatch" style="background:' + escapeHtml(cat.color) + '"></span>' +
            '<span class="category-name">' + escapeHtml(cat.name) + '</span>' +
            '<button onclick="removeCategory(\'' + cat.id + '\')" class="btn btn-danger btn-small">&times;</button>';
        list.appendChild(div);
    });

    updateCategoryDropdowns();
}

function updateCategoryDropdowns() {
    const selectors = ['group-category', 'group-dialog-category'];
    selectors.forEach(selId => {
        const sel = document.getElementById(selId);
        if (!sel) return;
        const currentVal = sel.value;
        sel.innerHTML = '<option value="">No category</option>';
        state.config.categories.forEach(cat => {
            const opt = document.createElement('option');
            opt.value = cat.id;
            opt.textContent = cat.name;
            opt.style.color = cat.color;
            sel.appendChild(opt);
        });
        sel.value = currentVal;
    });
}

function renderLegend() {
    const legend = document.getElementById('legend');
    if (!legend) return;

    // Only show legend if there are categories with associated grouping rules
    const usedCategoryIds = new Set(
        state.config.groupingRules
            .filter(r => r.enabled && r.categoryId)
            .map(r => r.categoryId)
    );

    const usedCategories = state.config.categories.filter(c => usedCategoryIds.has(c.id));

    if (usedCategories.length === 0) {
        legend.style.display = 'none';
        return;
    }

    legend.style.display = 'block';
    legend.innerHTML = '<div class="legend-title">Categories</div>';

    // Add built-in node types
    legend.innerHTML += '<div class="legend-item">' +
        '<span class="legend-swatch" style="background:#d4e6f1"></span>' +
        '<span>Internal package</span></div>';
    legend.innerHTML += '<div class="legend-item">' +
        '<span class="legend-swatch" style="background:#e8e8e8"></span>' +
        '<span>External package</span></div>';

    usedCategories.forEach(cat => {
        legend.innerHTML += '<div class="legend-item">' +
            '<span class="legend-swatch" style="background:' + escapeHtml(cat.color) + '"></span>' +
            '<span>' + escapeHtml(cat.name) + '</span></div>';
    });
}

// =============================================================================
// Package List
// =============================================================================
function renderPackageList() {
    const list = document.getElementById('packages-list');
    const countEl = document.getElementById('package-count');
    list.innerHTML = '';

    if (!state.config.graph) return;

    const nodes = [...state.config.graph.nodes].sort((a, b) => {
        if (a.external !== b.external) return a.external ? 1 : -1;
        return a.name.localeCompare(b.name);
    });

    countEl.textContent = nodes.length;

    nodes.forEach(node => {
        const deps = getDependencies(node.name).size;
        const dependents = getDependents(node.name).size;
        const div = document.createElement('div');
        div.className = 'package-item ' + (node.external ? 'external' : 'internal');
        div.innerHTML = '<span>' + escapeHtml(node.name) + '</span>' +
            '<span class="dep-count">' + dependents + '&larr; ' + deps + '&rarr;</span>';
        div.title = node.name + (node.external ? ' (external)' : ' (internal)') +
            '\nDependencies: ' + deps + '\nDependents: ' + dependents;

        div.addEventListener('click', () => toggleHighlight(node.name));
        div.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            showContextMenu(e.clientX, e.clientY, node.name);
        });
        list.appendChild(div);
    });
}

function filterPackageList() {
    const search = document.getElementById('package-search').value.toLowerCase();
    const items = document.querySelectorAll('#packages-list .package-item');
    items.forEach(item => {
        item.style.display = item.textContent.toLowerCase().includes(search) ? '' : 'none';
    });
}

// =============================================================================
// Collapsible Sections
// =============================================================================
function toggleSection(header) {
    header.classList.toggle('collapsed');
    const content = header.nextElementSibling;
    content.classList.toggle('collapsed');
}

// =============================================================================
// Save / Re-analyze
// =============================================================================
async function saveProject() {
    if (!state.filePath) {
        toast('No project file path set.', 'error');
        return;
    }

    showLoading('Saving...');
    try {
        const resp = await fetch('/api/project/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                filePath: state.filePath,
                config: state.config
            })
        });
        const data = await resp.json();

        if (data.error) {
            toast('Error saving: ' + data.error, 'error');
        } else {
            markClean();
            toast('Project saved.', 'success');
        }
    } catch (e) {
        toast('Error saving: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

async function reanalyze() {
    if (!state.config.sourceDirectories || state.config.sourceDirectories.length === 0) {
        toast('No source directories configured.', 'error');
        return;
    }

    showLoading('Analyzing source code...');
    try {
        const resp = await fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceDirectories: state.config.sourceDirectories })
        });
        const graph = await resp.json();

        if (graph.error) {
            toast('Error: ' + graph.error, 'error');
            return;
        }

        state.config.graph = graph;
        renderPackageList();
        renderGraph();
        toast('Analysis complete.', 'success');
    } catch (e) {
        toast('Error: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

// =============================================================================
// Module Selector
// =============================================================================
function renderModuleSelector() {
    const container = document.getElementById('module-selector');
    const list = document.getElementById('module-list');
    const countEl = document.getElementById('module-count');

    // Use allSourceDirectories if available, otherwise fall back to sourceDirectories
    const allDirs = state.config.allSourceDirectories && state.config.allSourceDirectories.length > 0
        ? state.config.allSourceDirectories
        : state.config.sourceDirectories || [];

    if (allDirs.length <= 1) {
        container.style.display = 'none';
        return;
    }

    container.style.display = 'block';
    const activeDirs = new Set(state.config.sourceDirectories || []);
    countEl.textContent = activeDirs.size + '/' + allDirs.length;

    list.innerHTML = '';
    // Compute the longest common prefix of all directory paths
    const prefix = findCommonPathPrefix(allDirs);

    allDirs.forEach(dir => {
        const shortName = prefix ? dir.substring(prefix.length) : dir;
        const id = 'mod-' + hashCode(dir);
        const checked = activeDirs.has(dir);

        const div = document.createElement('div');
        div.className = 'module-item';
        div.innerHTML =
            '<input type="checkbox" id="' + id + '" value="' + escapeHtml(dir) + '"' +
            (checked ? ' checked' : '') + '>' +
            '<label for="' + id + '">' + escapeHtml(shortName) + '</label>';
        list.appendChild(div);
    });
}

async function applyModuleSelection() {
    const checkboxes = document.querySelectorAll('#module-list input[type=checkbox]');
    const selectedDirs = Array.from(checkboxes).filter(cb => cb.checked).map(cb => cb.value);

    if (selectedDirs.length === 0) {
        toast('Select at least one module.', 'error');
        return;
    }

    // Check if selection actually changed
    const current = new Set(state.config.sourceDirectories);
    const next = new Set(selectedDirs);
    if (current.size === next.size && [...current].every(d => next.has(d))) {
        toast('No changes to apply.', 'info');
        return;
    }

    pushUndo();
    state.config.sourceDirectories = selectedDirs;

    showLoading('Re-analyzing with selected modules...');
    try {
        const resp = await fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceDirectories: selectedDirs })
        });
        const graph = await resp.json();

        if (graph.error) {
            toast('Error: ' + graph.error, 'error');
            return;
        }

        state.config.graph = graph;
        renderModuleSelector();
        renderPackageList();
        renderGraph();
        toast('Modules updated.', 'success');
    } catch (e) {
        toast('Error: ' + e.message, 'error');
    } finally {
        hideLoading();
    }
}

function hashCode(str) {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
        hash = ((hash << 5) - hash) + str.charCodeAt(i);
        hash |= 0;
    }
    return Math.abs(hash).toString(36);
}

// =============================================================================
// Sidebar Resizer
// =============================================================================
function initSidebarResizer() {
    const resizer = document.getElementById('sidebar-resizer');
    const sidebar = document.getElementById('sidebar');
    if (!resizer) return;

    let isResizing = false;

    resizer.addEventListener('mousedown', (e) => {
        isResizing = true;
        document.body.style.cursor = 'col-resize';
        document.body.style.userSelect = 'none';
        e.preventDefault();
    });

    document.addEventListener('mousemove', (e) => {
        if (!isResizing) return;
        const newWidth = document.body.clientWidth - e.clientX;
        if (newWidth >= 250 && newWidth <= 600) {
            sidebar.style.width = newWidth + 'px';
        }
    });

    document.addEventListener('mouseup', () => {
        if (isResizing) {
            isResizing = false;
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        }
    });
}

// =============================================================================
// Graph Interaction Init
// =============================================================================
function initGraphInteraction() {
    initZoomPan();
    initDragSelect();
}

// =============================================================================
// Keyboard Shortcuts
// =============================================================================
function initBeforeUnload() {
    window.addEventListener('beforeunload', (e) => {
        if (dirty) {
            e.preventDefault();
            e.returnValue = '';
        }
    });
}

function initKeyboardShortcuts() {
    document.addEventListener('keydown', (e) => {
        // Ctrl+Z / Cmd+Z = Undo
        if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
            e.preventDefault();
            undo();
        }
        // Ctrl+Y / Cmd+Y or Ctrl+Shift+Z = Redo
        if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) {
            e.preventDefault();
            redo();
        }
        // Escape = clear highlight and selection
        if (e.key === 'Escape') {
            if (highlightedNode) {
                highlightedNode = null;
                const container = document.getElementById('graph-container');
                container.classList.remove('highlight-mode');
                container.querySelectorAll('.node, .edge').forEach(el => el.classList.remove('dimmed'));
            }
            hideContextMenu();
            clearSelection();
        }
        // Ctrl+S / Cmd+S = Save
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            saveProject();
        }
    });
}

// =============================================================================
// Loading / Toast
// =============================================================================
function showLoading(text) {
    document.getElementById('loading-text').textContent = text || 'Loading...';
    document.getElementById('loading').style.display = 'flex';
}

function hideLoading() {
    document.getElementById('loading').style.display = 'none';
}

function toast(message, type) {
    type = type || 'info';
    const container = document.getElementById('toast-container');
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = message;
    container.appendChild(el);

    setTimeout(() => {
        el.style.opacity = '0';
        el.style.transition = 'opacity 0.3s';
        setTimeout(() => el.remove(), 300);
    }, 3000);
}

// =============================================================================
// Utilities
// =============================================================================
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Finds the longest common directory prefix across a list of paths.
 * Returns the prefix including the trailing separator, or '' if none.
 */
function findCommonPathPrefix(paths) {
    if (!paths || paths.length === 0) return '';
    if (paths.length === 1) {
        // For a single path, return everything up to and including the last separator
        const lastSep = Math.max(paths[0].lastIndexOf('/'), paths[0].lastIndexOf('\\'));
        return lastSep > 0 ? paths[0].substring(0, lastSep + 1) : '';
    }

    // Normalize separators to / for comparison
    const normalized = paths.map(p => p.replace(/\\/g, '/'));
    let prefix = normalized[0];
    for (let i = 1; i < normalized.length; i++) {
        while (prefix && !normalized[i].startsWith(prefix)) {
            // Remove trailing slash before finding previous separator
            const trimmed = prefix.endsWith('/') ? prefix.substring(0, prefix.length - 1) : prefix;
            const lastSep = trimmed.lastIndexOf('/');
            if (lastSep <= 0) { prefix = ''; break; }
            prefix = trimmed.substring(0, lastSep + 1);
        }
    }
    if (!prefix) return '';

    // Ensure prefix ends at a directory boundary
    if (!prefix.endsWith('/')) {
        const lastSep = prefix.lastIndexOf('/');
        prefix = lastSep > 0 ? prefix.substring(0, lastSep + 1) : '';
    }

    // Return using the original separator style from the first path
    if (paths[0].includes('\\') && !paths[0].includes('/')) {
        return prefix.replace(/\//g, '\\');
    }
    return prefix;
}
