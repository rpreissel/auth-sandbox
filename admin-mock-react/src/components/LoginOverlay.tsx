import { useState } from 'react';
import type { KeyboardEvent } from 'react';

interface Props {
  onLogin: (username: string, password: string) => Promise<void>;
}

export function LoginOverlay({ onLogin }: Props) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleLogin() {
    if (!username || !password) {
      setError('Username and password are required.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      await onLogin(username, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed.');
    } finally {
      setLoading(false);
    }
  }

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') void handleLogin();
  }

  return (
    <div id="login-overlay">
      <div className="login-box">
        <div style={{ fontSize: '2rem', marginBottom: '.75rem' }}>🔑</div>
        <h2>Admin Login</h2>
        <p>Keycloak admin credentials required to access the Device Auth Admin panel.</p>
        {error && <div className="login-error show">{error}</div>}
        <div className="field">
          <label htmlFor="login-username">Username</label>
          <input
            id="login-username"
            name="username"
            type="text"
            value={username}
            onChange={e => setUsername(e.target.value)}
            autoComplete="username"
          />
        </div>
        <div className="field">
          <label htmlFor="login-password">Password</label>
          <input
            id="login-password"
            name="password"
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            onKeyDown={handleKeyDown}
            autoComplete="current-password"
          />
        </div>
        <div className="btn-group" style={{ marginTop: '1.25rem' }}>
          <button
            className="btn btn-primary"
            onClick={() => void handleLogin()}
            disabled={loading}
            style={{ width: '100%', justifyContent: 'center' }}
          >
            {loading ? 'Logging in…' : 'Login'}
          </button>
        </div>
      </div>
    </div>
  );
}
