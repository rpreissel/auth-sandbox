import { useState, useEffect, useRef } from 'react';
import BiometricModal from '../components/BiometricModal';
import { Spinner } from '../components/ui';
import { startLogin, verifyLogin } from '../services/api';
import { signChallenge } from '../services/crypto';
import type { DeviceBinding, LogEntry, OidcTokens } from '../types';

interface Props {
  binding: DeviceBinding;
  privateKey: CryptoKey;
  onLoggedIn: (tokens: OidcTokens) => void;
  onPasswordRequired: (tokens: OidcTokens) => void;
  onUnregister: () => void;
  log: (msg: string, level?: LogEntry['level']) => void;
  autoLogin?: boolean;
}

export default function HomeScreen({ binding, privateKey, onLoggedIn, onPasswordRequired, onUnregister, log, autoLogin }: Props) {
  const [busy, setBusy]                     = useState(false);
  const [showBio, setShowBio]               = useState(false);
  const [error, setError]                   = useState('');
  const [pendingResolve, setPendingResolve] = useState<((ok: boolean) => void) | null>(null);

  function requestBiometric(): Promise<boolean> {
    return new Promise(resolve => {
      setPendingResolve(() => resolve);
      setShowBio(true);
    });
  }
  function bioConfirm() { setShowBio(false); pendingResolve?.(true);  setPendingResolve(null); }
  function bioCancel()  { setShowBio(false); pendingResolve?.(false); setPendingResolve(null); }

  async function handleLogin() {
    if (busy) return;
    setError('');
    setBusy(true);
    try {
      log('Challenge anfordern…', 'info');
      const challenge = await startLogin(binding.deviceId);
      log(`Challenge erhalten (nonce: ${challenge.nonce})`, 'ok');

      log('Warte auf biometrische Bestätigung…', 'warn');
      const confirmed = await requestBiometric();
      if (!confirmed) {
        log('Abgebrochen.', 'warn');
        return;
      }

      log('Signiere Challenge…', 'info');
      const signature = await signChallenge(privateKey, challenge.challenge);

      log('Verifiziere…', 'info');
      const tokens = await verifyLogin(challenge.nonce, signature);
      if (tokens.required_action === 'SET_PASSWORD') {
        log('Passwort-Einrichtung erforderlich.', 'warn');
        onPasswordRequired(tokens);
      } else {
        log('Erfolgreich authentifiziert.', 'ok');
        onLoggedIn(tokens);
      }
    } catch (err) {
      const msg = (err as Error).message;
      setError(msg);
      log(`Login fehlgeschlagen: ${msg}`, 'err');
    } finally {
      setBusy(false);
    }
  }

  const autoLoginFired = useRef(false);
  useEffect(() => {
    if (autoLogin && !autoLoginFired.current) {
      autoLoginFired.current = true;
      handleLogin();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoLogin]);

  return (
    <>
      {showBio && <BiometricModal onConfirm={bioConfirm} onCancel={bioCancel} />}

      <div className="flex flex-col gap-6">
        {/* Avatar + greeting */}
        <div className="text-center pt-4 flex flex-col items-center">
          <div className="w-16 h-16 rounded-full bg-[--color-accent]/20 flex items-center justify-center text-4xl mb-3 leading-none">
            👤
          </div>
          <h2 className="text-xl font-bold">{binding.name}</h2>
          <p className="text-sm text-[--color-text-dim] mt-1">{binding.userId}</p>
        </div>

        {/* Binding status */}
        <div className="flex items-center justify-center gap-2 text-sm">
          <span className="w-2 h-2 rounded-full bg-green-500" />
          <span className="text-green-400 font-medium">Gerätebindung aktiv</span>
        </div>

        {/* Error */}
        {error && <p className="text-sm text-red-400 text-center">{error}</p>}

        {/* Primary action */}
        <button className="btn-primary text-base py-4" onClick={handleLogin} disabled={busy}>
          {busy
            ? <><Spinner size="md" /> Anmelden…</>
            : <>🔐 Mit Biometrie anmelden</>}
        </button>

        {/* Device info (secondary) */}
        <details className="group">
          <summary className="text-xs text-[--color-text-dim] cursor-pointer select-none text-center hover:text-[--color-text] transition-colors list-none">
            📱 Geräteinformationen
          </summary>
          <div className="mt-3 bg-[--color-bg] border border-[--color-border] rounded-xl p-3 font-mono text-xs text-[--color-text-dim] break-all">
            {binding.deviceId}
          </div>
        </details>

        {/* Danger zone */}
        <button
          className="text-xs text-[--color-text-dim] hover:text-red-400 transition-colors text-center mt-2"
          onClick={onUnregister}
          disabled={busy}
        >
          Gerätebindung aufheben
        </button>
      </div>
    </>
  );
}
