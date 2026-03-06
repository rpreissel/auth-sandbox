import type { Device, RegistrationCode, SyncResult, CleanupResult } from '../types';
import { ApiError, handleApiResponse } from '@auth-sandbox/utils';

async function apiFetch<T>(
  method: string,
  path: string,
  accessToken: string,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${accessToken}`,
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
  getRegistrationCodes(accessToken: string): Promise<RegistrationCode[]> {
    return apiFetch('GET', '/api/v1/admin/registration-codes', accessToken);
  },

  createRegistrationCode(
    accessToken: string,
    payload: { userId: string; name: string; activationCode: string },
  ): Promise<RegistrationCode> {
    return apiFetch('POST', '/api/v1/admin/registration-codes', accessToken, payload);
  },

  deleteRegistrationCode(accessToken: string, id: string): Promise<null> {
    return apiFetch('DELETE', `/api/v1/admin/registration-codes/${id}`, accessToken);
  },

  syncRegistrationCodes(accessToken: string): Promise<SyncResult> {
    return apiFetch('POST', '/api/v1/admin/registration-codes/sync', accessToken);
  },

  cleanupExpiredCodes(accessToken: string): Promise<CleanupResult> {
    return apiFetch('POST', '/api/v1/admin/registration-codes/cleanup', accessToken);
  },

  getDevices(accessToken: string): Promise<Device[]> {
    return apiFetch('GET', '/api/v1/admin/devices', accessToken);
  },

  deleteDevice(accessToken: string, id: string): Promise<null> {
    return apiFetch('DELETE', `/api/v1/admin/devices/${id}`, accessToken);
  },
};
