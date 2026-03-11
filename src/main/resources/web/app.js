// ===== State =====
let state = {
    filePath: null,
    config: {
        name: '',
        rootDirectory: '',
        sourceDirectories: [],
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

// ===== Initialization =====
document.addEventListener('DOMContentLoaded', async () => {
    // Initialize viz.js
    vizInstance = await Viz.instance();

    // Check if a project was passed via --project flag
    try {
        const resp = await fetch('/api/project/initial');
        const data = await resp.json();
        if (data.config) {
            state.config = data.config;
            state.filePath = data.filePath;
            showMainApp();
        }
    } catch (e) {
        // No initial project, show landing page
    }

    initSidebarResizer();
});

// ===== Landing Page =====
function showCreateProject() {
    document.getElementById('create-dialog').style.display = 'flex';
}

function showOpenProject() {
    document.getElementById('open-dialog').style.display = 'flex';
}

function hideDialogs() {
    document.getElementById('create-dialog').style.display = 'none';
    document.getElementById('open-dialog').style.display = 'none';
}

// ===== Create Project =====
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
            alert('Error: ' + data.error);
            return;
        }

        const list = document.getElementById('source-dirs-list');
        list.innerHTML = '';

        if (data.sourceDirectories.length === 0) {
            list.innerHTML = '<p style="color:#888">No Java source directories found.</p>';
        } else {
            data.sourceDirectories.forEach(dir => {
                const label = document.createElement('label');
                label.innerHTML = `<input type="checkbox" value="${dir}" checked> ${dir}`;
                list.appendChild(label);
            });
        }

        document.getElementById('source-dirs-section').style.display = 'block';
        document.getElementById('create-btn').disabled = data.sourceDirectories.length === 0;
    } catch (e) {
        alert('Error scanning: ' + e.message);
    } finally {
        hideLoading();
    }
}

async function createProject() {
    const name = document.getElementById('create-name').value.trim();
    const rootDir = document.getElementById('create-root').value.trim();
    if (!name || !rootDir) {
        alert('Please fill in all fields.');
        return;
    }

    const checkboxes = document.querySelectorAll('#source-dirs-list input[type=checkbox]:checked');
    const selectedDirs = Array.from(checkboxes).map(cb => cb.value);

    if (selectedDirs.length === 0) {
        alert('Please select at least one source directory.');
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
            alert('Error: ' + graph.error);
            return;
        }

        state.config = {
            name: name,
            rootDirectory: rootDir,
            sourceDirectories: selectedDirs,
            groupingRules: [],
            hideRules: [],
            graphDirection: 'TOP_TO_BOTTOM',
            highlightCircularDependencies: true,
            trimCommonPrefix: false,
            graph: graph
        };
        state.filePath = rootDir.replace(/\/$/, '') + '/' + name + '.pg2';

        hideDialogs();
        showMainApp();
    } catch (e) {
        alert('Error analyzing: ' + e.message);
    } finally {
        hideLoading();
    }
}

// ===== Open Project =====
async function openProject() {
    const filePath = document.getElementById('open-path').value.trim();
    if (!filePath) return;

    showLoading('Opening project...');
    try {
        const resp = await fetch('/api/project/open', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ filePath: filePath })
        });
        const data = await resp.json();

        if (data.error) {
            alert('Error: ' + data.error);
            return;
        }

        state.config = data.config;
        state.filePath = data.filePath;
        hideDialogs();
        showMainApp();
    } catch (e) {
        alert('Error opening: ' + e.message);
    } finally {
        hideLoading();
    }
}

// ===== Main App =====
function showMainApp() {
    document.getElementById('landing-page').style.display = 'none';
    document.getElementById('main-app').style.display = 'flex';
    document.getElementById('main-app').style.flexDirection = 'column';
    document.getElementById('project-title').textContent = state.config.name || 'packagraph2';

    // Sync UI with config
    document.getElementById('opt-direction').value = state.config.graphDirection;
    document.getElementById('opt-circular').checked = state.config.highlightCircularDependencies;
    document.getElementById('opt-trim-prefix').checked = state.config.trimCommonPrefix;

    renderRulesList();
    renderPackageList();
    renderGraph();
}

// ===== Graph Rendering =====
async function renderGraph() {
    if (!state.config.graph) return;

    // Debounce rendering
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
                console.error('DOT generation error:', data.error);
                return;
            }

            const svg = vizInstance.renderSVGElement(data.dot);
            const container = document.getElementById('graph-container');
            container.innerHTML = '';
            container.appendChild(svg);

            // Make SVG fill container and be pannable
            svg.style.width = '100%';
            svg.style.height = '100%';

        } catch (e) {
            console.error('Render error:', e);
        }
    }, 200);
}

// ===== Config Changes =====
function onConfigChanged() {
    state.config.graphDirection = document.getElementById('opt-direction').value;
    state.config.highlightCircularDependencies = document.getElementById('opt-circular').checked;
    state.config.trimCommonPrefix = document.getElementById('opt-trim-prefix').checked;
    renderGraph();
}

