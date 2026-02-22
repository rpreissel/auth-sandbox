import { useState } from 'react';
import type { Device, RegistrationCode, StatusKind, SyncResult } from './types';
import { api, ApiError } from './services/api';
import { useActivityLog } from './hooks/useActivityLog';
import { LoginOverlay } from './components/LoginOverlay';
import { StatusBadge } from './components/StatusBadge';
import { RegCodesTab } from './components/RegCodesTab';
import { DevicesTab } from './components/DevicesTab';
import { ActivityLog } from './components/ActivityLog';

type TabId = 'reg-codes' | 'devices';

interface Credentials {
  username: string;
  password: string;
}

export default function App() {
  const [creds, setCreds] = useState<Credentials | null>(null);
  const [activeTab, setActiveTab] = useState<TabId>('reg-codes');
  const [status, setStatus] = useState<{ text: string; kind: StatusKind }>({
    text: 'Ready',
    kind: 'idle',
  });

  const [codes, setCodes] = useState<RegistrationCode[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);

  const { entries, addEntry, clearEntries } = useActivityLog();

  function handleApiError(err: unknown, context: string) {
    if (err instanceof ApiError && err.status === 401) {
      addEntry(`${context}: session expired — please log in again.`, 'err');
      setCreds(null);
      setStatus({ text: 'Ready', kind: 'idle' });
    } else {
      const msg = err instanceof Error ? err.message : String(err);
      addEntry(`${context}: ${msg}`, 'err');
      setStatus({ text: 'Error', kind: 'error' });
    }
  }

  async function handleLogin(username: string, password: string) {
    const newCreds = { username, password };
    setStatus({ text: 'Authenticating…', kind: 'pending' });
    try {
      const result = await api.getRegistrationCodes(newCreds);
      setCreds(newCreds);
      setCodes(result);
      addEntry(`Logged in as '${username}'`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      // also load devices in background
      api
        .getDevices(newCreds)
        .then(setDevices)
        .catch(err => handleApiError(err, 'Failed to load devices'));
    } catch (err) {
      setStatus({ text: 'Ready', kind: 'idle' });
      if (err instanceof ApiError && err.status === 401) {
        throw new Error('Invalid username or password.');
      }
      throw err;
    }
  }

  function handleLogout() {
    setCreds(null);
    setStatus({ text: 'Ready', kind: 'idle' });
    addEntry('Logged out.', 'warn');
  }

  async function loadCodes() {
    if (!creds) return;
    setStatus({ text: 'Loading…', kind: 'pending' });
    try {
      const result = await api.getRegistrationCodes(creds);
      setCodes(result);
      addEntry(`Loaded ${result.length} registration code(s).`, 'info');
      setStatus({ text: 'Authenticated', kind: 'success' });
    } catch (err) {
      handleApiError(err, 'Failed to load registration codes');
    }
  }

  async function loadDevices() {
    if (!creds) return;
    setStatus({ text: 'Loading…', kind: 'pending' });
    try {
      const result = await api.getDevices(creds);
      setDevices(result);
      addEntry(`Loaded ${result.length} device(s).`, 'info');
      setStatus({ text: 'Authenticated', kind: 'success' });
    } catch (err) {
      handleApiError(err, 'Failed to load devices');
    }
  }

  async function handleCreateCode(userId: string, name: string, activationCode: string) {
    if (!creds) return;
    setStatus({ text: 'Creating…', kind: 'pending' });
    try {
      const created = await api.createRegistrationCode(creds, { userId, name, activationCode });
      addEntry(`Created registration code for userId='${created.userId}'.`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes();
    } catch (err) {
      handleApiError(err, 'Failed to create registration code');
      throw err;
    }
  }

  async function handleSyncRegistrationCodes() {
    if (!creds) return;
    setStatus({ text: 'Syncing…', kind: 'pending' });
    try {
      const result: SyncResult = await api.syncRegistrationCodes(creds);
      addEntry(
        `Sync complete — synced: ${result.synced}, already synced: ${result.alreadySynced}, failed: ${result.failed}.`,
        result.failed > 0 ? 'warn' : 'ok',
      );
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes();
    } catch (err) {
      handleApiError(err, 'Failed to sync Keycloak users');
    }
  }

  async function handleDeleteCode(id: string, userId: string) {
    if (!creds) return;
    setStatus({ text: 'Deleting…', kind: 'pending' });
    try {
      await api.deleteRegistrationCode(creds, id);
      addEntry(`Deleted registration code id='${id}' (userId='${userId}').`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes();
    } catch (err) {
      handleApiError(err, 'Failed to delete registration code');
    }
  }

  async function handleDeleteDevice(id: string, userId: string) {
    if (!creds) return;
    setStatus({ text: 'Deleting…', kind: 'pending' });
    try {
      await api.deleteDevice(creds, id);
      addEntry(`Deleted device id='${id}' (userId='${userId}').`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadDevices();
    } catch (err) {
      handleApiError(err, 'Failed to delete device');
    }
  }

  function handleTabChange(tab: TabId) {
    setActiveTab(tab);
    if (tab === 'devices') void loadDevices();
    if (tab === 'reg-codes') void loadCodes();
  }

  return (
    <>
      {!creds && <LoginOverlay onLogin={handleLogin} />}

      <div className="app-shell">
        <header>
          <div className="icon-box">🛡️</div>
          <div>
            <h1>Device Auth Admin</h1>
            <p>Manage registration codes and registered devices</p>
          </div>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: '.75rem', alignItems: 'center' }}>
            <StatusBadge text={status.text} kind={status.kind} />
            <button className="btn btn-secondary btn-sm" onClick={handleLogout}>
              Logout
            </button>
          </div>
        </header>

        <div className="card">
          <div className="tabs">
            {(['reg-codes', 'devices'] as TabId[]).map(tab => (
              <div
                key={tab}
                className={`tab${activeTab === tab ? ' active' : ''}`}
                onClick={() => handleTabChange(tab)}
              >
                {tab === 'reg-codes' ? 'Registration Codes' : 'Devices'}
              </div>
            ))}
          </div>

          <div className="tab-pane active">
            {activeTab === 'reg-codes' ? (
              <RegCodesTab
                codes={codes}
                onRefresh={() => void loadCodes()}
                onDelete={(id, userId) => void handleDeleteCode(id, userId)}
                onCreate={handleCreateCode}
                onSync={handleSyncRegistrationCodes}
                count={codes.length}
              />
            ) : (
              <DevicesTab
                devices={devices}
                onRefresh={() => void loadDevices()}
                onDelete={(id, userId) => void handleDeleteDevice(id, userId)}
                count={devices.length}
              />
            )}
          </div>
        </div>

        <ActivityLog entries={entries} onClear={clearEntries} />
      </div>
    </>
  );
}
