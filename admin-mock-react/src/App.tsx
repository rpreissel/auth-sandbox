import { useState, useEffect } from 'react';
import type { Device, RegistrationCode, StatusKind, SyncResult, CleanupResult } from './types';
import { api } from './services/api';
import { ApiError } from '@auth-sandbox/utils';
import { useActivityLog } from './hooks/useActivityLog';
import { LoginOverlay } from './components/LoginOverlay';
import { StatusBadge } from './components/StatusBadge';
import { RegCodesTab } from './components/RegCodesTab';
import { DevicesTab } from './components/DevicesTab';
import { ActivityLog } from './components/ActivityLog';
import {
  getTokens,
  exchangeCode,
  logout as oidcLogout,
  fetchUserinfo,
} from './services/oidc';

type TabId = 'reg-codes' | 'devices';

interface OidcTokens {
  access_token: string;
  id_token: string;
  refresh_token: string;
}

export default function App() {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [tokens, setTokens] = useState<OidcTokens | null>(null);
  const [isCallback, setIsCallback] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId>('reg-codes');
  const [status, setStatus] = useState<{ text: string; kind: StatusKind }>({
    text: 'Ready',
    kind: 'idle',
  });

  const [codes, setCodes] = useState<RegistrationCode[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);

  const { entries, addEntry, clearEntries } = useActivityLog();

  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');
    const state = urlParams.get('state');

    if (code && state) {
      setIsCallback(true);
      setStatus({ text: 'Authenticating…', kind: 'pending' });
      exchangeCode(code, state)
        .then(async (tokens) => {
          setAccessToken(tokens.access_token);
          setTokens(tokens);
          setIsCallback(false);
          const info = await fetchUserinfo(tokens.access_token);
          const username = (info['preferred_username'] as string) || (info['username'] as string) || 'unknown';
          addEntry(`Logged in as '${username}'`, 'ok');
          setStatus({ text: 'Authenticated', kind: 'success' });
          window.history.replaceState({}, '', '/');
          await loadCodes(tokens.access_token);
          await loadDevices(tokens.access_token);
        })
        .catch((err) => {
          setIsCallback(false);
          addEntry(`Login failed: ${err instanceof Error ? err.message : 'Unknown error'}`, 'err');
          setStatus({ text: 'Ready', kind: 'idle' });
        });
    } else {
      const storedTokens = getTokens();
      if (storedTokens?.access_token) {
        setAccessToken(storedTokens.access_token);
        setTokens(storedTokens);
        fetchUserinfo(storedTokens.access_token)
          .then((info) => {
            const username = (info['preferred_username'] as string) || (info['username'] as string) || 'unknown';
            addEntry(`Session restored for '${username}'`, 'info');
          })
          .catch(() => {
            addEntry('Session expired — please log in again.', 'warn');
            setAccessToken(null);
          });
      }
    }
  }, []);

  function handleApiError(err: unknown, context: string) {
    if (err instanceof ApiError && err.status === 401) {
      addEntry(`${context}: session expired — please log in again.`, 'err');
      setAccessToken(null);
      setStatus({ text: 'Ready', kind: 'idle' });
    } else {
      const msg = err instanceof Error ? err.message : String(err);
      addEntry(`${context}: ${msg}`, 'err');
      setStatus({ text: 'Error', kind: 'error' });
    }
  }

  function handleLogout() {
    setAccessToken(null);
    setTokens(null);
    setStatus({ text: 'Ready', kind: 'idle' });
    if (tokens?.refresh_token) {
      oidcLogout(tokens.refresh_token, tokens.id_token).catch(() => {});
    } else {
      addEntry('Logged out.', 'warn');
    }
  }

  async function loadCodes(token: string) {
    setStatus({ text: 'Loading…', kind: 'pending' });
    try {
      const result = await api.getRegistrationCodes(token);
      setCodes(result);
      addEntry(`Loaded ${result.length} registration code(s).`, 'info');
      setStatus({ text: 'Authenticated', kind: 'success' });
    } catch (err) {
      handleApiError(err, 'Failed to load registration codes');
    }
  }

  async function loadDevices(token: string) {
    setStatus({ text: 'Loading…', kind: 'pending' });
    try {
      const result = await api.getDevices(token);
      setDevices(result);
      addEntry(`Loaded ${result.length} device(s).`, 'info');
      setStatus({ text: 'Authenticated', kind: 'success' });
    } catch (err) {
      handleApiError(err, 'Failed to load devices');
    }
  }

  async function handleCreateCode(userId: string, name: string, activationCode: string) {
    if (!accessToken) return;
    setStatus({ text: 'Creating…', kind: 'pending' });
    try {
      const created = await api.createRegistrationCode(accessToken, { userId, name, activationCode });
      addEntry(`Created registration code for userId='${created.userId}'.`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to create registration code');
      throw err;
    }
  }

  async function handleSyncRegistrationCodes() {
    if (!accessToken) return;
    setStatus({ text: 'Syncing…', kind: 'pending' });
    try {
      const result: SyncResult = await api.syncRegistrationCodes(accessToken);
      addEntry(
        `Sync complete — synced: ${result.synced}, already synced: ${result.alreadySynced}, failed: ${result.failed}.`,
        result.failed > 0 ? 'warn' : 'ok',
      );
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to sync Keycloak users');
    }
  }

  async function handleCleanupExpiredCodes() {
    if (!accessToken) return;
    if (!confirm('Delete all expired registration codes (and their Keycloak users)?')) return;
    setStatus({ text: 'Cleaning up…', kind: 'pending' });
    try {
      const result: CleanupResult = await api.cleanupExpiredCodes(accessToken);
      addEntry(
        `Cleanup complete — deleted: ${result.deleted}, skipped (has devices): ${result.skipped}.`,
        result.deleted > 0 ? 'ok' : 'info',
      );
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to cleanup expired codes');
    }
  }

  async function handleDeleteCode(id: string, userId: string) {
    if (!accessToken) return;
    setStatus({ text: 'Deleting…', kind: 'pending' });
    try {
      await api.deleteRegistrationCode(accessToken, id);
      addEntry(`Deleted registration code id='${id}' (userId='${userId}').`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadCodes(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to delete registration code');
    }
  }

  async function handleDeleteDevice(id: string, userId: string) {
    if (!accessToken) return;
    setStatus({ text: 'Deleting…', kind: 'pending' });
    try {
      await api.deleteDevice(accessToken, id);
      addEntry(`Deleted device id='${id}' (userId='${userId}').`, 'ok');
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadDevices(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to delete device');
    }
  }

  function handleTabChange(tab: TabId) {
    setActiveTab(tab);
    if (tab === 'devices' && accessToken) void loadDevices(accessToken);
    if (tab === 'reg-codes' && accessToken) void loadCodes(accessToken);
  }

  if (isCallback) {
    return (
      <div id="login-overlay">
        <div className="login-box">
          <div style={{ fontSize: '2rem', marginBottom: '.75rem' }}>⏳</div>
          <h2>Authenticating…</h2>
          <p>Please wait while we complete your login.</p>
        </div>
      </div>
    );
  }

  return (
    <>
      {!accessToken && <LoginOverlay />}

      <div className="app-shell">
        <header>
          <div className="icon-box">🛡️</div>
          <div>
            <h1>Device Auth Admin</h1>
            <p>Manage registration codes and registered devices</p>
          </div>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: '.75rem', alignItems: 'center' }}>
            <StatusBadge text={status.text} kind={status.kind} />
            {accessToken && (
              <button className="btn btn-secondary btn-sm" onClick={handleLogout}>
                Logout
              </button>
            )}
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
                onRefresh={() => accessToken && loadCodes(accessToken)}
                onDelete={(id, userId) => accessToken && handleDeleteCode(id, userId)}
                onCreate={handleCreateCode}
                onSync={handleSyncRegistrationCodes}
                onCleanup={handleCleanupExpiredCodes}
                count={codes.length}
              />
            ) : (
              <DevicesTab
                devices={devices}
                onRefresh={() => accessToken && loadDevices(accessToken)}
                onDelete={(id, userId) => accessToken && handleDeleteDevice(id, userId)}
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
