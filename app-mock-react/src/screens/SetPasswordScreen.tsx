import { useState } from 'react';
import { Spinner } from '../components/ui';
import { setPassword } from '../services/api';
import type { OidcTokens, LogEntry } from '../types';

interface Props {
  tokens: OidcTokens;
  onPasswordSet: (tokens: OidcTokens) => void;
  log: (msg: string, level?: LogEntry['level']) => void;
}

export default function SetPasswordScreen({ tokens, onPasswordSet, log }: Props) {
  const [password, setPasswordInput] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit() {
    setError('');
    if (password.length < 8) {
      setError('Passwort muss mindestens 8 Zeichen haben.');
      return;
    }
    if (password !== confirm) {
      setError('Passwörter stimmen nicht überein.');
      return;
    }
    setBusy(true);
    try {
      await setPassword(tokens.access_token, password);
      log('Passwort erfolgreich gesetzt.', 'ok');
      onPasswordSet(tokens);
    } catch (err) {
      setError((err as Error).message);
      log('Passwort setzen fehlgeschlagen: ' + (err as Error).message, 'err');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="text-center pt-4 flex flex-col items-center">
        <div className="w-16 h-16 rounded-full bg-[--color-accent]/20 flex items-center justify-center text-4xl mb-3 leading-none">
          🔑
        </div>
        <h2 className="text-xl font-bold">Passwort erstellen</h2>
        <p className="text-sm text-[--color-text-dim] mt-1">
          Erstellen Sie ein Passwort, um sich zukünftig auch ohne Gerät anmelden zu können.
        </p>
      </div>

      <div className="flex flex-col gap-3">
        <input
          type="password"
          placeholder="Neues Passwort"
          value={password}
          onChange={e => setPasswordInput(e.target.value)}
          className="input-field"
          disabled={busy}
        />
        <input
          type="password"
          placeholder="Passwort bestätigen"
          value={confirm}
          onChange={e => setConfirm(e.target.value)}
          className="input-field"
          disabled={busy}
        />

        {error && <p className="text-sm text-red-400">{error}</p>}

        <button className="btn-primary" onClick={handleSubmit} disabled={busy}>
          {busy ? <><Spinner /> Passwort setzen…</> : '🔑 Passwort setzen'}
        </button>
      </div>
    </div>
  );
}
