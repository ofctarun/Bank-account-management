import { useState, useEffect } from 'react';
import * as api from '../api';

export default function Dashboard({ onViewAccount }) {
  const [accountId, setAccountId] = useState('');
  const [account, setAccount] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [health, setHealth] = useState(null);

  useEffect(() => {
    api.healthCheck()
      .then(setHealth)
      .catch(() => setHealth(null));
  }, []);

  const lookup = async () => {
    if (!accountId.trim()) return;
    setLoading(true);
    setError('');
    setAccount(null);
    try {
      const data = await api.getAccount(accountId.trim());
      setAccount(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      {/* Health Status */}
      <div className="grid-3">
        <div className="info-card">
          <div className="info-label">API Status</div>
          <div className={`info-value ${health ? 'status-open' : 'status-closed'}`}>
            {health ? '● Online' : '○ Offline'}
          </div>
        </div>
        <div className="info-card">
          <div className="info-label">Architecture</div>
          <div className="info-value" style={{ fontSize: '1rem', color: 'var(--accent)' }}>
            Event Sourcing + CQRS
          </div>
        </div>
        <div className="info-card">
          <div className="info-label">Runtime</div>
          <div className="info-value" style={{ fontSize: '1rem', color: 'var(--warning)' }}>
            Java 21 + Virtual Threads
          </div>
        </div>
      </div>

      {/* Quick Lookup */}
      <div className="card">
        <div className="card-title">
          <span className="icon">🔍</span>
          Quick Account Lookup
        </div>

        <div style={{ display: 'flex', gap: '8px', marginBottom: '16px' }}>
          <input
            type="text"
            placeholder="Enter Account ID..."
            value={accountId}
            onChange={(e) => setAccountId(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && lookup()}
            style={{
              flex: 1,
              padding: '10px 14px',
              borderRadius: 'var(--radius-sm)',
              border: '1px solid var(--border)',
              background: 'var(--bg-input)',
              color: 'var(--text-primary)',
              fontFamily: 'var(--font)',
              fontSize: '0.9rem',
              outline: 'none',
            }}
          />
          <button className="btn btn-primary" onClick={lookup} disabled={loading}>
            {loading ? 'Searching...' : 'Search'}
          </button>
          {account && (
            <button className="btn btn-secondary" onClick={() => onViewAccount(accountId.trim())}>
              View Details →
            </button>
          )}
        </div>

        {error && <div className="alert alert-error">⚠️ {error}</div>}

        {account && (
          <div className="account-info">
            <div className="info-card">
              <div className="info-label">Account ID</div>
              <div className="info-value" style={{ fontSize: '0.95rem', wordBreak: 'break-all' }}>
                {account.accountId}
              </div>
            </div>
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
              <div className="info-label">Status</div>
              <div className={`info-value ${account.status === 'OPEN' ? 'status-open' : 'status-closed'}`}>
                {account.status}
              </div>
            </div>
          </div>
        )}

        {!account && !error && !loading && (
          <div className="empty-state">
            <div className="emoji">🏦</div>
            <p>Enter an Account ID to look up account details</p>
          </div>
        )}
      </div>
    </div>
  );
}
