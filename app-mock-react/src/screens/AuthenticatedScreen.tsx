import { useState, useEffect } from 'react';
import { parseJwt } from '../services/crypto';
import { refreshTokens, initiateTransfer, fetchUserinfo, getPasswordStatus } from '../services/api';
import { Spinner } from '../components/ui';
import PasswordModal from '../components/PasswordModal';
import type { OidcTokens, LogEntry } from '../types';

interface Props {
  tokens: OidcTokens;
  onTokensRefreshed: (tokens: OidcTokens) => void;
  onLogout: () => void;
  log: (msg: string, level?: LogEntry['level']) => void;
}

type TabKey = 'access' | 'id' | 'refresh';

export default function AuthenticatedScreen({ tokens, onTokensRefreshed, onLogout, log }: Props) {
  const [activeTab, setActiveTab] = useState<TabKey>('access');
  const [busy, setBusy]           = useState(false);
  const [error, setError]         = useState('');
  const [transferUrl, setTransferUrl]     = useState('');
  const [transferBusy, setTransferBusy]   = useState(false);
  const [transferred, setTransferred]     = useState(false);
  const [userinfoBusy, setUserinfoBusy]   = useState(false);
  const [userinfoData, setUserinfoData]   = useState<Record<string, unknown> | null>(null);
  const [showPasswordModal, setShowPasswordModal] = useState(false);
  const [passwordCheckDone, setPasswordCheckDone] = useState(false);

  // Check password status on mount
  useEffect(() => {
    if (passwordCheckDone) return;
    (async () => {
      try {
        const status = await getPasswordStatus(tokens.access_token);
        if (!status.hasPassword) {
          setShowPasswordModal(true);
        }
      } catch (err) {
        // Ignore - user can proceed without password check
        log('Passwort-Status konnte nicht geprüft werden: ' + (err as Error).message, 'warn');
      }
      setPasswordCheckDone(true);
    })();
  }, [tokens.access_token, passwordCheckDone, log]);

  // Tick every second so secsLeft / expired stay accurate in real time.
  const [, tick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => tick(n => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  const accessParsed = parseJwt(tokens.access_token);
  const payload      = accessParsed?.payload ?? {};
  const sub          = payload['preferred_username'] as string ?? payload['sub'] as string ?? '—';
  const exp          = payload['exp'] as number | undefined;
  const secsLeft     = exp ? Math.max(0, Math.round((exp * 1000 - Date.now()) / 1000)) : tokens.expires_in;
  const expired      = secsLeft === 0;

  async function handleRefresh() {
    if (!tokens.refresh_token) { setError('Kein Refresh Token.'); return; }
    setError('');
    setBusy(true);
    log('→ Token erneuern…', 'info');
    try {
      const t = await refreshTokens(tokens.refresh_token);
      onTokensRefreshed(t);
      log('Tokens erneuert.', 'ok');
    } catch (err) {
      const msg = (err as Error).message;
      setError(msg);
      log(`Refresh fehlgeschlagen: ${msg}`, 'err');
    } finally {
      setBusy(false);
    }
  }

  async function handleUserinfo() {
    setError('');
    setUserinfoBusy(true);
    log('→ Userinfo-Endpoint abrufen…', 'info');
    try {
      const data = await fetchUserinfo(tokens.access_token);
      setUserinfoData(data);
      log('Userinfo erfolgreich abgerufen.', 'ok');
    } catch (err) {
      const msg = (err as Error).message;
      setError(msg);
      log(`Userinfo fehlgeschlagen: ${msg}`, 'err');
    } finally {
      setUserinfoBusy(false);
    }
  }

  async function handleTransfer() {
    setError('');
    setTransferUrl('');
    setTransferred(false);
    setTransferBusy(true);
    log('→ Browser-Transfer initiieren…', 'info');
    try {
      const result = await initiateTransfer(tokens.access_token);
      setTransferUrl(result.transferUrl);
      log(`Transfer-URL generiert (gültig ${result.expiresInSeconds}s).`, 'ok');
    } catch (err) {
      const msg = (err as Error).message;
      setError(msg);
      log(`Transfer fehlgeschlagen: ${msg}`, 'err');
    } finally {
      setTransferBusy(false);
    }
  }

  async function copyTransferUrl() {
    if (!transferUrl) return;
    await navigator.clipboard.writeText(transferUrl);
    setTransferred(true);
    setTimeout(() => setTransferred(false), 2000);
  }

  return (
    <div className="flex flex-col gap-5">
      {showPasswordModal && (
        <PasswordModal
          accessToken={tokens.access_token}
          onPasswordSet={() => setShowPasswordModal(false)}
          onSkip={() => setShowPasswordModal(false)}
        />
      )}

      {/* User card */}
      <div className="text-center pt-4 flex flex-col items-center">
        <div className="w-16 h-16 rounded-full bg-green-900/40 border border-green-700/40 flex items-center justify-center text-4xl mb-3 leading-none">
          ✅
        </div>
        <h2 className="text-xl font-bold">{sub}</h2>
        <p className="text-sm mt-1">
          <span className={expired ? 'text-red-400' : 'text-green-400'}>
            {expired ? 'Token abgelaufen' : `Token gültig noch ${secsLeft}s`}
          </span>
        </p>
      </div>

      {/* Actions */}
      {error && <p className="text-sm text-red-400 text-center">{error}</p>}

      <div className="flex gap-3">
        <button
          className="btn-secondary flex-1"
          onClick={handleRefresh}
          disabled={busy || !tokens.refresh_token}
        >
          {busy ? <><Spinner /> Erneuern…</> : '🔄 Token erneuern'}
        </button>
        <button className="btn-danger" onClick={onLogout}>
          Abmelden
        </button>
      </div>

      {/* Userinfo */}
      <div className="rounded-xl border border-[--color-border] bg-[--color-surface] p-4 flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold">Userinfo-Endpoint</span>
          <button
            className="btn-secondary text-xs px-3 py-1.5"
            onClick={handleUserinfo}
            disabled={userinfoBusy}
          >
            {userinfoBusy ? <><Spinner /> Wird abgerufen…</> : '👤 Userinfo abrufen'}
          </button>
        </div>
        {userinfoData && (
          <div className="flex flex-col gap-1">
            {Object.entries(userinfoData).map(([k, v]) => (
              <div key={k} className="flex gap-2 text-xs py-1 border-b border-[--color-border]/40 last:border-0">
                <span className="text-[--color-accent] font-mono shrink-0 w-32 truncate">{k}</span>
                <span className="font-mono break-all text-[--color-text]">
                  {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Browser transfer */}
      <div className="rounded-xl border border-[--color-border] bg-[--color-surface] p-4 flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold">Browser-Übertragung</span>
          <button
            className="btn-secondary text-xs px-3 py-1.5"
            onClick={handleTransfer}
            disabled={transferBusy}
          >
            {transferBusy ? <><Spinner /> Wird erstellt…</> : '🌐 In Browser öffnen'}
          </button>
        </div>
        <p className="text-xs text-[--color-text-dim] leading-relaxed">
          Erzeugt eine einmalige Transfer-URL (gültig 60 s). auth-service deposited eine
          Keycloak SSO-Session und leitet zu target-app weiter, das automatisch OIDC
          Auth Code + PKCE startet — kein Login-Prompt.
        </p>
        {transferUrl && (
          <div className="flex flex-col gap-2">
            <div className="bg-[--color-bg] rounded-lg p-3 font-mono text-[10px] break-all text-[--color-accent] max-h-24 overflow-y-auto">
              {transferUrl}
            </div>
            <button
              className="btn-secondary text-xs self-end px-3 py-1.5"
              onClick={copyTransferUrl}
            >
              {transferred ? '✅ Kopiert!' : '📋 URL kopieren'}
            </button>
          </div>
        )}
      </div>

      {/* Token viewer */}
      <div className="rounded-xl border border-[--color-border] bg-[--color-surface] overflow-hidden">
        {/* Tabs */}
        <div className="flex border-b border-[--color-border]">
          {(['access', 'id', 'refresh'] as TabKey[]).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={[
                'flex-1 py-2.5 text-xs font-semibold transition-colors',
                activeTab === tab
                  ? 'text-[--color-accent] border-b-2 border-[--color-accent] -mb-px bg-[--color-accent]/5'
                  : 'text-[--color-text-dim] hover:text-[--color-text]',
              ].join(' ')}
            >
              {tab === 'access' ? 'Access' : tab === 'id' ? 'ID' : 'Refresh'}
            </button>
          ))}
        </div>

        <div className="p-4">
          {activeTab === 'refresh' ? (
            <div>
              <p className="text-xs text-[--color-text-dim] mb-2">Opak / verschlüsselt — kein Payload</p>
              <div className="bg-[--color-bg] rounded-lg p-3 font-mono text-[10px] break-all text-[--color-text-dim] max-h-24 overflow-y-auto">
                {tokens.refresh_token || '(kein Refresh Token)'}
              </div>
            </div>
          ) : (
            <JwtView token={activeTab === 'access' ? tokens.access_token : tokens.id_token} />
          )}
        </div>
      </div>
    </div>
  );
}

// ── JWT viewer ──────────────────────────────────────────────────────

function JwtView({ token }: { token: string }) {
  const [showRaw, setShowRaw] = useState(false);
  const parsed = parseJwt(token);
  if (!parsed) return <p className="text-xs text-red-400">Ungültiges JWT</p>;

  return (
    <div className="flex flex-col gap-3">
      {/* Claims */}
      <div className="flex flex-col gap-1">
        {Object.entries(parsed.payload).map(([k, v]) => (
          <ClaimRow key={k} k={k} v={v} />
        ))}
      </div>

      {/* Raw toggle */}
      <button
        className="text-xs text-[--color-text-dim] hover:text-[--color-text] text-left transition-colors"
        onClick={() => setShowRaw(x => !x)}
      >
        {showRaw ? '▲ Raw ausblenden' : '▼ Raw anzeigen'}
      </button>
      {showRaw && (
        <div className="bg-[--color-bg] rounded-lg p-3 font-mono text-[10px] break-all text-[--color-text-dim] max-h-32 overflow-y-auto whitespace-pre-wrap">
          {token}
        </div>
      )}
    </div>
  );
}

function ClaimRow({ k, v }: { k: string; v: unknown }) {
  let display: string;
  let valCls = 'text-[--color-text]';

  if ((k === 'exp' || k === 'iat' || k === 'nbf' || k === 'auth_time') && typeof v === 'number') {
    const date    = new Date(v * 1000);
    const expired = k === 'exp' && date < new Date();
    valCls  = k === 'exp' ? (expired ? 'text-red-400' : 'text-green-400') : 'text-[--color-text-dim]';
    display = `${date.toLocaleString('de-DE')}`;
  } else {
    display = typeof v === 'object' ? JSON.stringify(v) : String(v);
  }

  return (
    <div className="flex gap-2 text-xs py-1 border-b border-[--color-border]/40 last:border-0">
      <span className="text-[--color-accent] font-mono shrink-0 w-32 truncate">{k}</span>
      <span className={`font-mono break-all ${valCls}`}>{display}</span>
    </div>
  );
}
