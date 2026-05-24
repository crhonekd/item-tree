import { state } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError, toastSuccess } from './toast.js';

export async function runRefresh(type) {
  const status = document.getElementById('refresh-status');
  status.textContent = `${type}: running…`;
  try {
    const result = await api.refresh(type);
    state.lastRefresh = { type, result, when: new Date().toISOString() };
    status.textContent = `last: ${type} ${JSON.stringify(result)} ${state.lastRefresh.when}`;
    toastSuccess(`Refresh ${type} done`);
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
    status.textContent = `last: ${type} FAILED`;
  }
}

export async function runProbe() {
  const target = document.getElementById('probe-result');
  target.className = 'probe-result';
  target.textContent = '';
  if (!state.iceUser) {
    target.className = 'probe-result err';
    target.textContent = '(set X-Ice-User first)';
    return;
  }
  try {
    const home = await api.getHomeFolder(state.iceUser);
    target.className = 'probe-result ok';
    target.textContent = `home: id=${home.itemTreeId} name="${home.name}" type=${home.type}`;
  } catch (e) {
    target.className = 'probe-result err';
    target.textContent = e instanceof ProblemError
      ? `${e.problem.status ?? '?'} ${e.problem.errorCode ?? e.problem.title}: ${e.problem.detail ?? ''}`
      : String(e);
  }
}
