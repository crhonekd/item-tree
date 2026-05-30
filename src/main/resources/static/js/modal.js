import { state, ingestNodes, removeNode, renameNode } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError, toastSuccess } from './toast.js';
import { renderTree } from './tree.js';

const KNOWN_TYPES = [
  'Folder', 'Shortcut', 'Shortcut.Report', 'Shortcut.Filter', 'Shortcut.Filter.Nested',
  'DrillDown.Set', 'Report', 'Filter',
  'Details.Column.Collection', 'Numeric.Bucket.Collection', 'Discrete.Bucket.Collection',
  'Bucket.Collection', 'View', 'UDF.Context', 'Eval', 'UDFRepo',
];

const TYPES_WITHOUT_DATA = new Set([
  'Folder', 'Shortcut', 'Shortcut.Report', 'Shortcut.Filter', 'Shortcut.Filter.Nested',
]);

const UDF_REPO = 'UDFRepo';

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  })[c]);
}

function openModal(innerHtml, onMount) {
  const root = document.getElementById('modal-root');
  root.innerHTML = `<div class="modal-backdrop"><div class="modal">${innerHtml}</div></div>`;
  const backdrop = root.querySelector('.modal-backdrop');
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop) closeModal(); });
  onMount?.(root);
}

function closeModal() {
  document.getElementById('modal-root').innerHTML = '';
}

export function openCreateModal(parentId) {
  const parent = state.tree.nodesById.get(parentId);
  const typeOptions = KNOWN_TYPES.map((t) => `<option value="${t}">${t}</option>`).join('');
  openModal(`
    <h3>Create child of "${escapeHtml(parent?.name ?? '?')}" (id ${parentId})</h3>
    <label id="cm-name-label">Name</label>
    <input id="cm-name" type="text" maxlength="70">
    <label>Type</label>
    <select id="cm-type">${typeOptions}</select>
    <label>Custom type (overrides dropdown if set)</label>
    <input id="cm-custom-type" type="text" maxlength="30" placeholder="(leave blank to use dropdown)">
    <label>Data (JSON; disabled for types-without-data)</label>
    <textarea id="cm-data"></textarea>
    <div id="cm-error" class="modal-error"></div>
    <div class="modal-actions">
      <button id="cm-cancel" type="button">Cancel</button>
      <button id="cm-create" type="button">Create</button>
    </div>
  `, (root) => {
    const typeSel = root.querySelector('#cm-type');
    const customType = root.querySelector('#cm-custom-type');
    const dataArea = root.querySelector('#cm-data');
    const nameInput = root.querySelector('#cm-name');
    const nameLabel = root.querySelector('#cm-name-label');
    const err = root.querySelector('#cm-error');
    const recompute = () => {
      const effective = customType.value.trim() || typeSel.value;
      const noData = TYPES_WITHOUT_DATA.has(effective);
      dataArea.disabled = noData;
      if (noData) dataArea.value = '';
      const isUdfRepo = effective === UDF_REPO;
      nameLabel.hidden = isUdfRepo;
      nameInput.hidden = isUdfRepo;
      if (isUdfRepo) nameInput.value = '';
    };
    typeSel.addEventListener('change', recompute);
    customType.addEventListener('input', recompute);
    recompute();
    root.querySelector('#cm-cancel').addEventListener('click', closeModal);
    root.querySelector('#cm-create').addEventListener('click', async () => {
      const type = customType.value.trim() || typeSel.value;
      const isUdfRepo = type === UDF_REPO;
      const name = isUdfRepo ? UDF_REPO : nameInput.value.trim();
      if (!isUdfRepo && !name) { err.textContent = 'Name required'; return; }
      let data = null;
      if (!dataArea.disabled && dataArea.value.trim()) {
        try { data = JSON.parse(dataArea.value); }
        catch (e) { err.textContent = `Invalid JSON: ${e.message}`; return; }
      }
      try {
        const created = await api.createItem({ parentId, name, type, data });
        ingestNodes([created]);
        state.tree.expanded.add(parentId);
        renderTree();
        toastSuccess(`Created id ${created.itemTreeId}`);
        closeModal();
      } catch (e) {
        if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
      }
    });
  });
}

