vi.mock('./admin/management/api', async importOriginal => ({
  ...(await importOriginal<typeof import('./admin/management/api')>()),
  getManagementPage: vi.fn(async () => ({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })),
}))
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router'
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

  it('지도 설정이 없어도 첫 방문 안내를 표시하고 바로 지도 탐색을 시작한다', () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('dialog', { name: /살고 싶은 동네의/ })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '바로 지도 둘러보기' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('banner', { name: '서비스 헤더' })).not.toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible()
    const homeLink = screen.getByRole('link', { name: '공공주택 복덕방 홈' })
    expect(homeLink).toBeVisible()
    expect(homeLink.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(homeLink.querySelector('img')).toHaveAttribute('src', '/logo-bok-search.svg')
    expect(
      screen.getByRole('searchbox', { name: '지역, 단지, 공고 검색' }),
    ).toBeVisible()
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent(
      '지도 설정이 준비되지 않았습니다.',
    )
  })

  it('검색창과 공고 메뉴를 두고 소개 제목과 기본 입력 안내는 표시하지 않는다', () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('searchbox', { name: '지역, 단지, 공고 검색' }))
      .toHaveAttribute('placeholder', '지역, 단지, 공고 검색')
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
      screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다.' }),
    ).toBeVisible()
    const brand = screen.getByRole('link', { name: '공공주택 복덕방 홈' })
    expect(brand.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(brand.querySelector('img'))
      .toHaveAttribute('src', '/logo-bok-search.svg')
    expect(screen.getByRole('link', { name: '지도로 돌아가기' })).toHaveAttribute(
      'href',
      '/',
    )
  })

  it('관리자 로그인과 관리 화면에도 같은 로고를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse({ message: '인증이 필요합니다.' }, 401))
      .mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    const { unmount } = render(<MemoryRouter initialEntries={['/admin/login']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeVisible()
    const adminLoginBrand = screen.getByRole('link', { name: '공공주택 복덕방 홈' })
    expect(adminLoginBrand.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(adminLoginBrand.querySelector('img'))
      .toHaveAttribute('src', '/logo-bok-search.svg')

    unmount()
    render(<MemoryRouter initialEntries={['/admin/complexes']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '단지 관리' })).toBeVisible()
    const adminBrand = screen.getByRole('link', { name: '공공주택 복덕방 홈' })
    expect(adminBrand.querySelector('.brand-name')).not.toBeInTheDocument()
    expect(adminBrand.querySelector('img'))
      .toHaveAttribute('src', '/logo-bok-search.svg')
  })

  it('만료된 세션의 로그아웃 응답이 401이어도 로그인 상태를 지우고 다시 로그인한다', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' }))
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ message: '인증이 필요합니다.' }, 401))
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' }))

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <App />
      </MemoryRouter>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    const loginIdentifierInput = await screen.findByLabelText('로그인 식별자')
    fireEvent.change(loginIdentifierInput, { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '단지 관리', level: 1 })).toBeVisible()
  })

  it('관리자 메뉴를 이동하면 해당 업무만 표시하고 뒤로 돌아와도 실행 상태를 조회한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '단지 관리', level: 1 })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '단지 등록' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '단지 관리' }))
    fireEvent.click(screen.getByRole('link', { name: '단지 추가' }))
    expect(screen.getByRole('heading', { name: '단지 관리',level:1 })).toBeVisible()
    expect(screen.getByRole('region', { name: '단지 추가' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '공고 수집 실행' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '공고 관리' }))
    fireEvent.click(screen.getByRole('link', { name: '공고 추가' }))
    expect(screen.getByRole('heading', { name: 'JSON 가져오기' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '직접 입력' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
    expect(screen.getByRole('heading', { name: '직접 입력' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'JSON 가져오기' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '주소 데이터' }))
    expect(screen.getByRole('heading', { name: '주소 데이터', level: 1 })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '단지 등록' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '수집·정제' }))
    expect(screen.getByRole('heading', { name: '수집·정제', level: 1 })).toBeVisible()
  })

  it('공고 입력 URL로 직접 들어와도 지정한 방식만 연다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' })))
    render(<MemoryRouter initialEntries={['/admin/announcements/new?mode=direct']}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '직접 입력' })).toBeVisible()
    expect(screen.getByRole('link', { name: '공고 관리' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('heading', { name: '단지 등록' })).not.toBeInTheDocument()
  })

  it.each([false, true])('인증 복원 대기 중 로그인하면 관리자 화면을 연다 (실패 후 재시도: %s)', async retry => {
    const restoring = deferredResponse()
    const fetchMock = vi.fn().mockReturnValueOnce(restoring.promise)
    if (retry) {
      fetchMock.mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
        .mockResolvedValueOnce(jsonResponse({ message: '로그인 실패' }, 401))
    }
    fetchMock.mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'current-admin', role: 'ADMIN' }))
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/admin/login']}><App /></MemoryRouter>)
    fireEvent.change(await screen.findByLabelText('로그인 식별자'), { target: { value: 'current-admin' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    if (retry) {
      expect(await screen.findByText('로그인 실패')).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    }
    expect(await screen.findByRole('heading', { name: '단지 관리', level: 1 })).toBeVisible()
    await act(async () => restoring.resolve(jsonResponse({ loginIdentifier: 'stale-admin', role: 'ADMIN' })))
    expect(screen.getByText('current-admin')).toBeVisible()
    expect(screen.queryByText('stale-admin')).not.toBeInTheDocument()
  })

  it('StrictMode의 오래된 인증 상태 응답이 로그인 후 세션을 덮어쓰지 않는다', async () => {
    const firstSessionResponse = deferredResponse()
    const latestSessionResponse = deferredResponse()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock
      .mockReturnValueOnce(firstSessionResponse.promise)
      .mockReturnValueOnce(latestSessionResponse.promise)
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'current-admin', role: 'ADMIN' }))

    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/admin/login']}>
          <App />
        </MemoryRouter>
      </StrictMode>,
    )

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    await act(async () => {
      latestSessionResponse.resolve(jsonResponse({ message: '인증이 필요합니다.' }, 401))
    })

    fireEvent.change(await screen.findByLabelText('로그인 식별자'), {
      target: { value: 'current-admin' },
    })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('current-admin')).toBeVisible()

    await act(async () => {
      firstSessionResponse.resolve(jsonResponse({ loginIdentifier: 'stale-admin', role: 'ADMIN' }))
    })

    expect(screen.getByText('current-admin')).toBeVisible()
    expect(screen.queryByText('stale-admin')).not.toBeInTheDocument()
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

function deferredResponse(): {
  promise: Promise<Response>
  resolve: (response: Response) => void
} {
  let resolveResponse: (response: Response) => void
  const promise = new Promise<Response>((resolve) => {
    resolveResponse = resolve
  })
  return { promise, resolve: resolveResponse! }
}
