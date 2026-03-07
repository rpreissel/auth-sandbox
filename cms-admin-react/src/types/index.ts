export interface CmsPage {
  id: string;
  name: string;
  key: string;
  protectionLevel: string;
  contentPath: string;
  createdAt: string;
}

export interface CmsPageRequest {
  name: string;
  key: string;
  protectionLevel: string;
  contentPath: string;
}

export type StatusKind = 'idle' | 'pending' | 'success' | 'error';

export interface OidcTokens {
  access_token: string;
  id_token: string;
  refresh_token: string;
}
