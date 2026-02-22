import type { Device, RegistrationCode, SyncResult } from '../types';

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

interface Credentials {
  username: string;
  password: string;
}

function basicAuth(creds: Credentials): string {
  return 'Basic ' + btoa(creds.username + ':' + creds.password);
}

async function apiFetch<T>(
  method: string,
  path: string,
  creds: Credentials,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: basicAuth(creds),
    Accept: 'application/json',
  };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  const resp = await fetch(path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (resp.status === 401) {
    throw new ApiError('Unauthorized', 401);
  }
  if (resp.status === 204) {
    return null as T;
  }

  const json = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    const msg =
      (json as { message?: string; error?: string }).message ??
      (json as { message?: string; error?: string }).error ??
      resp.statusText;
    throw new ApiError(`HTTP ${resp.status}: ${msg}`, resp.status);
  }
  return json as T;
}

export const api = {
  getRegistrationCodes(creds: Credentials): Promise<RegistrationCode[]> {
    return apiFetch('GET', '/api/v1/admin/registration-codes', creds);
  },

  createRegistrationCode(
    creds: Credentials,
    payload: { userId: string; name: string; activationCode: string },
  ): Promise<RegistrationCode> {
    return apiFetch('POST', '/api/v1/admin/registration-codes', creds, payload);
  },

  deleteRegistrationCode(creds: Credentials, id: string): Promise<null> {
    return apiFetch('DELETE', `/api/v1/admin/registration-codes/${id}`, creds);
  },

  syncRegistrationCodes(creds: Credentials): Promise<SyncResult> {
    return apiFetch('POST', '/api/v1/admin/registration-codes/sync', creds);
  },

  getDevices(creds: Credentials): Promise<Device[]> {
    return apiFetch('GET', '/api/v1/admin/devices', creds);
  },

  deleteDevice(creds: Credentials, id: string): Promise<null> {
    return apiFetch('DELETE', `/api/v1/admin/devices/${id}`, creds);
  },
};
