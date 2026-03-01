export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

type JsonObject = Record<string, unknown>;

function extractErrorMessage(json: JsonObject): string {
  return (json['message'] ?? json['error'] ?? json['error_description'] ?? '') as string;
}

export async function handleApiResponse<T>(resp: Response): Promise<T> {
  if (resp.status === 204) {
    return null as T;
  }

  const json = await resp.json().catch(() => ({})) as JsonObject;
  if (!resp.ok) {
    const msg = extractErrorMessage(json) || resp.statusText;
    throw new ApiError(`HTTP ${resp.status}: ${msg}`, resp.status);
  }
  return json as T;
}

export function buildApiError(message: string, status: number): ApiError {
  return new ApiError(message, status);
}
