import type { Device, RegistrationCode, SyncResult, CleanupResult } from '../types';
import { ApiError, handleApiResponse } from '@auth-sandbox/utils';

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

  return handleApiResponse<T>(resp);
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

  cleanupExpiredCodes(creds: Credentials): Promise<CleanupResult> {
    return apiFetch('POST', '/api/v1/admin/registration-codes/cleanup', creds);
  },

  getDevices(creds: Credentials): Promise<Device[]> {
    return apiFetch('GET', '/api/v1/admin/devices', creds);
  },

  deleteDevice(creds: Credentials, id: string): Promise<null> {
    return apiFetch('DELETE', `/api/v1/admin/devices/${id}`, creds);
  },
};
