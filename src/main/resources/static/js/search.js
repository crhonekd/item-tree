import { state, ingestNodes } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError } from './toast.js';
import { renderTree, ingestSubtreeResult } from './tree.js';

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  })[c]);
}

export async function runSearch() {
  const input = document.getElementById('search-input').value.trim();
  const mode = document.querySelector('input[name="search-mode"]:checked').value;
  const limit = document.getElementById('search-limit').value.trim() || undefined;
  const results = document.getElementById('search-results');
  results.innerHTML = '';
  if (!input) return;
  const args = mode === 'id'
    ? { id: Number(input), limit }
    : { name: input, limit };
  try {
    const hits = await api.search(args);
    if (!hits || hits.length === 0) {
      results.innerHTML = '<li>(no results)</li>';
      return;
    }
    for (const hit of hits) {
      const li = document.createElement('li');
      li.textContent = `${hit.itemTreeId}  ${hit.type}  ${hit.name}`;
      li.addEventListener('click', () => navigateTo(hit.itemTreeId));
      results.appendChild(li);
    }
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
  }
}

async function navigateTo(id) {
  if (!state.tree.nodesById.has(id)) {
    // load the node's subtree so it appears in the tree
    try {
      const subtree = await api.getSubtree(id);
      ingestSubtreeResult(id, subtree);
    } catch (e) {
      if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
      return;
    }
  }
  // expand all ancestors so the row is visible
  let cur = state.tree.nodesById.get(id);
  while (cur && cur.parentId && cur.parentId !== 0) {
    state.tree.expanded.add(cur.parentId);
    cur = state.tree.nodesById.get(cur.parentId);
  }
  state.tree.selectedId = id;
  renderTree();
  const row = document.querySelector(`.tree-node[data-id="${id}"] .tree-row`);
  row?.scrollIntoView({ block: 'center', behavior: 'smooth' });
}
