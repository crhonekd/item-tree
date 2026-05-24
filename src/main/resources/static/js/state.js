const STORAGE_KEY = 'itemtreeTestUi.v1';

const persisted = (() => {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}; }
  catch { return {}; }
})();

export const state = {
  iceUser: persisted.iceUser ?? '',
  impersonatedUser: persisted.impersonatedUser ?? '',
  backendBaseUrl: persisted.backendBaseUrl ?? '',
  homeFolderId: null,
  tree: {
    nodesById: new Map(),
    childrenByParent: new Map(),
    expanded: new Set(),
    loadedSubtreeOf: new Set(),
    selectedId: null,
  },
  clipboard: null,
  lastDetail: null,
  lastRefresh: null,
};

export function savePersisted() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    iceUser: state.iceUser,
    impersonatedUser: state.impersonatedUser,
    backendBaseUrl: state.backendBaseUrl,
  }));
}

export function resetTreeState() {
  state.homeFolderId = null;
  state.tree.nodesById.clear();
  state.tree.childrenByParent.clear();
  state.tree.expanded.clear();
  state.tree.loadedSubtreeOf.clear();
  state.tree.selectedId = null;
  state.clipboard = null;
  state.lastDetail = null;
}

export function ingestNodes(nodes) {
  for (const n of nodes) {
    state.tree.nodesById.set(n.itemTreeId, n);
    let siblings = state.tree.childrenByParent.get(n.parentId);
    if (!siblings) {
      siblings = new Set();
      state.tree.childrenByParent.set(n.parentId, siblings);
    }
    siblings.add(n.itemTreeId);
  }
}

export function removeNode(id) {
  const node = state.tree.nodesById.get(id);
  if (!node) return;
  const children = state.tree.childrenByParent.get(id);
  if (children) {
    for (const childId of [...children]) removeNode(childId);
    state.tree.childrenByParent.delete(id);
  }
  state.tree.nodesById.delete(id);
  const siblings = state.tree.childrenByParent.get(node.parentId);
  if (siblings) {
    siblings.delete(id);
    if (siblings.size === 0) state.tree.childrenByParent.delete(node.parentId);
  }
  state.tree.expanded.delete(id);
  state.tree.loadedSubtreeOf.delete(id);
  if (state.tree.selectedId === id) state.tree.selectedId = null;
}

export function reparentNode(id, newParentId) {
  const node = state.tree.nodesById.get(id);
  if (!node) return;
  const oldSiblings = state.tree.childrenByParent.get(node.parentId);
  if (oldSiblings) {
    oldSiblings.delete(id);
    if (oldSiblings.size === 0) state.tree.childrenByParent.delete(node.parentId);
  }
  state.tree.nodesById.set(id, { ...node, parentId: newParentId });
  let newSiblings = state.tree.childrenByParent.get(newParentId);
  if (!newSiblings) {
    newSiblings = new Set();
    state.tree.childrenByParent.set(newParentId, newSiblings);
  }
  newSiblings.add(id);
}

export function renameNode(id, newName) {
  const node = state.tree.nodesById.get(id);
  if (!node) return;
  state.tree.nodesById.set(id, { ...node, name: newName });
}
