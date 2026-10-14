import { initializeApp } from 'firebase/app'
import { getMessaging } from 'firebase/messaging/sw'
const config = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY, projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID, messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
}
if (Object.values(config).every(value => typeof value === 'string' && value.trim())) {
  // FCM displays the notification payload itself; another showNotification would duplicate it.
  getMessaging(initializeApp(config, 'notification-delivery'))
}
