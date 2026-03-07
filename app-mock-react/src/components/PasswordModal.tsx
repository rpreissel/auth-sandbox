import { useState } from 'react';
import { Spinner } from './ui';
import { setPassword } from '../services/api';

interface Props {
  accessToken: string;
  onPasswordSet: () => void;
  onSkip?: () => void;
}

export default function PasswordModal({ accessToken, onPasswordSet, onSkip }: Props) {
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
      await setPassword(accessToken, password);
      onPasswordSet();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
      <div className="bg-[--color-surface] border border-[--color-border] rounded-2xl p-6 w-full max-w-sm mx-4">
        <h2 className="text-xl font-bold mb-2">Passwort erstellen</h2>
        <p className="text-sm text-[--color-text-dim] mb-4">
          Sie haben noch kein Passwort. Erstellen Sie eines, um sich zukünftig auch ohne Gerät anmelden zu können.
        </p>

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

          <button
            className="btn-primary"
            onClick={handleSubmit}
            disabled={busy}
          >
            {busy ? <><Spinner /> Passwort setzen…</> : '🔑 Passwort setzen'}
          </button>

          {onSkip && (
            <button
              className="text-xs text-[--color-text-dim] hover:text-[--color-text] transition-colors"
              onClick={onSkip}
              disabled={busy}
            >
              Später →进入
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
