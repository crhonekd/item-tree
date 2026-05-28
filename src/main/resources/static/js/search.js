import { state, ingestNodes } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError } from './toast.js';
import { renderTree, selectAndLoad, scrollToNode } from './tree.js';

const $ = (id) => document.getElementById(id);

function hitNode(hit) {
  return { itemTreeId: hit.itemTreeId, parentId: hit.parentId, name: hit.name, type: hit.type };
}

// Materialize a hit's location in the tree from the ancestors carried on the hit
// itself (no extra backend call): ingest root→hit and expand the ancestor chain.
function ingestHit(hit) {
  const ancestors = hit.ancestors || [];
  ingestNodes([...ancestors, hitNode(hit)]);
  for (const a of ancestors) state.tree.expanded.add(a.itemTreeId);
}

function setClearVisible(visible) {
  const btn = $('search-clear-btn');
  if (btn) btn.hidden = !visible;
}

export async function runSearch() {
  const q = $('search-input').value.trim();
  const limit = $('search-limit').value.trim() || undefined;
  const results = $('search-results');
  const status = $('search-status');
  const embed = $('embed-in-tree').checked;

  results.innerHTML = '';
  state.search.matchIds.clear();
  status.textContent = '';
  setClearVisible(false);
  if (!q) { renderTree(); return; }

  let hits;
  try {
    hits = await api.search({ q, limit });
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
    return;
  }

  if (!hits || hits.length === 0) {
    if (embed) { status.textContent = '(no results)'; renderTree(); }
    else { results.innerHTML = '<li>(no results)</li>'; }
    return;
  }

  if (embed) {
    for (const hit of hits) {
      ingestHit(hit);
      state.search.matchIds.add(hit.itemTreeId);
    }
    status.textContent = `${hits.length} match${hits.length === 1 ? '' : 'es'}`;
    setClearVisible(true);
    renderTree();
    scrollToNode(hits[0].itemTreeId);
  } else {
    for (const hit of hits) {
      const li = document.createElement('li');
      li.textContent = `${hit.itemTreeId}  ${hit.type}  ${hit.name}`;
      li.addEventListener('click', () => revealHit(hit));
      results.appendChild(li);
    }
  }
}

// List-mode click: reveal exactly this hit in the tree, select it, load its
// detail from the backend, and scroll to it.
async function revealHit(hit) {
  ingestHit(hit);
  await selectAndLoad(hit.itemTreeId);
  scrollToNode(hit.itemTreeId);
}

export function clearSearchHighlight() {
  state.search.matchIds.clear();
  $('search-status').textContent = '';
  setClearVisible(false);
  renderTree();
}
