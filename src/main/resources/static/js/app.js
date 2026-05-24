import { state, savePersisted, resetTreeState, ingestNodes } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError } from './toast.js';
import { renderTree, ingestSubtreeResult } from './tree.js';
import { runSearch } from './search.js';
import { runRefresh, runProbe } from './refresh.js';

function $(id) { return document.getElementById(id); }

function bindHeader() {
  $('ice-user').value = state.iceUser;
  $('impersonated-user').value = state.impersonatedUser;
  $('backend-url').value = state.backendBaseUrl;

  $('ice-user').addEventListener('input', (e) => { state.iceUser = e.target.value.trim(); savePersisted(); });
  $('impersonated-user').addEventListener('input', (e) => { state.impersonatedUser = e.target.value.trim(); savePersisted(); });
  $('backend-url').addEventListener('change', (e) => {
    const newUrl = e.target.value.trim();
    if (newUrl !== state.backendBaseUrl) {
      state.backendBaseUrl = newUrl;
      savePersisted();
      resetTreeState();
      renderTree();
      $('detail-root').innerHTML = '(backend URL changed — log in again)';
    }
  });
  $('probe-btn').addEventListener('click', runProbe);
  $('login-btn').addEventListener('click', doLogin);
}

function bindSearch() {
  $('search-btn').addEventListener('click', runSearch);
  $('search-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });
}

function bindRefresh() {
  $('refresh-delta-btn').addEventListener('click', () => runRefresh('delta'));
  $('refresh-full-btn').addEventListener('click', () => runRefresh('full'));
}

async function doLogin() {
  if (!state.iceUser) {
    toastError({ title: 'X-Ice-User required', detail: 'Type a user name first.' });
    return;
  }
  resetTreeState();
  renderTree();
  $('detail-root').innerHTML = '(loading…)';
  try {
    const [home, tree] = await Promise.all([
      api.getHomeFolder(state.iceUser),
      api.getTree(),
    ]);
    state.homeFolderId = home.itemTreeId;
    ingestNodes(tree);

    // expand only the ancestor chain from root down to the home folder
    state.tree.expanded.add(1);
    let cur = state.tree.nodesById.get(home.itemTreeId);
    while (cur && cur.parentId && cur.parentId !== 0) {
      state.tree.expanded.add(cur.parentId);
      cur = state.tree.nodesById.get(cur.parentId);
    }

    const subtree = await api.getSubtree(home.itemTreeId);
    ingestSubtreeResult(home.itemTreeId, subtree);

    // expand the home folder itself and any folders directly within its loaded subtree
    state.tree.expanded.add(home.itemTreeId);
    for (const n of subtree) {
      if (n.type === 'Folder') state.tree.expanded.add(n.itemTreeId);
    }

    renderTree();
    $('detail-root').innerHTML = '(logged in as <b></b>; click a node)';
    $('detail-root').querySelector('b').textContent = state.iceUser;
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
    $('detail-root').innerHTML = '(login failed — see toast)';
  }
}

document.addEventListener('DOMContentLoaded', () => {
  bindHeader();
  bindSearch();
  bindRefresh();
  renderTree();
});
