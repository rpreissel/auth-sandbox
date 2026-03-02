import { useEffect, useState } from 'react';
import { buildAuthUrl, exchangeCode } from './services/oidc.ts';
import TokenDisplay from './components/TokenDisplay.tsx';
import type { OidcTokens } from './types/index.ts';

type AppState =
  | { phase: 'init' }
  | { phase: 'redirecting' }
  | { phase: 'exchanging' }
  | { phase: 'done'; tokens: OidcTokens }
  | { phase: 'error'; message: string };

export default function App() {
  const [state, setState] = useState<AppState>({ phase: 'init' });

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code   = params.get('code');
    const returnedState = params.get('state');
    const error  = params.get('error');

    if (error) {
      setState({ phase: 'error', message: `OAuth error: ${params.get('error_description') ?? error}` });
      return;
    }

    if (code && returnedState) {
      // Callback — exchange code for tokens
      setState({ phase: 'exchanging' });
      // Clean the URL so a refresh doesn't re-exchange
      window.history.replaceState({}, '', window.location.pathname);

      exchangeCode(code, returnedState)
        .then(tokens => setState({ phase: 'done', tokens }))
        .catch((err: unknown) => setState({ phase: 'error', message: (err as Error).message }));
      return;
    }

    // No code in URL — start OIDC flow.
    // If an SSO session exists (established by sso-proxy transfer), Keycloak will
    // skip the login prompt and redirect straight back with a code.
    setState({ phase: 'redirecting' });
    buildAuthUrl()
      .then(url => {
        // Brief delay to allow UI to show redirecting state before navigation
        setTimeout(() => { window.location.href = url; }, 100);
      })
      .catch((err: unknown) => setState({ phase: 'error', message: (err as Error).message }));
  }, []);

  return (
    <div className="min-h-screen flex flex-col items-center justify-center px-4 py-8">
      <div className="w-full max-w-xl">
        {/* Header */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-[--color-accent]/20 border border-[--color-accent]/30 text-3xl mb-4">
            🌐
          </div>
          <h1 className="text-2xl font-bold tracking-tight">Target App</h1>
          <p className="text-sm text-[--color-text-dim] mt-1">SSO transfer destination</p>
        </div>

        {/* State */}
        {state.phase === 'init' && (
          <StatusCard icon="⏳" title="Initialising…" />
        )}

        {state.phase === 'redirecting' && (
          <StatusCard icon="🔀" title="Redirecting to Keycloak…" subtitle="Starting OIDC Authorization Code + PKCE flow" />
        )}

        {state.phase === 'exchanging' && (
          <StatusCard icon="🔄" title="Exchanging code for tokens…" subtitle="Completing PKCE flow" />
        )}

        {state.phase === 'error' && (
          <div className="rounded-xl border border-red-800/50 bg-red-950/30 p-5 text-center">
            <div className="text-3xl mb-2">❌</div>
            <p className="text-sm font-semibold text-red-400">{state.message}</p>
          </div>
        )}

        {state.phase === 'done' && (
          <TokenDisplay tokens={state.tokens} />
        )}
      </div>
    </div>
  );
}

function StatusCard({ icon, title, subtitle }: { icon: string; title: string; subtitle?: string }) {
  return (
    <div className="rounded-xl border border-[--color-border] bg-[--color-surface] p-6 text-center">
      <div className="text-3xl mb-3">{icon}</div>
      <p className="text-sm font-semibold">{title}</p>
      {subtitle && <p className="text-xs text-[--color-text-dim] mt-1">{subtitle}</p>}
    </div>
  );
}
