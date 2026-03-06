import { buildAuthUrl } from '../services/oidc';

export function LoginOverlay() {
  async function handleLogin() {
    const authUrl = await buildAuthUrl();
    window.location.href = authUrl;
  }

  return (
    <div id="login-overlay">
      <div className="login-box">
        <div style={{ fontSize: '2rem', marginBottom: '.75rem' }}>🔑</div>
        <h2>Admin Login</h2>
        <p>Authenticate with Keycloak to access the Device Auth Admin panel.</p>
        <div className="btn-group" style={{ marginTop: '1.25rem' }}>
          <button
            className="btn btn-primary"
            onClick={() => void handleLogin()}
            style={{ width: '100%', justifyContent: 'center' }}
          >
            Login with Keycloak
          </button>
        </div>
      </div>
    </div>
  );
}
