import { buildAuthUrl } from '../services/oidc';

export function LoginOverlay() {
  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const authUrl = await buildAuthUrl();
    window.location.href = authUrl;
  }

  return (
    <div className="overlay">
      <div className="overlay-content">
        <h2>CMS Admin Login</h2>
        <form className="login-form" onSubmit={handleSubmit}>
          <p style={{ marginBottom: '1rem', color: '#666' }}>
            Click the button below to authenticate with your identity provider.
          </p>
          <button type="submit" className="btn btn-primary">
            Login with OIDC
          </button>
        </form>
      </div>
    </div>
  );
}
