import { useState } from 'react';
import type { RegistrationCode } from '../types';

function fmtDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', {
    dateStyle: 'short',
    timeStyle: 'medium',
  });
}

function isExpired(expiresAt: string | null): boolean {
  if (!expiresAt) return false;
  return new Date(expiresAt) < new Date();
}

interface CreateFormProps {
  onSubmit: (userId: string, name: string, activationCode: string) => Promise<void>;
  onCancel: () => void;
}

function CreateForm({ onSubmit, onCancel }: CreateFormProps) {
  const [userId, setUserId] = useState('');
  const [name, setName] = useState('');
  const [activationCode, setActivationCode] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit() {
    if (!userId || !name || !activationCode) return;
    setLoading(true);
    try {
      await onSubmit(userId, name, activationCode);
      setUserId('');
      setName('');
      setActivationCode('');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="create-form open">
      <div
        style={{
          fontSize: '.8rem',
          fontWeight: 700,
          textTransform: 'uppercase',
          letterSpacing: '.07em',
          color: 'var(--text-dim)',
          marginBottom: '.75rem',
        }}
      >
        New Registration Code
      </div>
      <div className="create-form-grid">
        <div className="field" style={{ marginBottom: 0 }}>
          <label htmlFor="create-userId">
            User ID <span style={{ color: 'var(--error)' }}>*</span>
          </label>
          <input
            id="create-userId"
            name="userId"
            type="text"
            value={userId}
            onChange={e => setUserId(e.target.value)}
            placeholder="e.g. user-alice"
          />
        </div>
        <div className="field" style={{ marginBottom: 0 }}>
          <label htmlFor="create-name">
            Name <span style={{ color: 'var(--error)' }}>*</span>
          </label>
          <input
            id="create-name"
            name="name"
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder="Full name"
          />
        </div>
        <div className="field" style={{ marginBottom: 0 }}>
          <label htmlFor="create-activationCode">
            Activation Code <span style={{ color: 'var(--error)' }}>*</span>
          </label>
          <input
            id="create-activationCode"
            name="activationCode"
            type="text"
            value={activationCode}
            onChange={e => setActivationCode(e.target.value)}
            placeholder="One-time password"
            autoComplete="off"
          />
        </div>
      </div>
      <div style={{ display: 'flex', gap: '.6rem', justifyContent: 'flex-end' }}>
        <button className="btn btn-secondary btn-sm" onClick={onCancel} disabled={loading}>
          Cancel
        </button>
        <button
          className="btn btn-primary btn-sm"
          onClick={() => void handleSubmit()}
          disabled={loading || !userId || !name || !activationCode}
        >
          {loading ? 'Creating…' : 'Create'}
        </button>
      </div>
    </div>
  );
}

interface Props {
  codes: RegistrationCode[];
  onRefresh: () => void;
  onDelete: (id: string, userId: string) => void;
  onCreate: (userId: string, name: string, activationCode: string) => Promise<void>;
  onSync: () => Promise<void>;
  count: number;
}

export function RegCodesTab({ codes, onRefresh, onDelete, onCreate, onSync, count }: Props) {
  const [showCreate, setShowCreate] = useState(false);
  const [syncing, setSyncing] = useState(false);

  async function handleCreate(userId: string, name: string, activationCode: string) {
    await onCreate(userId, name, activationCode);
    setShowCreate(false);
  }

  async function handleSync() {
    setSyncing(true);
    try {
      await onSync();
    } finally {
      setSyncing(false);
    }
  }

  return (
    <div>
      {showCreate && (
        <CreateForm
          onSubmit={handleCreate}
          onCancel={() => setShowCreate(false)}
        />
      )}

      <div className="row" style={{ marginBottom: '1rem' }}>
        {!showCreate && (
          <button className="btn btn-primary btn-sm" onClick={() => setShowCreate(true)}>
            + New Code
          </button>
        )}
        <button className="btn btn-secondary btn-sm" onClick={onRefresh}>
          &#8635; Refresh
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => void handleSync()}
          disabled={syncing}
        >
          {syncing ? 'Syncing…' : '\u21C5 Sync Keycloak'}
        </button>
        <span className="mono">{count} code{count !== 1 ? 's' : ''}</span>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User ID</th>
              <th>Name</th>
              <th>Activation Code</th>
              <th>Use Count</th>
              <th>Created</th>
              <th>Expires At</th>
              <th>ID</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {codes.length === 0 ? (
              <tr>
                <td colSpan={8} className="empty-state">
                  No registration codes yet.
                </td>
              </tr>
            ) : (
              codes.map(c => (
                <RegCodeRow key={c.id} code={c} onDelete={onDelete} />
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function RegCodeRow({
  code: c,
  onDelete,
}: {
  code: RegistrationCode;
  onDelete: (id: string, userId: string) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [showCode, setShowCode] = useState(false);
  const expired = isExpired(c.expiresAt);

  function handleDelete() {
    if (!confirming) {
      setConfirming(true);
      return;
    }
    onDelete(c.id, c.userId);
    setConfirming(false);
  }

  return (
    <tr>
      <td>{c.userId}</td>
      <td>{c.name}</td>
      <td>
        <span
          className="mono"
          style={{
            fontSize: '.8rem',
            cursor: 'pointer',
            userSelect: showCode ? 'text' : 'none',
            color: showCode ? 'var(--text)' : 'var(--text-dim)',
            letterSpacing: showCode ? 'normal' : '.15em',
          }}
          onClick={() => setShowCode(v => !v)}
          title={showCode ? 'Click to hide' : 'Click to reveal'}
        >
          {showCode ? c.activationCode : '••••••••'}
        </span>
      </td>
      <td style={{ textAlign: 'center' }}>
        {c.useCount > 0 ? (
          <span className="badge badge-used">{c.useCount}</span>
        ) : (
          <span className="badge badge-unused">0</span>
        )}
      </td>
      <td>{fmtDate(c.createdAt)}</td>
      <td style={{ color: expired ? 'var(--error)' : 'var(--text)' }}>
        {fmtDate(c.expiresAt)}
        {expired && (
          <span style={{ marginLeft: '.4rem', fontSize: '.7rem', color: 'var(--error)' }}>
            (expired)
          </span>
        )}
      </td>
      <td style={{ fontSize: '.7rem', color: 'var(--text-dim)' }}>{c.id}</td>
      <td className="td-actions">
        {confirming ? (
          <>
            <button
              className="btn btn-danger btn-sm"
              onClick={handleDelete}
              style={{ marginRight: '.3rem' }}
            >
              Confirm
            </button>
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => setConfirming(false)}
            >
              Cancel
            </button>
          </>
        ) : (
          <button className="btn btn-danger btn-sm" onClick={handleDelete}>
            Delete
          </button>
        )}
      </td>
    </tr>
  );
}
