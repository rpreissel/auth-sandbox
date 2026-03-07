import { ApiError, handleApiResponse } from '@auth-sandbox/utils';
import type { CmsPage, CmsPageRequest } from '../types';

interface Credentials {
  accessToken: string;
}

function bearerAuth(creds: Credentials): string {
  return 'Bearer ' + creds.accessToken;
}

async function apiFetch<T>(
  method: string,
  path: string,
  creds: Credentials,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: bearerAuth(creds),
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
  getPages(creds: Credentials): Promise<CmsPage[]> {
    return apiFetch('GET', '/api/v1/cms/pages', creds);
  },

  createPage(creds: Credentials, payload: CmsPageRequest): Promise<CmsPage> {
    return apiFetch('POST', '/api/v1/cms/pages', creds, payload);
  },

  deletePage(creds: Credentials, id: string): Promise<null> {
    return apiFetch('DELETE', `/api/v1/cms/pages/${id}`, creds);
  },
};
