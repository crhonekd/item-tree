const container = () => document.getElementById('toast-container');

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  })[c]);
}

export function toastSuccess(message) {
  const el = document.createElement('div');
  el.className = 'toast success';
  el.innerHTML = `<span class="toast-close">×</span>${escapeHtml(message)}`;
  el.querySelector('.toast-close').addEventListener('click', () => el.remove());
  container().appendChild(el);
  setTimeout(() => el.remove(), 2000);
}

export function toastError(problemOrMessage) {
  const p = typeof problemOrMessage === 'string'
    ? { title: 'Error', detail: problemOrMessage }
    : problemOrMessage;
  const el = document.createElement('div');
  el.className = 'toast error';
  const head = `${escapeHtml(p.title ?? 'Error')}`
    + (p.status ? ` · <b>${p.status}</b>` : '')
    + (p.errorCode ? ` · <code>${escapeHtml(p.errorCode)}</code>` : '');
  const detail = p.detail ? `<div>${escapeHtml(p.detail)}</div>` : '';
  const traceId = p.traceId ? `<div style="opacity:.7">traceId: ${escapeHtml(p.traceId)}</div>` : '';
  const raw = `<details><summary>raw</summary><pre>${escapeHtml(JSON.stringify(p, null, 2))}</pre></details>`;
  el.innerHTML = `<span class="toast-close">×</span>${head}${detail}${traceId}${raw}`;
  el.querySelector('.toast-close').addEventListener('click', () => el.remove());
  container().appendChild(el);
}
