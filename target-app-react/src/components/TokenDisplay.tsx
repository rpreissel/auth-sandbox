import { useState } from 'react';
import { fetchUserinfo } from '../services/oidc';
import type { OidcTokens } from '../types';

interface Props {
  tokens: OidcTokens;
}

type TabKey = 'access' | 'id' | 'refresh';

export default function TokenDisplay({ tokens }: Props) {
  const [activeTab, setActiveTab] = useState<TabKey>('access');
  const [userinfoBusy, setUserinfoBusy] = useState(false);
  const [userinfoData, setUserinfoData] = useState<Record<string, unknown> | null>(null);
  const [userinfoError, setUserinfoError] = useState('');

  const payload = parseJwt(tokens.access_token)?.payload ?? {};
  const sub     = (payload['preferred_username'] as string | undefined)
               ?? (payload['sub'] as string | undefined)
               ?? '—';
  const exp     = payload['exp'] as number | undefined;
  const secsLeft = exp ? Math.max(0, Math.round((exp * 1000 - Date.now()) / 1000)) : undefined;
  const expired  = secsLeft === 0;

  async function handleUserinfo() {
    setUserinfoError('');
    setUserinfoBusy(true);
    try {
      const data = await fetchUserinfo(tokens.access_token);
      setUserinfoData(data);
    } catch (err) {
      setUserinfoError((err as Error).message);
    } finally {
      setUserinfoBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-5 w-full max-w-xl mx-auto">
      {/* User card */}
      <div className="text-center pt-4 flex flex-col items-center">
        <div className="w-16 h-16 rounded-full bg-green-900/40 border border-green-700/40 flex items-center justify-center text-4xl mb-3 leading-none">
          ✅
        </div>
        <h2 className="text-xl font-bold">{sub}</h2>
        <p className="text-sm mt-1">
          {secsLeft !== undefined
            ? <span className={expired ? 'text-red-400' : 'text-green-400'}>
                {expired ? 'Token expired' : `Token valid for ${secsLeft}s`}
              </span>
            : <span className="text-[--color-text-dim]">SSO transfer complete</span>
          }
        </p>
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
              <p className="text-xs text-[--color-text-dim] mb-2">Opaque / encrypted — no payload</p>
              <div className="bg-[--color-bg] rounded-lg p-3 font-mono text-[10px] break-all text-[--color-text-dim] max-h-24 overflow-y-auto">
                {tokens.refresh_token || '(no refresh token)'}
              </div>
            </div>
          ) : (
            <JwtView token={activeTab === 'access' ? tokens.access_token : tokens.id_token} />
          )}
        </div>
      </div>

      {/* Userinfo */}
      <div className="rounded-xl border border-[--color-border] bg-[--color-surface] p-4 flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold">Userinfo Endpoint</span>
          <button
            className={[
              'text-xs px-3 py-1.5 rounded-lg border font-semibold transition-colors',
              'border-[--color-border] bg-[--color-surface2] text-[--color-text] hover:border-[--color-accent]/50',
            ].join(' ')}
            onClick={handleUserinfo}
            disabled={userinfoBusy}
          >
            {userinfoBusy ? '⏳ Loading…' : '👤 Fetch userinfo'}
          </button>
        </div>
        {userinfoError && (
          <p className="text-xs text-red-400">{userinfoError}</p>
        )}
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
    </div>
  );
}

// ── JWT viewer ──────────────────────────────────────────────────────

function parseJwt(token: string): { payload: Record<string, unknown> } | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const decoded = atob(parts[1]!.replace(/-/g, '+').replace(/_/g, '/'));
    return { payload: JSON.parse(decoded) as Record<string, unknown> };
  } catch {
    return null;
  }
}

function JwtView({ token }: { token: string }) {
  const [showRaw, setShowRaw] = useState(false);
  const parsed = parseJwt(token);
  if (!parsed) return <p className="text-xs text-red-400">Invalid JWT</p>;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        {Object.entries(parsed.payload).map(([k, v]) => (
          <ClaimRow key={k} k={k} v={v} />
        ))}
      </div>

      <button
        className="text-xs text-[--color-text-dim] hover:text-[--color-text] text-left transition-colors"
        onClick={() => setShowRaw(x => !x)}
      >
        {showRaw ? '▲ Hide raw' : '▼ Show raw'}
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
    display = date.toLocaleString();
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
