import { useState } from 'react';
import Dashboard from './components/Dashboard';
import CreateAccount from './components/CreateAccount';
import AccountView from './components/AccountView';
import ProjectionStatus from './components/ProjectionStatus';

export default function App() {
  const [activeTab, setActiveTab] = useState('dashboard');
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);

  const refresh = () => setRefreshKey((k) => k + 1);

  const viewAccount = (id) => {
    setSelectedAccountId(id);
    setActiveTab('account');
  };

  return (
    <div className="app">
      <header className="header">
        <h1>🏦 Bank Account Management</h1>
        <div className="header-badges">
          <span className="badge badge-es">Event Sourcing</span>
          <span className="badge badge-cqrs">CQRS</span>
          <span className="badge badge-java">Java 21</span>
        </div>
      </header>

      <main className="main">
        <div className="tabs">
          <button
            className={`tab ${activeTab === 'dashboard' ? 'active' : ''}`}
            onClick={() => setActiveTab('dashboard')}
          >
            📊 Dashboard
          </button>
          <button
            className={`tab ${activeTab === 'create' ? 'active' : ''}`}
            onClick={() => setActiveTab('create')}
          >
            ➕ Create Account
          </button>
          <button
            className={`tab ${activeTab === 'account' ? 'active' : ''}`}
            onClick={() => setActiveTab('account')}
          >
            🔍 Account View
          </button>
          <button
            className={`tab ${activeTab === 'projections' ? 'active' : ''}`}
            onClick={() => setActiveTab('projections')}
          >
            ⚙️ Projections
          </button>
        </div>

        {activeTab === 'dashboard' && (
          <Dashboard key={refreshKey} onViewAccount={viewAccount} />
        )}

        {activeTab === 'create' && (
          <CreateAccount
            onCreated={(id) => {
              refresh();
              viewAccount(id);
            }}
          />
        )}

        {activeTab === 'account' && (
          <AccountView
            accountId={selectedAccountId}
            onAccountIdChange={setSelectedAccountId}
            onRefresh={refresh}
          />
        )}

        {activeTab === 'projections' && <ProjectionStatus />}
      </main>
    </div>
  );
}
