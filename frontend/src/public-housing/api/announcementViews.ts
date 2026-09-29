const VIEWER_KEY = 'toadzip.announcement-viewer'
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export async function recordAnnouncementView(
  apiBaseUrl: string,
  fetcher: typeof fetch,
  announcementId: string,
  signal: AbortSignal,
): Promise<number | null> {
  signal.throwIfAborted()
  try {
    if (!navigator.locks) return null
    const boundedSignal = AbortSignal.any([signal, AbortSignal.timeout(3000)])
    // Serialize identity creation and CSRF cookie initialization across tabs.
    return await navigator.locks.request(VIEWER_KEY, { signal: boundedSignal }, async () => {
      boundedSignal.throwIfAborted()
      const viewerId = persistentViewerId()
      const csrfResponse = await fetcher(`${apiBaseUrl}/api/auth/csrf`, {
        credentials: 'include', cache: 'no-store', signal: boundedSignal,
      })
      if (!csrfResponse.ok) return null
      const csrf: unknown = await csrfResponse.json()
      if (!isRecord(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string') return null
      boundedSignal.throwIfAborted()
      const response = await fetcher(`${apiBaseUrl}/api/v1/announcements/${announcementId}/views`, {
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf.token },
        body: JSON.stringify({ viewerId }),
        signal: boundedSignal,
      })
      if (!response.ok) return null
      const body: unknown = await response.json()
      if (!isRecord(body) || !isRecord(body.data)) return null
      const count = body.data.viewCount
      return typeof count === 'number' && Number.isSafeInteger(count) && count >= 0 ? count : null
    })
  } catch {
    signal.throwIfAborted()
    // Counting is optional; never invent a temporary identity or fail a usable detail.
    return null
  }
}

function persistentViewerId(): string {
  const stored = localStorage.getItem(VIEWER_KEY)
  if (stored !== null && UUID_PATTERN.test(stored)) return stored
  const viewerId = crypto.randomUUID()
  localStorage.setItem(VIEWER_KEY, viewerId)
  if (localStorage.getItem(VIEWER_KEY) !== viewerId) throw new Error('Browser storage is unavailable')
  return viewerId
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
