import { state, removeNode, reparentNode, ingestNodes } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError, toastSuccess } from './toast.js';
import { renderTree, refreshSubtree } from './tree.js';
import { openCreateModal, openRenameModal, openDeleteConfirm, openEditDataModal } from './modal.js';

const FOLDER = 'Folder';
const ROOT_ID = 1;

let openMenuEl = null;

function closeMenu() {
  if (openMenuEl) { openMenuEl.remove(); openMenuEl = null; }
}

document.addEventListener('click', closeMenu);
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (openMenuEl) { closeMenu(); return; }
    if (state.clipboard) { state.clipboard = null; renderTree(); }
  }
});

export function openContextMenu(id, clientX, clientY) {
  closeMenu();
  const node = state.tree.nodesById.get(id);
  if (!node) return;
  const isRoot = id === ROOT_ID;
  const isFolder = node.type === FOLDER;
  const hasData = !isFolder && !node.type.startsWith('Shortcut');

  const items = [];

  if (!isRoot) {
    if (isFolder) {
      items.push({ label: 'Refresh subtree', action: () => refreshSubtree(id) });
      items.push({ label: 'Create child', action: () => openCreateModal(id) });
    }
    if (hasData) {
      items.push({ label: 'Edit data', action: () => openEditDataModal(id, null) });
    }
    items.push({ label: 'Rename', action: () => openRenameModal(id, node.name) });
    items.push({ label: 'Delete', action: () => openDeleteConfirm(id, node) });
    items.push({ label: 'Cut', action: () => { state.clipboard = { op: 'cut', sourceId: id, sourceName: node.name }; renderTree(); } });
    items.push({ label: 'Copy', action: () => { state.clipboard = { op: 'copy', sourceId: id, sourceName: node.name }; renderTree(); } });
    if (isFolder && state.clipboard) {
      items.push({ label: `Paste here (${state.clipboard.op} ${state.clipboard.sourceName})`,
                   action: () => pasteInto(id) });
    }
  }

  // Phase 20: Copy ID and Show-and-copy path apply to every node, root included.
  items.push({ label: `Copy ID (${id})`, action: () => copyId(id), separatorBefore: !isRoot });
  items.push({ label: 'Show and copy full path', action: () => showAndCopyPath(node) });

  const ul = document.createElement('ul');
  ul.className = 'context-menu';
  ul.style.left = `${clientX}px`;
  ul.style.top = `${clientY}px`;
  for (const item of items) {
    const li = document.createElement('li');
    li.textContent = item.label;
    if (item.separatorAfter) li.classList.add('context-menu-separator');
    if (item.separatorBefore) li.classList.add('context-menu-separator-before');
    li.addEventListener('click', (e) => { e.stopPropagation(); closeMenu(); item.action(); });
    ul.appendChild(li);
  }
  document.body.appendChild(ul);
  openMenuEl = ul;
}

async function pasteInto(targetId) {
  const cb = state.clipboard;
  if (!cb) return;
  try {
    if (cb.op === 'cut') {
      const moved = await api.moveItem(cb.sourceId, targetId);
      reparentNode(cb.sourceId, targetId);
      ingestNodes([moved]);
      state.clipboard = null;
      toastSuccess(`Moved ${cb.sourceName} (id ${cb.sourceId})`);
    } else {
      const newNodes = await api.copyItem(cb.sourceId, targetId);
      ingestNodes(newNodes);
      toastSuccess(`Copied — ${newNodes.length} new node(s)`);
    }
    renderTree();
  } catch (e) {
    if (e instanceof ProblemError) {
      toastError(e.problem);
      if (e.problem.errorCode === 'ITEM_NOT_FOUND') removeNode(cb.sourceId);
    } else {
      toastError(String(e));
    }
  }
}

// navigator.clipboard requires a secure context (HTTPS or localhost).
// Fall back to execCommand for plain HTTP on remote hosts.
async function writeToClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const el = document.createElement('textarea');
  el.value = text;
  el.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
  document.body.appendChild(el);
  el.focus();
  el.select();
  try {
    if (!document.execCommand('copy')) throw new Error('execCommand copy returned false');
  } finally {
    document.body.removeChild(el);
  }
}

async function copyId(id) {
  const text = String(id);
  try {
    await writeToClipboard(text);
    toastSuccess(`Copied id ${text}`);
  } catch (e) {
    toastError(`Clipboard write failed: ${text}`);
  }
}

async function showAndCopyPath(node) {
  const path = node.path ?? '';
  if (!path) {
    alert('Path is not available for this node (no path was returned by the server).');
    return;
  }
  try {
    await writeToClipboard(path);
  } catch (e) {
    toastError(`Clipboard write failed; path is: ${path}`);
    return;
  }
  alert('Path copied:\n' + path);
}
