import { useState, useEffect } from 'react';
import * as api from '../api';

export default function ProjectionStatus() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [rebuilding, setRebuilding] = useState(false);
  const [message, setMessage] = useState(null);

  const loadStatus = async () => {
    try {
      const data = await api.getProjectionStatus();
      setStatus(data);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadStatus();
    const interval = setInterval(loadStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  const handleRebuild = async () => {
    setRebuilding(true);
    setMessage(null);
    try {
      await api.rebuildProjections();
      setMessage({ type: 'success', text: 'Projection rebuild initiated (running on Virtual Thread).' });
      // Poll status after a brief delay
      setTimeout(loadStatus, 2000);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setRebuilding(false);
    }
  };

  if (loading) {
    return <div className="loading"><div className="spinner" /> Loading projection status...</div>;
  }

  return (
    <div>
      <div className="card" style={{ marginBottom: '20px' }}>
        <div className="card-title">
          <span className="icon">⚙️</span> CQRS Projection Status
          <span style={{ marginLeft: 'auto' }}>
            <button
              className="btn btn-primary btn-sm"
              onClick={handleRebuild}
              disabled={rebuilding}
            >
              {rebuilding ? '⏳ Rebuilding...' : '🔄 Rebuild All'}
            </button>
          </span>
        </div>

        {message && (
          <div className={`alert ${message.type === 'success' ? 'alert-success' : 'alert-error'}`}>
            {message.type === 'success' ? '✅' : '⚠️'} {message.text}
          </div>
        )}

        {status && (
          <>
            <div className="info-card" style={{ marginBottom: '16px', textAlign: 'center' }}>
              <div className="info-label">Total Events in Store</div>
              <div className="info-value" style={{ color: 'var(--accent)' }}>
                {status.totalEventsInStore}
              </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {status.projections && status.projections.map((proj, i) => (
                <div className="projection-card" key={i}>
                  <div>
                    <div className="projection-name">{proj.name}</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '2px' }}>
                      Last processed: #{proj.lastProcessedEventNumberGlobal}
                    </div>
                  </div>
                  <div className={`projection-lag ${proj.lag === 0 ? 'lag-zero' : 'lag-some'}`}>
                    {proj.lag === 0 ? '✅ Up to date' : `⏳ Lag: ${proj.lag} events`}
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      <div className="card">
        <div className="card-title">
          <span className="icon">ℹ️</span> How it Works
        </div>
        <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', lineHeight: '1.7' }}>
          <p style={{ marginBottom: '8px' }}>
            <strong style={{ color: 'var(--text-primary)' }}>Event Sourcing:</strong> Every state change 
            (create, deposit, withdraw, close) is stored as an immutable event. The current account 
            state is reconstructed by replaying these events.
          </p>
          <p style={{ marginBottom: '8px' }}>
            <strong style={{ color: 'var(--text-primary)' }}>CQRS:</strong> Commands (writes) and 
            Queries (reads) are handled by separate models. The projections above maintain denormalized 
            read views optimized for fast queries.
          </p>
          <p>
            <strong style={{ color: 'var(--text-primary)' }}>Virtual Threads:</strong> Projection 
            rebuilds run asynchronously on Java 21 Virtual Threads (Project Loom), demonstrating 
            lightweight concurrency for I/O-bound tasks.
          </p>
        </div>
      </div>
    </div>
  );
}
