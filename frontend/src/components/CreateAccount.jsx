import { useState } from 'react';
import * as api from '../api';

export default function CreateAccount({ onCreated }) {
  const [form, setForm] = useState({
    accountId: '',
    ownerName: '',
    initialBalance: '0',
    currency: 'USD',
  });
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState(null);

  const update = (field) => (e) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setMessage(null);
    try {
      await api.createAccount(
        form.accountId,
        form.ownerName,
        form.initialBalance,
        form.currency
      );
      setMessage({ type: 'success', text: `Account "${form.accountId}" created successfully!` });
      onCreated(form.accountId);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  const generateId = () => {
    const id = 'ACC-' + crypto.randomUUID().slice(0, 8).toUpperCase();
    setForm((prev) => ({ ...prev, accountId: id }));
  };

  return (
    <div className="card">
      <div className="card-title">
        <span className="icon">➕</span>
        Create New Account
      </div>

      {message && (
        <div className={`alert ${message.type === 'success' ? 'alert-success' : 'alert-error'}`}>
          {message.type === 'success' ? '✅' : '⚠️'} {message.text}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div className="form-grid">
          <div className="form-group">
            <label>Account ID</label>
            <div style={{ display: 'flex', gap: '6px' }}>
              <input
                type="text"
                placeholder="e.g. ACC-001"
                value={form.accountId}
                onChange={update('accountId')}
                required
                style={{ flex: 1 }}
              />
              <button type="button" className="btn btn-secondary btn-sm" onClick={generateId}>
                🎲
              </button>
            </div>
          </div>

          <div className="form-group">
            <label>Owner Name</label>
            <input
              type="text"
              placeholder="e.g. John Doe"
              value={form.ownerName}
              onChange={update('ownerName')}
              required
            />
          </div>

          <div className="form-group">
            <label>Initial Balance</label>
            <input
              type="number"
              step="0.01"
              min="0"
              value={form.initialBalance}
              onChange={update('initialBalance')}
            />
          </div>

          <div className="form-group">
            <label>Currency</label>
            <select value={form.currency} onChange={update('currency')}>
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
              <option value="INR">INR</option>
            </select>
          </div>

          <div className="form-group full" style={{ marginTop: '8px' }}>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? '⏳ Creating...' : '🏦 Create Account'}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
