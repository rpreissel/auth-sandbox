import { useState, useEffect, useRef } from 'react';
import { useLog } from './hooks/useLog';
import ActivityLog from './components/ActivityLog';
import UnregisteredScreen from './screens/UnregisteredScreen';
import HomeScreen from './screens/HomeScreen';
import AuthenticatedScreen from './screens/AuthenticatedScreen';
import SetPasswordScreen from './screens/SetPasswordScreen';
import { loadBinding, loadPrivateKey, clearBinding, clearPrivateKey } from './services/storage';
import { setBaseUrl } from './services/api';
import type { DeviceBinding, OidcTokens, Screen } from './types';

export default function App() {
  const { entries, log, clear } = useLog();

  const [binding,    setBinding]      = useState<DeviceBinding | null>(null);
  const [privateKey, setPrivateKey]   = useState<CryptoKey | null>(null);
  const [tokens,     setTokens]       = useState<OidcTokens | null>(null);
  const [screen,     setScreen]       = useState<Screen>('unregistered');
  const [baseUrl,    setBaseUrlState] = useState('');
  const [autoLogin,  setAutoLogin]    = useState(false);
  const [ready,      setReady]        = useState(false);
  const initFired = useRef(false);

  useEffect(() => {
    if (initFired.current) return;
    initFired.current = true;
    (async () => {
      const b  = loadBinding();
      const pk = b ? await loadPrivateKey() : null;
      if (b && pk) {
        setBinding(b);
        setPrivateKey(pk);
        setScreen('home');
        log(`Gerätebindung geladen: ${b.deviceId}`, 'ok');
      } else {
        log('Keine Gerätebindung. Bitte Gerät registrieren.', 'info');
      }
      setReady(true);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleBaseUrlChange(url: string) {
    setBaseUrlState(url);
    setBaseUrl(url);
  }

  function handleRegistered(b: DeviceBinding, pk: CryptoKey) {
    setBinding(b);
    setPrivateKey(pk);
    setAutoLogin(true);
    setScreen('home');
  }

  function handleLoggedIn(t: OidcTokens) {
    setTokens(t);
    setAutoLogin(false);
    setScreen('authenticated');
  }

  function handlePasswordRequired(t: OidcTokens) {
    setTokens(t);
    setAutoLogin(false);
    setScreen('set-password');
    log('Passwort-Einrichtung erforderlich.', 'warn');
  }

  function handlePasswordSet(t: OidcTokens) {
    setScreen('authenticated');
    log('Passwort gesetzt, angemeldet.', 'ok');
  }

  function handleLogout() {
    setTokens(null);
    setScreen('home');
    log('Abgemeldet.', 'info');
  }

  function handleUnregister() {
    clearBinding();
    clearPrivateKey();
    setBinding(null);
    setPrivateKey(null);
    setTokens(null);
    setAutoLogin(false);
    setScreen('unregistered');
    log('Gerätebindung aufgehoben.', 'warn');
  }

  if (!ready) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[--color-bg]">
        <div className="w-8 h-8 border-2 border-[--color-accent] border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[--color-bg] flex flex-col items-center justify-start px-4 py-10">

      {/* Phone frame */}
      <div className="w-full max-w-[400px] flex flex-col gap-5">

        {/* Header bar */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-xl bg-[--color-accent] flex items-center justify-center text-base">
              📱
            </div>
            <div>
              <p className="text-sm font-bold leading-tight">Device Auth Mock</p>
              <p className="text-[11px] text-[--color-text-dim] leading-tight">OAuth2 Device Flow</p>
            </div>
          </div>
          <StatusPill screen={screen} />
        </div>

        {/* Screen card */}
        <div className="bg-[--color-surface] border border-[--color-border] rounded-2xl p-6 shadow-xl shadow-black/40">
          {screen === 'unregistered' && (
            <UnregisteredScreen
              onRegistered={handleRegistered}
              log={log}
              baseUrl={baseUrl}
              onBaseUrlChange={handleBaseUrlChange}
            />
          )}
          {screen === 'home' && binding && privateKey && (
            <HomeScreen
              binding={binding}
              privateKey={privateKey}
              onLoggedIn={handleLoggedIn}
              onPasswordRequired={handlePasswordRequired}
              onUnregister={handleUnregister}
              log={log}
              autoLogin={autoLogin}
            />
          )}
          {screen === 'set-password' && tokens && (
            <SetPasswordScreen
              tokens={tokens}
              onPasswordSet={handlePasswordSet}
              log={log}
            />
          )}
          {screen === 'authenticated' && tokens && (
            <AuthenticatedScreen
              tokens={tokens}
              onTokensRefreshed={t => { setTokens(t); log('Tokens erneuert.', 'ok'); }}
              onLogout={handleLogout}
              log={log}
            />
          )}
        </div>

        {/* Log */}
        <ActivityLog entries={entries} onClear={clear} />

      </div>
    </div>
  );
}

function StatusPill({ screen }: { screen: Screen }) {
  const map: Record<Screen, { label: string; cls: string; dot: string }> = {
    unregistered:  { label: 'Nicht registriert', cls: 'bg-[--color-surface2] text-[--color-text-dim]', dot: 'bg-[--color-text-dim]' },
    home:          { label: 'Registriert',        cls: 'bg-[--color-accent]/15 text-[--color-accent]', dot: 'bg-[--color-accent]' },
    'set-password':{ label: 'Passwort setzen',    cls: 'bg-yellow-950/60 text-yellow-400',             dot: 'bg-yellow-500 animate-pulse' },
    authenticated: { label: 'Angemeldet',          cls: 'bg-green-950/60 text-green-400',               dot: 'bg-green-500 animate-pulse' },
  };
  const { label, cls, dot } = map[screen];
  return (
    <span className={`inline-flex items-center gap-1.5 text-[11px] font-semibold px-2.5 py-1 rounded-full ${cls}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${dot}`} />
      {label}
    </span>
  );
}
