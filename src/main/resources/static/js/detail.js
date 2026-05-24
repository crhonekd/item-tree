import { state } from './state.js';
import { openEditDataModal } from './modal.js';

const FOLDER = 'Folder';

function metadataBlock(item) {
  const rows = [
    ['id', item.itemTreeId],
    ['parentId', item.parentId],
    ['type', item.type],
    ['name', item.name],
    ['lastUpdate', item.lastUpdate],
    ['lastUpdateUser', item.lastUpdateUser],
  ];
  return `<div class="metadata">${
    rows.map(([k, v]) => `<div class="k">${k}</div><div class="v">${escapeHtml(String(v ?? ''))}</div>`).join('')
  }</div>`;
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  })[c]);
}

function render(html) {
  document.getElementById('detail-root').innerHTML = html;
}

export function renderDetailFolder(item) {
  state.lastDetail = { id: item.itemTreeId, mode: 'folder', payload: item };
  const childRows = (item.children ?? []).map((c) =>
    `<tr><td>${c.itemTreeId}</td><td>${escapeHtml(c.name)}</td>
      <td>${escapeHtml(c.type)}</td><td>${escapeHtml(c.lastUpdate ?? '')}</td></tr>`).join('');
  render(`${metadataBlock(item)}
    <div class="detail-section-head"><b>Children (${(item.children ?? []).length})</b></div>
    <table>
      <thead><tr><th>id</th><th>name</th><th>type</th><th>lastUpdate</th></tr></thead>
      <tbody>${childRows || '<tr><td colspan="4">(empty)</td></tr>'}</tbody>
    </table>`);
}

export function renderDetailItem(item) {
  if (item.dataJson != null) {
    state.lastDetail = { id: item.itemTreeId, mode: 'json', payload: item };
    const json = JSON.stringify(item.dataJson, null, 2);
    render(`${metadataBlock(item)}
      <div class="detail-section-head"><b>Data (JSON)</b>
        <button id="edit-data-btn" type="button">Edit</button></div>
      <pre>${escapeHtml(json)}</pre>`);
    document.getElementById('edit-data-btn').addEventListener('click',
      () => openEditDataModal(item.itemTreeId, item.dataJson));
  } else if (item.dataXml != null) {
    state.lastDetail = { id: item.itemTreeId, mode: 'xml', payload: item };
    render(`${metadataBlock(item)}
      <div class="detail-section-head"><b>Data (XML)</b>
        <button type="button" disabled title="XML items are read-only via API">Edit</button></div>
      <pre>${escapeHtml(item.dataXml)}</pre>`);
  } else {
    state.lastDetail = { id: item.itemTreeId, mode: 'no-data', payload: item };
    render(`${metadataBlock(item)}
      <div class="detail-section-head"><i>This type has no data payload.</i></div>`);
  }
}

export function clearDetail() {
  state.lastDetail = null;
  render('(nothing selected)');
}
