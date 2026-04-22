const API_BASE = '/api';

export async function createAccount(accountId, ownerName, initialBalance = 0, currency = 'USD') {
  const res = await fetch(`${API_BASE}/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accountId, ownerName, initialBalance: parseFloat(initialBalance), currency }),
  });
  return handleResponse(res);
}

export async function deposit(accountId, amount, description, transactionId) {
  const res = await fetch(`${API_BASE}/accounts/${accountId}/deposit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount: parseFloat(amount), description, transactionId }),
  });
  return handleResponse(res);
}

export async function withdraw(accountId, amount, description, transactionId) {
  const res = await fetch(`${API_BASE}/accounts/${accountId}/withdraw`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount: parseFloat(amount), description, transactionId }),
  });
  return handleResponse(res);
}

export async function closeAccount(accountId, reason) {
  const res = await fetch(`${API_BASE}/accounts/${accountId}/close`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  return handleResponse(res);
}

export async function getAccount(accountId) {
  const res = await fetch(`${API_BASE}/accounts/${accountId}`);
  return handleResponse(res);
}

export async function getEvents(accountId) {
  const res = await fetch(`${API_BASE}/accounts/${accountId}/events`);
  return handleResponse(res);
}

export async function getBalanceAt(accountId, timestamp) {
  const encoded = encodeURIComponent(timestamp);
  const res = await fetch(`${API_BASE}/accounts/${accountId}/balance-at/${encoded}`);
  return handleResponse(res);
}

export async function getTransactions(accountId, page = 1, pageSize = 10) {
  const res = await fetch(`${API_BASE}/accounts/${accountId}/transactions?page=${page}&pageSize=${pageSize}`);
  return handleResponse(res);
}

export async function getProjectionStatus() {
  const res = await fetch(`${API_BASE}/projections/status`);
  return handleResponse(res);
}

export async function rebuildProjections() {
  const res = await fetch(`${API_BASE}/projections/rebuild`, { method: 'POST' });
  return handleResponse(res);
}

export async function healthCheck() {
  const res = await fetch('/health');
  return handleResponse(res);
}

async function handleResponse(res) {
  const data = await res.json();
  if (!res.ok) {
    throw new Error(data.error || `HTTP ${res.status}`);
  }
  return data;
}