// ===== Grouping Rules =====
function addGroupingRule() {
    const pattern = document.getElementById('group-pattern').value.trim();
    const displayName = document.getElementById('group-name').value.trim();
    if (!pattern || !displayName) return;

    state.config.groupingRules.push({
        id: crypto.randomUUID(),
        pattern: pattern,
        displayName: displayName,
        enabled: true
    });

    document.getElementById('group-pattern').value = '';
    document.getElementById('group-name').value = '';

    renderRulesList();
    renderGraph();
}

function toggleGroupingRule(id) {
    const rule = state.config.groupingRules.find(r => r.id === id);
    if (rule) {
        rule.enabled = !rule.enabled;
        renderRulesList();
        renderGraph();
    }
}

function removeGroupingRule(id) {
    state.config.groupingRules = state.config.groupingRules.filter(r => r.id !== id);
    renderRulesList();
    renderGraph();
}

// ===== Hide Rules =====
function addHideRule() {
    const pattern = document.getElementById('hide-pattern').value.trim();
    if (!pattern) return;

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
    const rule = state.config.hideRules.find(r => r.id === id);
    if (rule) {
        rule.enabled = !rule.enabled;
        renderRulesList();
        renderGraph();
    }
}

function removeHideRule(id) {
    state.config.hideRules = state.config.hideRules.filter(r => r.id !== id);
    renderRulesList();
    renderGraph();
}

// ===== Rules List Rendering =====
function renderRulesList() {
    // Grouping rules
    const groupList = document.getElementById('grouping-rules-list');
    groupList.innerHTML = '';
    state.config.groupingRules.forEach(rule => {
        const div = document.createElement('div');
        div.className = 'rule-item';
        div.innerHTML = `
            <input type="checkbox" ${rule.enabled ? 'checked' : ''} onchange="toggleGroupingRule('${rule.id}')">
            <span class="rule-text ${rule.enabled ? '' : 'disabled'}">${rule.pattern}</span>
            <span class="rule-arrow">&rarr;</span>
            <span class="rule-text ${rule.enabled ? '' : 'disabled'}">${rule.displayName}</span>
            <button onclick="removeGroupingRule('${rule.id}')" class="btn btn-danger btn-small">&times;</button>
        `;
        groupList.appendChild(div);
    });

    // Hide rules
    const hideList = document.getElementById('hide-rules-list');
    hideList.innerHTML = '';
    state.config.hideRules.forEach(rule => {
        const div = document.createElement('div');
        div.className = 'rule-item';
        div.innerHTML = `
            <input type="checkbox" ${rule.enabled ? 'checked' : ''} onchange="toggleHideRule('${rule.id}')">
            <span class="rule-text ${rule.enabled ? '' : 'disabled'}">${rule.pattern}</span>
            <button onclick="removeHideRule('${rule.id}')" class="btn btn-danger btn-small">&times;</button>
        `;
        hideList.appendChild(div);
    });
}

// ===== Package List =====
function renderPackageList() {
    const list = document.getElementById('packages-list');
    list.innerHTML = '';

    if (!state.config.graph) return;

    const nodes = [...state.config.graph.nodes].sort((a, b) => {
        // Internal first, then alphabetical
        if (a.external !== b.external) return a.external ? 1 : -1;
        return a.name.localeCompare(b.name);
    });

    nodes.forEach(node => {
        const div = document.createElement('div');
        div.className = 'package-item ' + (node.external ? 'external' : 'internal');
        div.textContent = node.name;
        div.title = node.external ? 'External dependency' : 'Internal package';
        list.appendChild(div);
    });
}

function filterPackageList() {
    const search = document.getElementById('package-search').value.toLowerCase();
    const items = document.querySelectorAll('.package-item');
    items.forEach(item => {
        item.style.display = item.textContent.toLowerCase().includes(search) ? '' : 'none';
    });
}

// ===== Save / Re-analyze =====
async function saveProject() {
    if (!state.filePath) {
        alert('No project file path set.');
        return;
    }

    showLoading('Saving project...');
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
            alert('Error saving: ' + data.error);
        }
    } catch (e) {
        alert('Error saving: ' + e.message);
    } finally {
        hideLoading();
    }
}

async function reanalyze() {
    if (!state.config.sourceDirectories || state.config.sourceDirectories.length === 0) {
        alert('No source directories configured.');
        return;
    }

    showLoading('Re-analyzing source code...');
    try {
        const resp = await fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceDirectories: state.config.sourceDirectories })
        });
        const graph = await resp.json();

        if (graph.error) {
            alert('Error: ' + graph.error);
            return;
        }

        state.config.graph = graph;
        renderPackageList();
        renderGraph();
    } catch (e) {
        alert('Error analyzing: ' + e.message);
    } finally {
        hideLoading();
    }
}

// ===== Sidebar Resizer =====
function initSidebarResizer() {
    const resizer = document.getElementById('sidebar-resizer');
    const sidebar = document.getElementById('sidebar');
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
        isResizing = false;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
    });
}

// ===== Loading =====
function showLoading(text) {
    document.getElementById('loading-text').textContent = text || 'Loading...';
    document.getElementById('loading').style.display = 'flex';
}

function hideLoading() {
    document.getElementById('loading').style.display = 'none';
}
