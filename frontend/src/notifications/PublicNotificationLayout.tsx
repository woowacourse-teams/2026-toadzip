import { Outlet } from 'react-router'
import { NotificationInterestProvider } from '../public-housing/interest/NotificationInterest'
import { NotificationPushProvider } from './NotificationPushProvider'
import { NotificationInboxProvider } from './NotificationInboxProvider'
export function PublicNotificationLayout() {
  return <NotificationInterestProvider><NotificationInboxProvider><NotificationPushProvider><Outlet /></NotificationPushProvider></NotificationInboxProvider></NotificationInterestProvider>
}
