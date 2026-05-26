import { state, ingestNodes, removeNode } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError } from './toast.js';
import { openContextMenu } from './menu.js';
import { renderDetailFolder, renderDetailItem, clearDetail } from './detail.js';

const FOLDER = 'Folder';
const ROOT_ID = 1;

const treeRoot = () => document.getElementById('tree-root');

export function renderTree() {
  const root = treeRoot();
  root.innerHTML = '';
  if (!state.tree.nodesById.has(ROOT_ID)) {
    root.appendChild(document.createTextNode('(no tree loaded — click Login)'));
    return;
  }
  root.appendChild(renderNode(ROOT_ID, 0));
}

function renderNode(id, depth) {
  const node = state.tree.nodesById.get(id);
  if (!node) return document.createTextNode('');
  const isFolder = node.type === FOLDER;
  const isExpanded = state.tree.expanded.has(id);
  const isHome = state.homeFolderId === id;
  const isSelected = state.tree.selectedId === id;
  const isCut = state.clipboard?.op === 'cut' && state.clipboard.sourceId === id;

  const li = document.createElement('li');
  li.className = 'tree-node';
  li.dataset.id = String(id);

  const row = document.createElement('div');
  row.className = 'tree-row' + (isSelected ? ' selected' : '') + (isCut ? ' cut' : '');
  row.style.paddingLeft = `${depth * 14}px`;

  const lead = document.createElement('span');
  if (isFolder) {
    lead.className = 'tree-chevron';
    lead.textContent = isExpanded ? '▼' : '▶';
    lead.addEventListener('click', (e) => { e.stopPropagation(); onChevronClick(id); });
  } else {
    lead.className = 'tree-icon';
    lead.textContent = node.type.startsWith('Shortcut') ? '•' : '✱';
  }
  row.appendChild(lead);

  const label = document.createElement('span');
  label.className = 'tree-label';
  label.textContent = node.name + (isHome ? ' ★' : '');
  label.title = `${node.type} (id ${id})`;
  label.addEventListener('click', () => onNameClick(id));
  row.appendChild(label);

  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    openContextMenu(id, e.clientX, e.clientY);
  });

  li.appendChild(row);

  if (isFolder && isExpanded) {
    const childIds = state.tree.childrenByParent.get(id);
    if (childIds && childIds.size > 0) {
      const ul = document.createElement('ul');
      ul.className = 'tree-children';
      const sorted = [...childIds].sort((a, b) => {
        const na = state.tree.nodesById.get(a);
        const nb = state.tree.nodesById.get(b);
        if (!na || !nb) return 0;
        const fa = na.type === FOLDER;
        const fb = nb.type === FOLDER;
        if (fa !== fb) return fa ? -1 : 1;
        return na.name.localeCompare(nb.name);
      });
      for (const cid of sorted) ul.appendChild(renderNode(cid, depth + 1));
      li.appendChild(ul);
    }
  }
  return li;
}

async function onChevronClick(id) {
  if (state.tree.expanded.has(id)) {
    state.tree.expanded.delete(id);
    renderTree();
    return;
  }
  if (!state.tree.loadedSubtreeOf.has(id)) {
    try {
      const subtree = await api.getSubtree(id);
      ingestSubtreeResult(id, subtree);
    } catch (e) {
      if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
      return;
    }
  }
  state.tree.expanded.add(id);
  renderTree();
}

// Mark only the queried root as loaded — used after a level-1 fetch.
// Do NOT mark child folders as loaded: their children are not in the payload,
// so marking them would suppress later lazy-load fetches when expanded.
export function ingestSubtreeResult(rootId, nodes) {
  ingestNodes(nodes);
  state.tree.loadedSubtreeOf.add(rootId);
}

// Mark the queried root AND every folder in the payload as loaded — used after
// a recursive subtree-full fetch, where every folder's children are in the payload.
export function ingestSubtreeFullResult(rootId, nodes) {
  ingestNodes(nodes);
  state.tree.loadedSubtreeOf.add(rootId);
  for (const n of nodes) {
    if (n.type === FOLDER) state.tree.loadedSubtreeOf.add(n.itemTreeId);
  }
}

async function onNameClick(id) {
  state.tree.selectedId = id;
  renderTree();
  try {
    const items = await api.getItems([id]);
    const item = items?.[0];
    if (!item) {
      toastError({ title: 'Not found', status: 404, detail: `id ${id}` });
      clearDetail();
      return;
    }
    if (item.type === FOLDER) renderDetailFolder(item); else renderDetailItem(item);
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
  }
}

export async function refreshSubtree(id) {
  try {
    const subtree = await api.getSubtree(id);
    // drop existing children of id (we have a fresh authoritative list)
    const existing = state.tree.childrenByParent.get(id);
    if (existing) {
      for (const cid of [...existing]) removeNode(cid);
    }
    ingestSubtreeResult(id, subtree);
    state.tree.expanded.add(id);
    renderTree();
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
  }
}
