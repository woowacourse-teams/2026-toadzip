vi.mock('./admin/operations/api', () => ({
  loadComplexOverview: vi.fn(async () => ({ total: 0, recruiting: 0, missingCoordinates: 0, reviewRequired: 0, agencies: { LH: 0, SH: 0, GH: 0 }, asOf: '2026-11-06T00:00:00Z' })),
  loadAnnouncementOverview: vi.fn(async () => ({ statuses: {}, attention: 0, reasons: {}, asOf: '2026-11-06T00:00:00Z' })),
  loadUserOverview: vi.fn(async () => ({ total: 0, newUsers: 0, previousNewUsers: 0, trend: [], asOf: '2026-11-06T00:00:00Z' })),
  loadCollectionOverview: vi.fn(async () => ({ sources: [], asOf: '2026-11-06T00:00:00Z' })),
  loadTasksOverview: vi.fn(async () => ({ stages: {}, queue: { waiting: 0, running: 0, oldestWaitSeconds: null, recentFailed: 0 },
    unresolvedFailures: 0, asOf: '2026-11-06T00:00:00Z' })),
  loadPdfReviews: vi.fn(async () => ({ items: [], nextBefore: null })),
  loadPdfAutoExtraction: vi.fn(async () => ({ enabled: false, updatedBy: null, updatedAt: null, publicationMode: 'OFF',
    notExtracted: 0, inFlight: 0, maxInFlight: 2, asOf: '2026-11-06T00:00:00Z' })),
}))
vi.mock('./admin/notifications/deliveryApi', async original => ({ ...await original<typeof import('./admin/notifications/deliveryApi')>(), listDeliveries: vi.fn(async () => ({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })) }))
vi.mock('./admin/feedback/api', async original => ({ ...await original<typeof import('./admin/feedback/api')>(), listFeedback: vi.fn(async () => ({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })) }))
vi.mock('./admin/ingest/failureReviewApi', async original => ({ ...await original<typeof import('./admin/ingest/failureReviewApi')>(), getFailureReviews: vi.fn(async () => ({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })) }))
vi.mock('./admin/notifications/api', async original => ({
  ...await original<typeof import('./admin/notifications/api')>(),
  loadNotificationSubscriptions: vi.fn(async () => ({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0,
    summary: { activeMembers: 0, activeSubscriptions: 0, asOf: '2026-11-06T00:00:00Z' } })),
  loadNotificationTrend: vi.fn(async () => ({ days: [], collectionStartedAt: null, asOf: '2026-11-06T00:00:00Z' })),
}))
vi.mock('./admin/management/api', async importOriginal => ({
  ...(await importOriginal<typeof import('./admin/management/api')>()),
  getManagementPage: vi.fn(async () => ({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })),
}))
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import type { DataPipelineType } from './admin/ingest/api.ts'

vi.mock('./admin/ingest/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./admin/ingest/api')>()),
  getDataPipelineStatus: vi.fn((type: DataPipelineType) => Promise.resolve({
    executionId: null,
    type,
    status: 'IDLE',
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount: type === 'ANNOUNCEMENT_COLLECTION' ? 4 : 2,
    completedSteps: [],
    skippedSteps: [],
    partiallyFailedSteps: [],
    failure: null,
  })),
  startDataPipeline: vi.fn(),
  getPipelineHistory: vi.fn(async () => []),
}))

beforeEach(() => {
  localStorage.clear()
  vi.stubEnv('VITE_NAVER_MAPS_CLIENT_ID', '')
})

