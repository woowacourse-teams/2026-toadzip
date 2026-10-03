export interface HttpErrorBody {
  readonly code: string | null
  readonly message: string | null
  readonly traceId: string | null
}

/** Malformed error bodies retain the HTTP status; cancellation remains cancellation. */
export async function decodeHttpErrorBody(response: Response): Promise<HttpErrorBody> {
  let value: unknown
  try {
    value = await response.json()
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    value = null
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { code: null, message: null, traceId: null }
  }
  return {
    code: 'code' in value && typeof value.code === 'string' ? value.code : null,
    message: 'message' in value && typeof value.message === 'string' ? value.message : null,
    traceId: 'traceId' in value && typeof value.traceId === 'string' ? value.traceId : null,
  }
}

export function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}
