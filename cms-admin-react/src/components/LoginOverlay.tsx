interface Props {
  onLogin: () => void;
}

export function LoginOverlay({ onLogin }: Props) {
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    try {
      onLogin();
    } catch (err) {
      console.error('Login failed:', err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="overlay">
      <div className="overlay-content">
        <h2>CMS Admin Login</h2>
        <form className="login-form" onSubmit={handleSubmit}>
          <p style={{ marginBottom: '1rem', color: '#666' }}>
            Click the button below to authenticate with your identity provider.
          </p>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Redirecting...' : 'Login with OIDC'}
          </button>
        </form>
      </div>
    </div>
  );
}
