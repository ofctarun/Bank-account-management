import { useState, useEffect } from 'react';
import * as api from '../api';

export default function AccountView({ accountId, onAccountIdChange, onRefresh }) {
  const [localId, setLocalId] = useState(accountId || '');
  const [account, setAccount] = useState(null);
  const [events, setEvents] = useState([]);
  const [transactions, setTransactions] = useState(null);
  const [txPage, setTxPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState(null);

  // Action form state
  const [action, setAction] = useState('deposit');
  const [actionForm, setActionForm] = useState({
    amount: '',
    description: '',
    reason: '',
  });

  useEffect(() => {
    if (accountId) {
      setLocalId(accountId);
      loadAccount(accountId);
    }
  }, [accountId]);

  const loadAccount = async (id) => {
    if (!id) return;
    setLoading(true);
    setError('');
    try {
      const [acc, evts, txs] = await Promise.all([
        api.getAccount(id),
        api.getEvents(id).catch(() => []),
        api.getTransactions(id, 1, 10).catch(() => null),
      ]);
      setAccount(acc);
      setEvents(evts);
      setTransactions(txs);
      setTxPage(1);
    } catch (err) {
      setError(err.message);
      setAccount(null);
      setEvents([]);
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = () => {
    onAccountIdChange(localId.trim());
    loadAccount(localId.trim());
  };

  const loadTxPage = async (page) => {
    try {
      const txs = await api.getTransactions(localId, page, 10);
      setTransactions(txs);
      setTxPage(page);
    } catch (err) {
      console.error(err);
    }
  };

  const handleAction = async (e) => {
    e.preventDefault();
    setMessage(null);
    const txId = crypto.randomUUID();
    try {
      if (action === 'deposit') {
        await api.deposit(localId, actionForm.amount, actionForm.description, txId);
        setMessage({ type: 'success', text: `Deposited $${actionForm.amount} successfully!` });
      } else if (action === 'withdraw') {
        await api.withdraw(localId, actionForm.amount, actionForm.description, txId);
        setMessage({ type: 'success', text: `Withdrew $${actionForm.amount} successfully!` });
      } else if (action === 'close') {
        await api.closeAccount(localId, actionForm.reason || 'Closed by user');
        setMessage({ type: 'success', text: 'Account closed successfully!' });
      }
      setActionForm({ amount: '', description: '', reason: '' });
      onRefresh();
      await loadAccount(localId);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    }
  };

  return (
    <div>
      {/* Search Bar */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '20px' }}>
        <input
          type="text"
          placeholder="Enter Account ID..."
          value={localId}
          onChange={(e) => setLocalId(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          style={{
            flex: 1, padding: '10px 14px', borderRadius: 'var(--radius-sm)',
            border: '1px solid var(--border)', background: 'var(--bg-input)',
            color: 'var(--text-primary)', fontFamily: 'var(--font)', fontSize: '0.9rem', outline: 'none',
          }}
        />
        <button className="btn btn-primary" onClick={handleSearch}>Load Account</button>
      </div>

      {error && <div className="alert alert-error">⚠️ {error}</div>}
      {loading && <div className="loading"><div className="spinner" /> Loading account...</div>}

      {account && (
        <>
          {message && (
            <div className={`alert ${message.type === 'success' ? 'alert-success' : 'alert-error'}`}>
              {message.type === 'success' ? '✅' : '⚠️'} {message.text}
            </div>
          )}

          {/* Account Summary */}
          <div className="account-info">
            <div className="info-card">
              <div className="info-label">Owner</div>
              <div className="info-value" style={{ fontSize: '1.1rem' }}>{account.ownerName}</div>
            </div>
            <div className="info-card">
              <div className="info-label">Balance</div>
              <div className="info-value balance">
                {account.currency} {parseFloat(account.balance).toFixed(2)}
              </div>
            </div>
            <div className="info-card">
              <div className="info-label">Currency</div>
              <div className="info-value" style={{ fontSize: '1.1rem', color: 'var(--info)' }}>
                {account.currency}
              </div>
            </div>
            <div className="info-card">
              <div className="info-label">Status</div>
              <div className={`info-value ${account.status === 'OPEN' ? 'status-open' : 'status-closed'}`}>
                {account.status}
              </div>
            </div>
          </div>

          <div className="grid-2">
            {/* Actions Panel */}
            <div className="card">
              <div className="card-title">
                <span className="icon">⚡</span> Account Actions
              </div>

              {account.status === 'OPEN' ? (
                <>
                  <div style={{ display: 'flex', gap: '6px', marginBottom: '16px' }}>
                    <button
                      className={`btn btn-sm ${action === 'deposit' ? 'btn-success' : 'btn-secondary'}`}
                      onClick={() => setAction('deposit')}
                    >
                      💰 Deposit
                    </button>
                    <button
                      className={`btn btn-sm ${action === 'withdraw' ? 'btn-danger' : 'btn-secondary'}`}
                      onClick={() => setAction('withdraw')}
                    >
                      💸 Withdraw
                    </button>
                    <button
                      className={`btn btn-sm ${action === 'close' ? 'btn-danger' : 'btn-secondary'}`}
                      onClick={() => setAction('close')}
                    >
                      🔒 Close
                    </button>
                  </div>

                  <form onSubmit={handleAction}>
                    {(action === 'deposit' || action === 'withdraw') && (
                      <div className="form-grid">
                        <div className="form-group">
                          <label>Amount</label>
                          <input
                            type="number" step="0.01" min="0.01" required
                            placeholder="100.00"
                            value={actionForm.amount}
                            onChange={(e) => setActionForm({ ...actionForm, amount: e.target.value })}
                          />
                        </div>
                        <div className="form-group">
                          <label>Description</label>
                          <input
                            type="text" placeholder="Optional note"
                            value={actionForm.description}
                            onChange={(e) => setActionForm({ ...actionForm, description: e.target.value })}
                          />
                        </div>
                        <div className="form-group full">
                          <button type="submit" className={`btn ${action === 'deposit' ? 'btn-success' : 'btn-danger'}`}>
                            {action === 'deposit' ? '💰 Deposit Funds' : '💸 Withdraw Funds'}
                          </button>
                        </div>
                      </div>
                    )}
                    {action === 'close' && (
                      <div className="form-grid">
                        <div className="form-group full">
                          <label>Reason</label>
                          <input
                            type="text" placeholder="Reason for closing"
                            value={actionForm.reason}
                            onChange={(e) => setActionForm({ ...actionForm, reason: e.target.value })}
                          />
                        </div>
                        <div className="form-group full">
                          <button type="submit" className="btn btn-danger">🔒 Close Account</button>
                        </div>
                      </div>
                    )}
                  </form>
                </>
              ) : (
                <div className="alert alert-info">ℹ️ This account is closed. No further actions allowed.</div>
              )}
            </div>

            {/* Event Stream Panel */}
            <div className="card">
              <div className="card-title">
                <span className="icon">📜</span> Event Stream
                <span style={{ marginLeft: 'auto', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                  {events.length} events
                </span>
              </div>

              {events.length > 0 ? (
                <div className="event-list">
                  {events.map((evt, i) => (
                    <div className="event-item" key={i}>
                      <div className="event-number">{evt.eventNumber}</div>
                      <div>
                        <div className={`event-type ${evt.eventType}`}>{evt.eventType}</div>
                      </div>
                      <div className="event-time">
                        {new Date(evt.timestamp).toLocaleString()}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="empty-state">
                  <p>No events yet</p>
                </div>
              )}
            </div>
          </div>

          {/* Transaction History */}
          {transactions && transactions.items && transactions.items.length > 0 && (
            <div className="card" style={{ marginTop: '20px' }}>
              <div className="card-title">
                <span className="icon">📋</span> Transaction History
                <span style={{ marginLeft: 'auto', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                  {transactions.totalCount} total
                </span>
              </div>

              <div className="table-wrapper">
                <table>
                  <thead>
                    <tr>
                      <th>Type</th>
                      <th>Amount</th>
                      <th>Description</th>
                      <th>Date</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.items.map((tx, i) => (
                      <tr key={i}>
                        <td className={tx.type === 'DEPOSIT' ? 'type-deposit' : 'type-withdrawal'}>
                          {tx.type === 'DEPOSIT' ? '↗ ' : '↘ '}{tx.type}
                        </td>
                        <td>{parseFloat(tx.amount).toFixed(2)}</td>
                        <td style={{ color: 'var(--text-secondary)' }}>{tx.description || '—'}</td>
                        <td style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>
                          {new Date(tx.timestamp).toLocaleString()}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {transactions.totalPages > 1 && (
                <div className="pagination">
                  <button
                    className="btn btn-secondary btn-sm"
                    disabled={txPage <= 1}
                    onClick={() => loadTxPage(txPage - 1)}
                  >
                    ← Prev
                  </button>
                  <span>Page {txPage} of {transactions.totalPages}</span>
                  <button
                    className="btn btn-secondary btn-sm"
                    disabled={txPage >= transactions.totalPages}
                    onClick={() => loadTxPage(txPage + 1)}
                  >
                    Next →
                  </button>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
