import { BookmarkProvider } from '../user/bookmarks/BookmarkProvider'
import { NotificationPushOnboarding } from './NotificationPushOnboarding'
import { Outlet, useLocation } from 'react-router'
import { NotificationInterestProvider } from '../public-housing/interest/NotificationInterest'
import { NotificationPushProvider } from './NotificationPushProvider'
import { NotificationInboxProvider } from './NotificationInboxProvider'
import { ConditionsProvider } from '../user/conditions/ConditionsProvider'
export function PublicNotificationLayout() {
  const location = useLocation()
  return <NotificationInterestProvider><NotificationInboxProvider navigationKey={location.key}><NotificationPushProvider><BookmarkProvider><ConditionsProvider><NotificationPushOnboarding /><Outlet /></ConditionsProvider></BookmarkProvider></NotificationPushProvider></NotificationInboxProvider></NotificationInterestProvider>
}
