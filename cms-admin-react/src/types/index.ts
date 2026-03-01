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
