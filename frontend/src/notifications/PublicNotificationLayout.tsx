import { BookmarkProvider } from '../user/bookmarks/BookmarkProvider'
import { NotificationPushOnboarding } from './NotificationPushOnboarding'
import { Outlet, useLocation } from 'react-router'
import { NotificationInterestProvider } from '../public-housing/interest/NotificationInterest'
import { NotificationPushProvider } from './NotificationPushProvider'
import { NotificationInboxProvider } from './NotificationInboxProvider'
export function PublicNotificationLayout() {
  const location = useLocation()
  return <NotificationInterestProvider><NotificationInboxProvider navigationKey={location.key}><NotificationPushProvider><BookmarkProvider><NotificationPushOnboarding /><Outlet /></BookmarkProvider></NotificationPushProvider></NotificationInboxProvider></NotificationInterestProvider>
}
