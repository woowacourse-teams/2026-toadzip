// Server logout owns revocation; local listeners must never delay clearing the session UI.
let logoutHandler: (() => Promise<void>) | null = null
export function registerPushLogoutHandler(handler: () => Promise<void>) {
  logoutHandler = handler
  return () => { if (logoutHandler === handler) logoutHandler = null }
}
export function afterNotificationLogout() {
  try { void logoutHandler?.().catch(() => {}) } catch { /* Server logout has already succeeded. */ }
}
