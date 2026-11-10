import { useEffect, useRef, useState } from 'react'
import { PdfCorrectionPage } from './admin/announcement-pdf/PdfCorrectionPage'
import { FailureReviewPage } from './admin/ingest/FailureReviewPage'
import { IngestPage } from './admin/ingest/IngestPage'
import { SourceDataPage } from './admin/ingest/SourceDataPage'
import { Link, Navigate, Route, Routes, matchRoutes, useLocation, useNavigationType, useParams } from 'react-router'
import { AdminAuthProvider } from './admin/auth/AdminAuthProvider'
import { LocationDataPage } from './admin/ingest/LocationDataPage'
import { ManagementWorkspace } from './admin/management/ManagementWorkspace'
import { AdminLayout } from './admin/auth/AdminLayout'
import { LoginPage } from './admin/auth/LoginPage'
import { RequireAdmin } from './admin/auth/RequireAdmin'
import { DefaultPublicHousingExplorer } from './public-housing/DefaultPublicHousingExplorer.tsx'
import { rememberExplorerPath } from './user/auth/loginReturn'
import { BrandLink } from './BrandLink'
import { NotificationInterestSessionControl } from './public-housing/interest/NotificationInterest'
import { NotificationPage } from './public-housing/interest/NotificationPage'
import { UserListPage } from './admin/users/UserListPage'
import { UserDetailPage } from './admin/users/UserDetailPage'
import { NotificationManagementPage } from './admin/notifications/NotificationSubscriptions'
import { FeedbackPage } from './feedback/FeedbackPage'
import { FeedbackLink } from './feedback/FeedbackLink'
import { feedbackBackground } from './feedback/feedbackNavigation'
import { FeedbackPrompt } from './feedback/FeedbackPrompt'
import { NotificationDeliveriesPage } from './admin/notifications/NotificationDeliveriesPage'
import { OperationsHome } from './admin/operations/OperationsHome'
import { FeedbackListPage } from './admin/feedback/FeedbackListPage'
import { PrivacyBoundary, PrivacyPolicyPage, PrivacySettingsPage } from './privacy/Privacy'
import { ProductAnalyticsBoundary } from './analytics/ProductAnalyticsBoundary'
import { CampaignStatsBoundary } from './analytics/CampaignStatsBoundary'
import { PublicNotificationLayout } from './notifications/PublicNotificationLayout'
import { LandingPage } from './landing/LandingPage'
import { hasExplorerState } from './landing/explorerState'
import { LoginPage as UserLoginPage } from './user/auth/LoginPage'
import { useMobileViewport } from './public-housing/components/useMobileViewport'
import { parseDetailPath, toDetailPathLocation, toExplorerQueryLocation } from './public-housing/navigation/detailLocation'
import { regionExplorerSearch } from './public-housing/navigation/regionPath'
import { clearLoginReturn, loginReturnForBack, safeLoginReturn } from './user/auth/loginReturn'
import { ExplorerReturnContext } from './public-housing/navigation/ExplorerReturnContext'
import { MobileInstallProvider } from './install/MobileInstallProvider'

function LandingEntry() {
  const { search, hash } = useLocation()
  // Keep existing detail, filter, map and authentication links usable.
  return hasExplorerState(search)
    ? <Navigate to={{ pathname: '/explore', search, hash }} replace />
    : <LandingPage />
}

// `/explore`와 상세 주소는 같은 Home을 써서 상세를 여닫을 때 지도를 다시 만들지 않는다.
function Home() {
  const location = useLocation()
  if (parseDetailPath(location.pathname).kind === 'invalid') return <NotFound />
  return <ExplorerHome />
}

function ExplorerHome() {
  const location = useLocation()
  useEffect(() => { rememberExplorerPath(location.pathname + location.search + location.hash) }, [location])
  const navigationType = useNavigationType()
  const [backReturn] = useState(() => navigationType === 'POP'
    ? { key: location.key, url: loginReturnForBack(location.pathname + location.search + location.hash) }
    : null)
  const returnUrl = useRef<string | null>(null)
  useEffect(() => { if (backReturn?.url) clearLoginReturn() }, [backReturn])
  if (backReturn?.url && backReturn.key === location.key) {
    return <Navigate to={backReturn.url} replace state={location.state} />
  }
  return (
    <ExplorerReturnContext value={returnUrl}>
      <div className="app-shell">
        <div className="service-rail-brand"><BrandLink /></div>
        <div className="service-rail-account"><FeedbackLink className="service-feedback-link">의견 보내기</FeedbackLink><NotificationInterestSessionControl presentation="rail" /></div>
        <main className="map-main"><DefaultPublicHousingExplorer showWelcome={false} /></main>
        <FeedbackPrompt />
      </div>
    </ExplorerReturnContext>
  )
}

// 지역 목록 페이지는 검색엔진용이다. 사람에게는 같은 지역·공급 유형으로 거른 지도 화면을 연다.
function RegionEntry() {
  const { regionCode, rentalType } = useParams()
  const { hash } = useLocation()
  const search = regionExplorerSearch(regionCode, rentalType)
  return search === null ? <NotFound /> : <Navigate to={{ pathname: '/explore', search, hash }} replace />
}

