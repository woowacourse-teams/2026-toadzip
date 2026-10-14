import { useNotificationInbox } from './NotificationInboxContext'
import styles from './NotificationInbox.module.css'
export function NotificationBadge() {
  const context = useNotificationInbox()
  const count = context?.unreadCount
  return count ? <span className={styles.badge} aria-label={`읽지 않은 알림 ${count}개`}>{count > 99 ? '99+' : count}</span> : null
}
