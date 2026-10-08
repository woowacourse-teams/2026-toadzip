import { FailureReviewPage } from './admin/ingest/FailureReviewPage'
import { SourceDataPage } from './admin/ingest/SourceDataPage'
import { Link, Navigate, Route, Routes, useLocation } from 'react-router'
import { AdminAuthProvider } from './admin/auth/AdminAuthProvider'
import { LocationDataPage } from './admin/ingest/LocationDataPage'
import { ManagementWorkspace } from './admin/management/ManagementWorkspace'
import { AdminHome } from './admin/auth/AdminHome'
import { AdminLayout } from './admin/auth/AdminLayout'
import { GuestCancellationAdminPage } from './admin/GuestCancellationAdminPage'
import { LoginPage } from './admin/auth/LoginPage'
import { RequireAdmin } from './admin/auth/RequireAdmin'
import { DefaultPublicHousingExplorer } from './public-housing/DefaultPublicHousingExplorer.tsx'
import { BrandLink } from './BrandLink'
import { NotificationInterestProvider, NotificationInterestSessionControl } from './public-housing/interest/NotificationInterest'
import { NotificationPage } from './public-housing/interest/NotificationPage'
import { GuestCancellationPage } from './public-housing/interest/GuestCancellationPage'
import { UserListPage } from './admin/users/UserListPage'
import { UserDetailPage } from './admin/users/UserDetailPage'
import { FeedbackPage } from './feedback/FeedbackPage'
import { FeedbackListPage } from './admin/feedback/FeedbackListPage'

function Home() {
  return (
    <NotificationInterestProvider>
      <div className="app-shell">
        <div className="service-rail-brand"><BrandLink /></div>
        <div className="service-rail-account"><Link className="service-feedback-link" to="/feedback">의견 보내기</Link><NotificationInterestSessionControl presentation="rail" /></div>
        <main className="map-main"><DefaultPublicHousingExplorer /></main>
      </div>
    </NotificationInterestProvider>
  )
}

function NotFound() {
  return (
    <main className="not-found-main">
      <BrandLink />
      <h1>페이지를 찾을 수 없습니다.</h1>
      <p>입력한 주소를 다시 확인해 주세요.</p>
      <Link to="/">지도로 돌아가기</Link>
    </main>
  )
}

function LegacyLoginRedirect() {
  const { search, hash } = useLocation()
  const params = new URLSearchParams(search)
  params.set('login', params.get('login') === 'failed' ? 'failed' : 'required')
  return <Navigate to={{ pathname: '/', search: `?${params}`, hash }} replace />
}

function AdminRoutes() {
  return (
    <AdminAuthProvider>
      <Routes>
        <Route path="login" element={<LoginPage />} />
        <Route element={<RequireAdmin />}>
          <Route element={<AdminLayout />}>
            <Route index element={<Navigate to="complexes" replace />} />
            <Route path="ingest" element={<AdminHome />} />
            <Route path="complexes/:id?" element={<ManagementWorkspace resource="complexes" />} />
            <Route path="announcements/:id?" element={<ManagementWorkspace resource="announcements" />} />
            <Route path="users" element={<UserListPage />} />
            <Route path="users/:id" element={<UserDetailPage />} />
            <Route path="feedback" element={<FeedbackListPage />} />
            <Route path="failures" element={<FailureReviewPage />} />
            <Route path="sources" element={<SourceDataPage />} />
            <Route path="locations" element={<LocationDataPage />} />
            <Route path="notification-cancellations" element={<GuestCancellationAdminPage />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AdminAuthProvider>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<LegacyLoginRedirect />} />
      <Route path="/feedback" element={<FeedbackPage />} />
      <Route path="/notifications" element={<Navigate to="/?inbox=open" replace />} />
      <Route path="/mypage/notifications" element={<NotificationPage management />} />
      <Route path="/notifications/cancel" element={<GuestCancellationPage />} />
      <Route path="/admin/*" element={<AdminRoutes />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}