function NotFound() {
  return (
    <main className="not-found-main">
      <BrandLink />
      <h1>페이지를 찾을 수 없어요.</h1>
      <p>입력한 주소를 다시 확인해 주세요.</p>
      <Link to="/explore">지도로 돌아가기</Link>
    </main>
  )
}

function LegacyLoginRedirect() {
  const { search, hash } = useLocation()
  const mobile = useMobileViewport()
  const params = new URLSearchParams(search)
  if (mobile) return <UserLoginPage />
  const returnTo = params.get('returnTo')
  if (returnTo) {
    const target = new URL(safeLoginReturn(returnTo), window.location.origin)
    target.searchParams.set('login', params.get('login') === 'failed' ? 'failed' : 'required')
    return <Navigate to={target.pathname + target.search + target.hash} replace />
  }
  params.set('login', params.get('login') === 'failed' ? 'failed' : 'required')
  return <Navigate to={{ pathname: '/explore', search: `?${params}`, hash }} replace />
}

function LegacyIngestRedirect() {
  const { search, hash } = useLocation()
  const params = new URLSearchParams(search)
  const domain = params.get('domain') === 'complex' ? 'complexes' : 'announcements'
  params.delete('domain')
  return <Navigate to={{ pathname: `/admin/ingest/${domain}`, search: params.toString(), hash }} replace />
}

function AdminRoutes() {
  return (
    <AdminAuthProvider>
      <Routes>
        <Route path="login" element={<LoginPage />} />
        <Route element={<RequireAdmin />}>
          <Route element={<AdminLayout />}>
            <Route index element={<Navigate to="operations" replace />} />
            <Route path="operations" element={<OperationsHome />} />
            <Route path="ingest" element={<LegacyIngestRedirect />} />
            <Route path="ingest/announcements" element={<IngestPage domain="announcement" />} />
            <Route path="ingest/complexes" element={<IngestPage domain="complex" />} />
            <Route path="ingest-v2" element={<LegacyIngestRedirect />} />
            <Route path="complexes/:id?" element={<ManagementWorkspace resource="complexes" />} />
            <Route path="announcements/:id/pdf-correction" element={<PdfCorrectionPage />} />
            <Route path="announcements/:id?" element={<ManagementWorkspace resource="announcements" />} />
            <Route path="complex-coverage" element={<Navigate to="/admin/complexes" replace />} />
            <Route path="users" element={<UserListPage />} />
            <Route path="users/:id" element={<UserDetailPage />} />
            <Route path="notification-deliveries" element={<NotificationDeliveriesPage />} />
            <Route path="notification-subscriptions" element={<NotificationManagementPage />} />
            <Route path="feedback" element={<FeedbackListPage />} />
            <Route path="failures" element={<FailureReviewPage />} />
            <Route path="sources" element={<SourceDataPage />} />
            <Route path="locations" element={<LocationDataPage />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AdminAuthProvider>
  )
}

export default function App() {
  const location = useLocation()
  const { pathname, search, hash, state } = location
  const background = pathname === '/feedback' ? feedbackBackground(state) : null
  // Use the same matching rules as Routes, including encoded paths, before
  // mounting privacy and analytics on the canonical public path.
  if (pathname !== '/explore' && matchRoutes([{ path: '/explore' }], { pathname })) {
    return <Navigate to={{ pathname: '/explore', search, hash }} state={state} replace />
  }
  // 예전 상세 링크(`/explore?complexId=1`)는 상세 주소로 바꿔 열고, 서버 이동 뒤 상세 주소에 남은 상세 쿼리는 지운다.
  const detailPath = toDetailPathLocation(toExplorerQueryLocation({ pathname, search }))
  if (detailPath.pathname !== pathname || detailPath.search !== search) {
    return <Navigate to={{ ...detailPath, hash }} state={state} replace />
  }
  return (
    <MobileInstallProvider>
    {pathname !== '/' && <PrivacyBoundary />}
    <ProductAnalyticsBoundary />
    <CampaignStatsBoundary />
    <Routes location={background ?? location}>
      <Route path="/" element={<LandingEntry />} />
      <Route element={<PublicNotificationLayout />}>
        <Route path="/explore" element={<Home />} />
        <Route path="/complexes/:complexId" element={<Home />} />
        <Route path="/announcements/:announcementId" element={<Home />} />
        <Route path="/notifications" element={<NotificationPage />} />
        <Route path="/mypage" element={<Home />} />
        <Route path="/mypage/:section" element={<Home />} />
      </Route>
      <Route path="/regions/:regionCode" element={<RegionEntry />} />
      <Route path="/regions/:regionCode/:rentalType" element={<RegionEntry />} />
      <Route path="/privacy" element={<PrivacyPolicyPage />} />
      <Route path="/privacy/:key/:version" element={<PrivacyPolicyPage />} />
      <Route path="/privacy/settings" element={<PrivacySettingsPage />} />
      <Route path="/login" element={<LegacyLoginRedirect />} />
      <Route path="/feedback" element={<FeedbackPage />} />
      <Route path="/admin/*" element={<AdminRoutes />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
    {background && <FeedbackPage />}
    </MobileInstallProvider>
  )
}
