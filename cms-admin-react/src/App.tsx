import { useState, useEffect } from 'react';
import type { CmsPage, CmsPageRequest, StatusKind, OidcTokens } from './types';
import { api, ApiError } from './services/api';
import { LoginOverlay } from './components/LoginOverlay';
import { PagesTab } from './components/PagesTab';
import { getTokens, exchangeCode, logout as oidcLogout, buildAuthUrl } from './services/oidc';

const TOKEN_PROXY_PATH = '/api/token';

export default function App() {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [tokens, setTokens] = useState<OidcTokens | null>(null);
  const [isCallback, setIsCallback] = useState(false);
  const [status, setStatus] = useState<{ text: string; kind: StatusKind }>({
    text: 'Ready',
    kind: 'idle',
  });

  const [pages, setPages] = useState<CmsPage[]>([]);

  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');
    const state = urlParams.get('state');

    if (code && state) {
      setIsCallback(true);
      setStatus({ text: 'Authenticating…', kind: 'pending' });
      exchangeCode(code, state)
        .then((newTokens) => {
          setAccessToken(newTokens.access_token);
          setTokens(newTokens);
          setIsCallback(false);
          setStatus({ text: 'Authenticated', kind: 'success' });
          window.history.replaceState({}, '', '/');
          loadPages(newTokens.access_token);
        })
        .catch((err) => {
          setIsCallback(false);
          setStatus({ text: 'Authentication failed', kind: 'error' });
          console.error('Login failed:', err);
        });
    } else {
      const existingTokens = getTokens();
      if (existingTokens) {
        setAccessToken(existingTokens.access_token);
        setTokens(existingTokens);
        loadPages(existingTokens.access_token);
      }
    }
  }, []);

  async function loadPages(token: string) {
    setStatus({ text: 'Loading…', kind: 'pending' });
    try {
      const result = await api.getPages({ accessToken: token });
      setPages(result);
      setStatus({ text: 'Authenticated', kind: 'success' });
    } catch (err) {
      handleApiError(err, 'Failed to load pages');
    }
  }

  function handleApiError(err: unknown, _context: string) {
    if (err instanceof ApiError && err.status === 401) {
      setStatus({ text: 'Session expired', kind: 'error' });
      setAccessToken(null);
      setTokens(null);
    } else {
      const msg = err instanceof Error ? err.message : String(err);
      setStatus({ text: msg, kind: 'error' });
    }
  }

  async function handleLogin() {
    const authUrl = await buildAuthUrl();
    window.location.href = authUrl;
  }

  async function handleLogout() {
    if (tokens) {
      try {
        await oidcLogout(tokens.refresh_token);
      } catch {
        // Best effort
      }
    }
    setAccessToken(null);
    setTokens(null);
    setPages([]);
    setStatus({ text: 'Ready', kind: 'idle' });
  }

  async function handleCreatePage(payload: CmsPageRequest) {
    if (!accessToken) return;
    setStatus({ text: 'Creating…', kind: 'pending' });
    try {
      await api.createPage({ accessToken }, payload);
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadPages(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to create page');
      throw err;
    }
  }

  async function handleDeletePage(id: string) {
    if (!accessToken) return;
    setStatus({ text: 'Deleting…', kind: 'pending' });
    try {
      await api.deletePage({ accessToken }, id);
      setStatus({ text: 'Authenticated', kind: 'success' });
      await loadPages(accessToken);
    } catch (err) {
      handleApiError(err, 'Failed to delete page');
    }
  }

  return (
    <>
      {!accessToken && !isCallback && <LoginOverlay onLogin={handleLogin} />}

      <div className="app-shell">
        <header>
          <div className="icon-box">📄</div>
          <div>
            <h1>CMS Admin</h1>
            <p>Manage CMS pages</p>
          </div>
          <div className="header-actions">
            {accessToken && (
              <button className="btn btn-secondary" onClick={handleLogout}>
                Logout
              </button>
            )}
          </div>
        </header>

        <main>
          <div className="status-bar">
            <span className={`status-indicator status-${status.kind}`} />
            <span>{status.text}</span>
          </div>

          {accessToken && (
            <PagesTab
              pages={pages}
              onCreate={handleCreatePage}
              onDelete={handleDeletePage}
            />
          )}
        </main>
      </div>
    </>
  );
}
