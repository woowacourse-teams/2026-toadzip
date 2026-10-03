import { FailureReviewPage } from './admin/ingest/FailureReviewPage'
import { Link, Navigate, Route, Routes } from 'react-router'
import { AdminAuthProvider } from './admin/auth/AdminAuthProvider'
import { HousingComplexRegistrationPage } from './admin/registration/HousingComplexRegistrationPage'
import { AnnouncementRegistrationPage } from './admin/registration/AnnouncementRegistrationPage'
import { LocationDataPage } from './admin/ingest/LocationDataPage'
import { ManagementList } from './admin/management/ManagementList'
import { ManagementDetail } from './admin/management/ManagementDetail'
import { AdminHome } from './admin/auth/AdminHome'
import { AdminLayout } from './admin/auth/AdminLayout'
import { GuestCancellationAdminPage } from './admin/GuestCancellationAdminPage'
import { LoginPage } from './admin/auth/LoginPage'
import { RequireAdmin } from './admin/auth/RequireAdmin'
import { DefaultPublicHousingExplorer } from './public-housing/DefaultPublicHousingExplorer.tsx'
import { LoginPage as UserLoginPage } from './user/auth/LoginPage'
import { BrandLink } from './BrandLink'
import { NotificationInterestProvider, NotificationInterestSessionControl } from './public-housing/interest/NotificationInterest'
import { GuestCancellationPage } from './public-housing/interest/GuestCancellationPage'

function Home() {
  return (
    <NotificationInterestProvider>
      <div className="app-shell">
        <header className="service-header" aria-label="서비스 헤더">
          <BrandLink />
          <NotificationInterestSessionControl />
        </header>
        <main className="map-main">
          <DefaultPublicHousingExplorer />
        </main>
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

function AdminRoutes() {
  return (
    <AdminAuthProvider>
      <Routes>
        <Route path="login" element={<LoginPage />} />
        <Route element={<RequireAdmin />}>
          <Route element={<AdminLayout />}>
            <Route index element={<Navigate to="complexes" replace />} />
            <Route path="ingest" element={<AdminHome />} />
            <Route path="complexes" element={<ManagementList resource="complexes" />} />
            <Route path="complexes/new" element={<HousingComplexRegistrationPage />} />
            <Route path="complexes/:id" element={<ManagementDetail resource="complexes" />} />
            <Route path="announcements" element={<ManagementList resource="announcements" />} />
            <Route path="announcements/new" element={<AnnouncementRegistrationPage />} />
            <Route path="announcements/:id" element={<ManagementDetail resource="announcements" />} />
            <Route path="failures" element={<FailureReviewPage />} />
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
      <Route path="/login" element={<UserLoginPage />} />
      <Route path="/notifications/cancel" element={<GuestCancellationPage />} />
      <Route path="/admin/*" element={<AdminRoutes />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}
