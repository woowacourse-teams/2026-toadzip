const STORAGE_KEY = 'toadzip:mobile-login-return'
const MAX_AGE = 30 * 60 * 1000

export function safeLoginReturn(value: string | null): string {
  if (!value?.startsWith('/') || value.startsWith('//') || value.includes('\\')) return '/explore'
  try {
    const url = new URL(value, window.location.origin)
    if (url.origin !== window.location.origin || !['/explore', '/notifications', '/mypage/notifications'].includes(url.pathname)) return '/explore'
    url.searchParams.delete('login')
    url.searchParams.delete('reason')
    return url.pathname + url.search + url.hash
  } catch { return '/explore' }
}

export function rememberLoginReturn(returnTo: string, pending = false) {
  try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ returnTo: safeLoginReturn(returnTo), pending, expiresAt: Date.now() + MAX_AGE })) } catch { /* URL returnTo still supports returning without storage. */ }
}

export function readLoginReturn(pendingOnly = false): string | null {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null')
    if (!value || typeof value !== 'object' || !('returnTo' in value) || typeof value.returnTo !== 'string'
      || !('expiresAt' in value) || typeof value.expiresAt !== 'number' || value.expiresAt <= Date.now()
      || (pendingOnly && (!('pending' in value) || value.pending !== true))) return null
    return safeLoginReturn(value.returnTo)
  } catch { return null }
}

export function clearLoginReturn() {
  try { sessionStorage.removeItem(STORAGE_KEY) } catch { /* Storage can be unavailable. */ }
}

// Browser Back returns to an explorer entry whose camera query was consumed.
// Restore only the matching entry and only a login that has not started OAuth.
export function loginReturnForBack(current: string): string | null {
  const saved = readLoginReturn()
  if (!saved || readLoginReturn(true)) return null
  const stripCamera = (value: string) => {
    const url = new URL(safeLoginReturn(value), window.location.origin)
    for (const key of ['mapLat', 'mapLng', 'mapZoom']) url.searchParams.delete(key)
    return url.pathname + url.search + url.hash
  }
  if (saved === current || stripCamera(saved) !== current) return null
  return saved
}

export function loginPageUrl(returnTo: string, failed = false) {
  const query = new URLSearchParams({ returnTo: safeLoginReturn(returnTo) })
  if (failed) query.set('login', 'failed')
  return `/login?${query}`
}
