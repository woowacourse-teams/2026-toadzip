import { afterEach, expect, it, vi } from 'vitest'
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.resetModules() })
it('설정이 없으면 브라우저 권한이나 FCM에 접근하지 않는다', async () => {
  vi.stubEnv('VITE_NOTIFICATION_PUSH_ENABLED', 'true')
  vi.stubEnv('VITE_FIREBASE_API_KEY', '')
  const { preparePushSdk } = await import('./firebasePushSdk')
  await expect(preparePushSdk()).rejects.toThrow('준비')
})
it('비보안 origin에서는 SDK 등록 전에 미지원으로 안내한다', async () => {
  vi.stubEnv('VITE_NOTIFICATION_PUSH_ENABLED', 'true')
  for (const key of ['VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_PROJECT_ID', 'VITE_FIREBASE_APP_ID', 'VITE_FIREBASE_MESSAGING_SENDER_ID', 'VITE_FIREBASE_VAPID_PUBLIC_KEY']) vi.stubEnv(key, 'public-test-placeholder')
  vi.stubGlobal('isSecureContext', false)
  const { preparePushSdk } = await import('./firebasePushSdk')
  await expect(preparePushSdk()).rejects.toThrow('지원하지')
})
