import { useState } from 'react';
import type { CmsPage, CmsPageRequest, StatusKind } from './types';
import { api, ApiError } from './services/api';
import { LoginOverlay } from './components/LoginOverlay';
import { PagesTab } from './components/PagesTab';

interface Credentials {
  username: string;
  password: string;
}

export default function App() {
  const [creds, setCreds] = useState<Credentials | null>(null);
  const [status, setStatus] = useState<{ text: string; kind: StatusKind }>({
    text: 'Ready',
    kind: 'idle',
  });

  const [pages, setPages] = useState<CmsPage[]>([]);

  function handleApiError(err: unknown, _context: string) {
    if (err instanceof ApiError && err.status === 401) {
      setStatus({ text: 'Session expired', kind: 'error' });
      setCreds(null);
    } else {
      const msg = err instanceof Error ? err.message : String(err);
      setStatus({ text: msg, kind: 'error' });
    }
  }

  async function handleLogin(username: string, password: string) {
    const newCreds = { username, password };
    setStatus({ text: 'Authenticating…', kind: 'pending' });
    try {
      const result = await api.getPages(newCreds);
      setCreds(newCreds);
      setPages(result);
      setStatus({ text: 'Authenticated', kind: 'success' });
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
  }

  async function loadPages() {
    if (!creds) return;
    setStatus({ text: 'Loading…', kind: 'pending' });
    try {
      const result = await api.getPages(creds);
      setPages(result);
      setStatus({ text: 'Authenticated', kind: 'success' });
    } catch (err) {
      handleApiError(err, 'Failed to load pages');
    }
  }

  async function handleCreatePage(payload: CmsPageRequest) {
    if (!creds) return;
    setStatus({ text: 'Creating…', kind: 'pending' });
    try {
      await api.createPage(creds, payload);
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadPages();
    } catch (err) {
      handleApiError(err, 'Failed to create page');
      throw err;
    }
  }

  async function handleDeletePage(id: string) {
    if (!creds) return;
    setStatus({ text: 'Deleting…', kind: 'pending' });
    try {
      await api.deletePage(creds, id);
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadPages();
    } catch (err) {
      handleApiError(err, 'Failed to delete page');
    }
  }

  return (
    <>
      {!creds && <LoginOverlay onLogin={handleLogin} />}

      <div className="app-shell">
        <header>
          <div className="icon-box">📄</div>
          <div>
            <h1>CMS Admin</h1>
            <p>Manage CMS pages</p>
          </div>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: '.75rem', alignItems: 'center' }}>
            <span className={`status-badge ${status.kind}`}>{status.text}</span>
            <button className="btn btn-secondary btn-sm" onClick={handleLogout}>
              Logout
            </button>
          </div>
        </header>

        <div className="card">
          <PagesTab
            pages={pages}
            onRefresh={() => void loadPages()}
            onDelete={(id) => void handleDeletePage(id)}
            onCreate={handleCreatePage}
            count={pages.length}
          />
        </div>
      </div>
    </>
  );
}
