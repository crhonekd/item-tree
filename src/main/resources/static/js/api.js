import { state } from './state.js';

export class ProblemError extends Error {
  constructor(problem, status) {
    super(problem.detail || problem.title || `HTTP ${status}`);
    this.problem = problem;
    this.status = status;
  }
}

function url(path) {
  return `${state.backendBaseUrl}${path}`;
}

function buildHeaders(hasBody) {
  const h = {};
  if (state.iceUser) h['X-Ice-User'] = state.iceUser;
  if (state.impersonatedUser) h['X-Impersonated-User'] = state.impersonatedUser;
  if (hasBody) h['Content-Type'] = 'application/json';
  return h;
}

async function request(method, path, body, { retryOn503 = false } = {}) {
  const opts = { method, headers: buildHeaders(body !== undefined) };
  if (body !== undefined) opts.body = JSON.stringify(body);

  const maxAttempts = retryOn503 ? 5 : 1;
  let attempt = 0;
  while (true) {
    attempt++;
    let res;
    try {
      res = await fetch(url(path), opts);
    } catch (networkErr) {
      throw new ProblemError(
        { title: 'Network error', detail: String(networkErr) }, 0);
    }
    if (res.status === 503 && attempt < maxAttempts) {
      await new Promise((r) => setTimeout(r, 2000));
      continue;
    }
    if (!res.ok) {
      let problem = { title: res.statusText, status: res.status };
      try { problem = await res.json(); } catch { /* keep default */ }
      throw new ProblemError(problem, res.status);
    }
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }
}

export const api = {
  getHomeFolder: (userName) =>
    request('GET', `/api/v1/itemtree/users/${encodeURIComponent(userName)}/home-folder`),
  getTree: () =>
    request('GET', '/api/v1/itemtree/tree', undefined, { retryOn503: true }),
  getSubtree: (rootId) =>
    request('GET', `/api/v1/itemtree/tree/${rootId}/subtree`),
  getSubtreeFull: (rootId) =>
    request('GET', `/api/v1/itemtree/tree/${rootId}/subtree-full`),
  getItems: (ids) =>
    request('POST', '/api/v1/itemtree/items/get', { ids }),
  createItem: (body) =>
    request('POST', '/api/v1/itemtree/items', body),
  moveItem: (id, newParentId) =>
    request('POST', `/api/v1/itemtree/items/${id}/move`, { newParentId }),
  copyItem: (id, destinationFolderId) =>
    request('POST', `/api/v1/itemtree/items/${id}/copy`, { destinationFolderId }),
  renameItem: (id, newName) =>
    request('POST', `/api/v1/itemtree/items/${id}/rename`, { newName }),
  updateItemData: (id, data) =>
    request('PUT', `/api/v1/itemtree/items/${id}/data`, { data }),
  deleteItem: (id) =>
    request('DELETE', `/api/v1/itemtree/items/${id}`),
  search: ({ q, limit }) => {
    const params = new URLSearchParams();
    params.set('q', q ?? '');
    if (limit !== undefined && limit !== '') params.set('limit', String(limit));
    return request('GET', `/api/v1/itemtree/search?${params.toString()}`);
  },
  refresh: (type) =>
    request('POST', `/actuator/itemtree-refresh/${type}`),
};
