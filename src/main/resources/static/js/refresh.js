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