afterEach(() => {
  localStorage.clear()
  vi.unstubAllEnvs()
})

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('지도 설정이 없어도 환영 모달 없이 공개 탐색을 시작한다', () => {
    render(
      <MemoryRouter initialEntries={['/explore']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('banner', { name: '서비스 헤더' })).not.toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible()
    const homeLink = screen.getByRole('link', { name: '공공주택 복덕방 홈' })
    expect(homeLink).toBeVisible()
    expect(homeLink.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(homeLink.querySelector('img')).toHaveAttribute('src', '/logo-bok-search.svg')
    expect(
      screen.getByRole('searchbox', { name: '지역, 지하철역, 단지, 공고 검색' }),
    ).toBeVisible()
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent(
      '지도 설정이 준비되지 않았어요.',
    )
  })

  it('검색창과 공고 메뉴를 두고 소개 제목과 기본 입력 안내는 표시하지 않는다', () => {
    render(
      <MemoryRouter initialEntries={['/explore']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('searchbox', { name: '지역, 지하철역, 단지, 공고 검색' }))
      .toHaveAttribute('placeholder', '지역, 지하철역, 단지, 공고 검색')
    expect(screen.queryByRole('button', { name: '단지 목록' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '공고 목록' })).toBeVisible()
    expect(screen.queryByText('지도 기반 탐색')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '공공임대주택' }))
      .not.toBeInTheDocument()
    expect(screen.queryByText('두 글자 이상 입력해 주세요.')).not.toBeInTheDocument()
  })

  it('존재하지 않는 경로에서 안내 화면을 표시한다', () => {
    render(
      <MemoryRouter initialEntries={['/unknown']}>
        <App />
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('heading', { name: '페이지를 찾을 수 없어요.' }),
    ).toBeVisible()
    const brand = screen.getByRole('link', { name: '공공주택 복덕방 홈' })
    expect(brand.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(brand.querySelector('img'))
      .toHaveAttribute('src', '/logo-bok-search.svg')
    expect(screen.getByRole('link', { name: '지도로 돌아가기' })).toHaveAttribute(
      'href',
      '/explore',
    )
  })

  it('관리 화면에 운영 홈 로고를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin/complexes']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '단지 관리' })).toBeVisible()
    const adminBrand = screen.getByRole('link', { name: '관리자 운영 홈으로' })
    expect(adminBrand).toHaveAttribute('href', '/admin/operations')
    expect(adminBrand.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(adminBrand.querySelector('img'))
      .toHaveAttribute('src', '/logo-bok-search.svg')
  })

  it('폐기한 단지 누락 대조 주소는 단지 관리로 연결하고 전용 메뉴를 표시하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin/complex-coverage']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '단지 관리', level: 1 })).toBeVisible()
    expect(screen.getByRole('link', { name: '단지 관리' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('link', { name: '단지 누락 대조' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '공식 목록과 대조' })).not.toBeInTheDocument()
  })

  it('만료된 세션의 로그아웃 응답이 401이어도 공통 로그인 화면으로 이동한다', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' }))
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ message: '인증이 필요합니다.' }, 401))
      .mockResolvedValue(jsonResponse({}, 401))

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <App />
      </MemoryRouter>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeVisible()

  })

  it('미인증 관리자 접근은 원래 주소를 보존해 공통 소셜 로그인으로 이동한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: '인증이 필요합니다.' }, 401)))
    render(<MemoryRouter initialEntries={['/admin/announcements?page=2']}><App /><LocationProbe /></MemoryRouter>)

    expect(screen.queryByLabelText('비밀번호')).not.toBeInTheDocument()

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeVisible()
    expect(screen.getByRole('link', { name: /카카오톡으로 로그인/ })).toBeVisible()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?returnTo=%2Fadmin%2Fannouncements%3Fpage%3D2')
  })

  it('관리자가 아닌 소셜 계정은 로그인 화면으로 다시 가지 않고 권한 없음 안내를 본다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ code: 'ACCESS_DENIED', message: '관리자 권한이 필요합니다.' }, 403)))
    render(<MemoryRouter initialEntries={['/admin/operations']}><App /><LocationProbe /></MemoryRouter>)

    expect(await screen.findByText('관리자 권한이 없는 계정입니다.')).toBeVisible()
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/operations')
    expect(screen.getByRole('link', { name: '지도로 돌아가기' })).toHaveAttribute('href', '/explore')
  })

  it('관리자 메뉴를 이동하면 해당 업무만 표시하고 뒤로 돌아와도 실행 상태를 조회한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '운영 홈', level: 1 })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '신규 단지 등록' })).not.toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('navigation', { name: '관리자 메뉴' })).getByRole('link', { name: '단지 관리' }))
    fireEvent.click(screen.getByRole('link', { name: '단지 추가' }))
    expect(screen.getByRole('heading', { name: '단지 관리',level:1 })).toBeVisible()
    expect(screen.getByRole('region', { name: '단지 추가' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '공고 수집 실행' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '공고 관리' }))
    fireEvent.click(screen.getByRole('link', { name: '공고 추가' }))
    expect(screen.queryByRole('button', { name: 'JSON 가져오기' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '직접 입력' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'JSON 가져오기' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('수집·원천 도구', { selector: 'summary' }))
    fireEvent.click(screen.getByRole('link', { name: '주소 데이터' }))
    expect(screen.getByRole('heading', { name: '주소 데이터', level: 1 })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '신규 단지 등록' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '공고 수집' }))
    expect(screen.getByRole('heading', { name: '공고 수집', level: 1 })).toBeVisible()
  })

  it('기존 수집 v2 주소의 단지 탭을 통합 수집 화면으로 연결한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin/ingest-v2?domain=complex']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '단지 수집', level: 1 })).toBeVisible()
    expect(screen.queryByRole('tablist', { name: '수집 대상' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '단지 수집' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('link', { name: '공고 수집·검토' })).not.toBeInTheDocument()
  })

  it('공고 관리의 마이홈 가져오기는 입력 폼 없이 단건 등록 지원 종료만 안내한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/admin/announcements/new']}><App /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: '마이홈에서 가져오기' }))
    expect(screen.getByRole('note')).toHaveTextContent('마이홈 공고 단건 등록은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.')
    expect(screen.queryByLabelText('마이홈 공고 URL')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '등록 실행' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => String(url)).filter(url => url.includes('announcement-registration'))).toEqual([])
  })

  it('지원 종료한 LH 행 연결 주소는 화면을 열지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin/failures/lh-links/21391']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: /찾을 수 없/ })).toBeVisible()
  })

  it('공고 입력 URL로 직접 들어와도 지정한 방식만 연다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '직접 입력' })).toBeVisible()
    expect(screen.getByRole('link', { name: '공고 관리' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('heading', { name: '신규 단지 등록' })).not.toBeInTheDocument()
  })


})

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname + location.search}</output>
}
