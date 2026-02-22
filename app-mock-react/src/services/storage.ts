import type { DeviceBinding } from '../types';

const KEY_BINDING = 'device_binding';
const KEY_PRIVATE = 'device_private_key_handle';

// ── DeviceBinding (plain JSON) ────────────────────────────────────

export function loadBinding(): DeviceBinding | null {
  try {
    const raw = localStorage.getItem(KEY_BINDING);
    return raw ? (JSON.parse(raw) as DeviceBinding) : null;
  } catch {
    return null;
  }
}

export function saveBinding(binding: DeviceBinding): void {
  localStorage.setItem(KEY_BINDING, JSON.stringify(binding));
}

export function clearBinding(): void {
  localStorage.removeItem(KEY_BINDING);
  localStorage.removeItem(KEY_PRIVATE);
}

// ── CryptoKey storage via IndexedDB ──────────────────────────────
// We cannot put a non-extractable CryptoKey in localStorage, so we
// use IndexedDB instead (same-origin, survives page reload).

const IDB_DB   = 'device-auth';
const IDB_STORE = 'keys';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(IDB_STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror   = () => reject(req.error);
  });
}

export async function savePrivateKey(key: CryptoKey): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx  = db.transaction(IDB_STORE, 'readwrite');
    const req = tx.objectStore(IDB_STORE).put(key, KEY_PRIVATE);
    req.onsuccess = () => resolve();
    req.onerror   = () => reject(req.error);
  });
}

export async function loadPrivateKey(): Promise<CryptoKey | null> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx  = db.transaction(IDB_STORE, 'readonly');
    const req = tx.objectStore(IDB_STORE).get(KEY_PRIVATE);
    req.onsuccess = () => resolve((req.result as CryptoKey) ?? null);
    req.onerror   = () => reject(req.error);
  });
}

export async function clearPrivateKey(): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx  = db.transaction(IDB_STORE, 'readwrite');
    const req = tx.objectStore(IDB_STORE).delete(KEY_PRIVATE);
    req.onsuccess = () => resolve();
    req.onerror   = () => reject(req.error);
  });
}
