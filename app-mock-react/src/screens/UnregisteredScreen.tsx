import { useState } from 'react';
import { generateDeviceId, generateKeyPair, exportPublicKeyPem } from '../services/crypto';
import { saveBinding, savePrivateKey } from '../services/storage';
import { registerDevice } from '../services/api';
import { Spinner } from '../components/ui';
import type { DeviceBinding, LogEntry } from '../types';

interface Props {
  onRegistered: (binding: DeviceBinding, privateKey: CryptoKey) => void;
  log: (msg: string, level?: LogEntry['level']) => void;
  baseUrl: string;
  onBaseUrlChange: (url: string) => void;
}

type Step = 'form' | 'keygen' | 'registering' | 'done';

export default function UnregisteredScreen({ onRegistered, log, baseUrl, onBaseUrlChange }: Props) {
  const [deviceId]                    = useState(() => generateDeviceId());
  const [userId, setUserId]           = useState('');
  const [name,   setName]             = useState('');
  const [code,   setCode]             = useState('');
  const [step,   setStep]             = useState<Step>('form');
  const [error,  setError]            = useState('');

  const busy = step === 'keygen' || step === 'registering';

  async function handleSubmit() {
    setError('');
    if (!userId.trim()) { setError('User-ID fehlt.'); return; }
    if (!name.trim())   { setError('Name fehlt.');    return; }
    if (!code.trim())   { setError('Freischaltcode fehlt.'); return; }

    // Step 1: generate keys
    setStep('keygen');
    log('Generiere RSA-2048 Schlüsselpaar…', 'info');
    let kp: CryptoKeyPair;
    let pem: string;
    try {
      kp  = await generateKeyPair();
      pem = await exportPublicKeyPem(kp.publicKey);
      log('Schlüsselpaar generiert.', 'ok');
    } catch (err) {
      setError(`Key-Generierung fehlgeschlagen: ${(err as Error).message}`);
      setStep('form');
      return;
    }

    // Step 2: register
    setStep('registering');
    log(`→ POST ${baseUrl}/api/v1/devices/register`, 'info');
    try {
      const resp = await registerDevice({
        deviceId,
        userId:         userId.trim(),
        name:           name.trim(),
        activationCode: code.trim(),
        publicKey:      pem,
      });
      const binding: DeviceBinding = { deviceId, publicPem: pem, userId: userId.trim(), name: name.trim() };
      saveBinding(binding);
      await savePrivateKey(kp.privateKey);
      log(`Gerät registriert: ${resp.message}`, 'ok');
      setStep('done');
      onRegistered(binding, kp.privateKey);
    } catch (err) {
      setError(`Registrierung fehlgeschlagen: ${(err as Error).message}`);
      log(`Registrierung fehlgeschlagen: ${(err as Error).message}`, 'err');
      setStep('form');
    }
  }

  const canSubmit = !busy && userId.trim() && name.trim() && code.trim();

  return (
    <div className="flex flex-col gap-6">
      {/* Icon + title */}
      <div className="text-center pt-4 flex flex-col items-center">
        <div className="w-16 h-16 rounded-2xl bg-[--color-accent]/20 flex items-center justify-center text-4xl mb-3 leading-none">
          🔒
        </div>
        <h2 className="text-xl font-bold">Gerät registrieren</h2>
        <p className="text-sm text-[--color-text-dim] mt-1">
          Einmalige Einrichtung deiner Gerätebindung
        </p>
      </div>

      {/* Form */}
      <div className="flex flex-col gap-3">
        <input
          className="input"
          placeholder="User-ID (vom Admin)"
          value={userId}
          onChange={e => setUserId(e.target.value)}
          disabled={busy}
        />
        <input
          className="input"
          placeholder="Dein Name"
          value={name}
          onChange={e => setName(e.target.value)}
          disabled={busy}
        />
        <input
          className="input"
          placeholder="Freischaltcode"
          type="password"
          autoComplete="off"
          value={code}
          onChange={e => setCode(e.target.value)}
          disabled={busy}
          onKeyDown={e => e.key === 'Enter' && canSubmit && handleSubmit()}
        />
      </div>

      {/* Error */}
      {error && (
        <p className="text-sm text-red-400 text-center">{error}</p>
      )}

      {/* Primary action */}
      <button
        className="btn-primary"
        onClick={handleSubmit}
        disabled={!canSubmit}
      >
        {step === 'keygen'       && <><Spinner /> Schlüssel generieren…</>}
        {step === 'registering'  && <><Spinner /> Registrieren…</>}
        {(step === 'form' || step === 'done') && 'Gerät einrichten'}
      </button>

      {/* Config (collapsed, secondary) */}
      <details className="group">
        <summary className="text-xs text-[--color-text-dim] cursor-pointer select-none text-center hover:text-[--color-text] transition-colors list-none">
          ⚙ Erweiterte Einstellungen
        </summary>
        <div className="mt-3 flex flex-col gap-2">
          <input
            className="input text-xs"
            placeholder="Backend URL (leer = gleiche Origin)"
            value={baseUrl}
            onChange={e => onBaseUrlChange(e.target.value)}
          />
          <div className="flex items-center gap-2">
            <input
              className="input text-xs flex-1 font-mono"
              value={deviceId}
              readOnly
            />
          </div>
        </div>
      </details>
    </div>
  );
}
