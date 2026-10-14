import type { PushSdk } from './firebasePushClient'
export const pushUiEnabled = import.meta.env.VITE_NOTIFICATION_PUSH_ENABLED === 'true'
export async function preparePushSdk(): Promise<PushSdk> {
  const config = {
    apiKey: import.meta.env.VITE_FIREBASE_API_KEY, projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
    appId: import.meta.env.VITE_FIREBASE_APP_ID, messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  }
  const vapidKey = import.meta.env.VITE_FIREBASE_VAPID_PUBLIC_KEY
  if (!pushUiEnabled || !Object.values(config).every(value => typeof value === 'string' && value.trim()) || !vapidKey) throw new Error('푸시 알림을 준비하고 있어요.')
  if (!globalThis.isSecureContext || !('Notification' in window) || !('serviceWorker' in navigator)) throw new Error('이 브라우저에서는 푸시 알림을 지원하지 않아요.')
  const [appSdk, messagingSdk] = await Promise.all([import('firebase/app'), import('firebase/messaging')])
  if (!await messagingSdk.isSupported()) throw new Error('이 브라우저에서는 푸시 알림을 지원하지 않아요.')
  const app = appSdk.getApps().find(value => value.name === 'notification-delivery') ?? appSdk.initializeApp(config, 'notification-delivery')
  const messaging = messagingSdk.getMessaging(app)
  // Serialize SDK changes across tabs sharing one Firebase installation.
  const serialize = (task: () => Promise<void>) => navigator.locks ? navigator.locks.request('toadzip-push-sdk', task) : task()
  const sdk: PushSdk = {
    permission: () => Notification.permission,
    requestPermission: () => Notification.requestPermission(),
    register: () => serialize(async () => {
      const serviceWorkerRegistration = await navigator.serviceWorker.register('/firebase-messaging-sw.js', { scope: '/', updateViaCache: 'none' })
      await messagingSdk.register(messaging, { vapidKey, serviceWorkerRegistration })
    }),
    onRegistered: listener => messagingSdk.onRegistered(messaging, listener),
    onMessage: listener => messagingSdk.onMessage(messaging, () => listener()),
  }
  return sdk
}