export function openRenameModal(id, currentName) {
  openModal(`
    <h3>Rename id ${id}</h3>
    <label>New name</label>
    <input id="rm-name" type="text" maxlength="70" value="${escapeHtml(currentName)}">
    <div id="rm-error" class="modal-error"></div>
    <div class="modal-actions">
      <button id="rm-cancel" type="button">Cancel</button>
      <button id="rm-save" type="button">Save</button>
    </div>
  `, (root) => {
    root.querySelector('#rm-cancel').addEventListener('click', closeModal);
    root.querySelector('#rm-save').addEventListener('click', async () => {
      const newName = root.querySelector('#rm-name').value.trim();
      if (!newName) { root.querySelector('#rm-error').textContent = 'Name required'; return; }
      try {
        await api.renameItem(id, newName);
        renameNode(id, newName);
        renderTree();
        toastSuccess(`Renamed id ${id}`);
        closeModal();
      } catch (e) {
        if (e instanceof ProblemError) {
          toastError(e.problem);
          if (e.problem.errorCode === 'ITEM_NOT_FOUND') { removeNode(id); renderTree(); closeModal(); }
        } else { toastError(String(e)); }
      }
    });
  });
}

export function openDeleteConfirm(id, node) {
  const cascadeNote = node.type === 'Folder'
    ? '<p><b>Warning:</b> this is a folder — descendants will cascade.</p>' : '';
  openModal(`
    <h3>Delete "${escapeHtml(node.name)}" (id ${id})?</h3>
    ${cascadeNote}
    <div class="modal-actions">
      <button id="dc-cancel" type="button">Cancel</button>
      <button id="dc-delete" type="button">Delete</button>
    </div>
  `, (root) => {
    root.querySelector('#dc-cancel').addEventListener('click', closeModal);
    root.querySelector('#dc-delete').addEventListener('click', async () => {
      try {
        await api.deleteItem(id);
        removeNode(id);
        renderTree();
        toastSuccess(`Deleted id ${id}`);
        closeModal();
      } catch (e) {
        if (e instanceof ProblemError) {
          toastError(e.problem);
          if (e.problem.errorCode === 'ITEM_NOT_FOUND') { removeNode(id); renderTree(); closeModal(); }
        } else { toastError(String(e)); }
      }
    });
  });
}

export function openEditDataModal(id, currentJson) {
  // currentJson may be null if invoked from the context menu; fetch fresh in that case
  const fetchIfNeeded = currentJson != null
    ? Promise.resolve(currentJson)
    : api.getItems([id]).then((arr) => arr?.[0]?.dataJson ?? {});
  fetchIfNeeded.then((json) => {
    openModal(`
      <h3>Edit data for id ${id}</h3>
      <label>JSON</label>
      <textarea id="ed-data">${escapeHtml(JSON.stringify(json, null, 2))}</textarea>
      <div id="ed-error" class="modal-error"></div>
      <div class="modal-actions">
        <button id="ed-cancel" type="button">Cancel</button>
        <button id="ed-save" type="button">Save</button>
      </div>
    `, (root) => {
      root.querySelector('#ed-cancel').addEventListener('click', closeModal);
      root.querySelector('#ed-save').addEventListener('click', async () => {
        const raw = root.querySelector('#ed-data').value;
        let parsed;
        try { parsed = JSON.parse(raw); }
        catch (e) { root.querySelector('#ed-error').textContent = `Invalid JSON: ${e.message}`; return; }
        try {
          await api.updateItemData(id, parsed);
          toastSuccess(`Updated data for id ${id}`);
          closeModal();
        } catch (e) {
          if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
        }
      });
    });
  }).catch((e) => {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
  });
}
