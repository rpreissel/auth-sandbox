import { useState } from 'react';
import type { Device } from '../types';
import { fmtDate } from '../utils/date';

interface Props {
  devices: Device[];
  onRefresh: () => void;
  onDelete: (id: string, userId: string) => void;
  count: number;
}

export function DevicesTab({ devices, onRefresh, onDelete, count }: Props) {
  return (
    <div>
      <div className="row" style={{ marginBottom: '1rem' }}>
        <button className="btn btn-secondary btn-sm" onClick={onRefresh}>
          &#8635; Refresh
        </button>
        <span className="mono">{count} device{count !== 1 ? 's' : ''}</span>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>User ID</th>
              <th>Name</th>
              <th>Device ID</th>
              <th>Keycloak User ID</th>
              <th>Created</th>
              <th>ID</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {devices.length === 0 ? (
              <tr>
                <td colSpan={7} className="empty-state">
                  No devices registered yet.
                </td>
              </tr>
            ) : (
              devices.map(d => (
                <DeviceRow
                  key={d.id}
                  device={d}
                  onDelete={onDelete}
                />
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function DeviceRow({
  device: d,
  onDelete,
}: {
  device: Device;
  onDelete: (id: string, userId: string) => void;
}) {
  const [confirming, setConfirming] = useState(false);

  function handleDelete() {
    if (!confirming) {
      setConfirming(true);
      return;
    }
    onDelete(d.id, d.userId);
    setConfirming(false);
  }

  return (
    <tr>
      <td>{d.userId}</td>
      <td>{d.name}</td>
      <td style={{ fontSize: '.72rem' }}>{d.deviceId}</td>
      <td style={{ fontSize: '.72rem' }}>{d.keycloakUserId ?? '—'}</td>
      <td>{fmtDate(d.createdAt)}</td>
      <td style={{ fontSize: '.7rem', color: 'var(--text-dim)' }}>{d.id}</td>
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
