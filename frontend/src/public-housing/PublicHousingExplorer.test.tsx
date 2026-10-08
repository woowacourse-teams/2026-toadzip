import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { useRef, useState } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { trackEvent } from '../analytics/googleAnalytics.ts'
import type { RegionBoundary } from './regions/regionBoundary.ts'
import type { RegionBoundaryRepository } from './regions/regionBoundaryRepository.ts'
import type { NaverMapProps } from '../maps/naver/NaverMap.tsx'
import type { HousingMapRepository } from './api/housingMapRepository.ts'
import {
  PublicHousingHttpError,
  type PublicHousingRepository,
} from './api/publicHousingRepository.ts'
import type { PublicHousingRegionRepository } from './api/publicHousingRegionRepository.ts'
import type {
  AnnouncementDetail,
  AnnouncementListItem,
  AnnouncementPage,
  ComplexDetail,
  ComplexListItem,
  ComplexPage,
  MapBounds,
  MapComplex,
  RawAnnouncementListItem,
  RawAnnouncementDetail,
  RawAnnouncementPage,
  RawComplexDetail,
  RawComplexListItem,
  RawComplexPage,
  RawMapComplex,
} from './model/publicHousing.ts'
import type {
  HousingMapAggregateResult,
  HousingMapIndividualResult,
} from './model/housingMap.ts'
import { PublicHousingExplorer } from './PublicHousingExplorer.tsx'
import type {
  IntegratedSearchRepository,
  IntegratedSearchResponse,
  SearchResultItem,
} from './search/integratedSearchRepository.ts'

vi.mock('../maps/naver/NaverMap.tsx', () => ({
  default: FakeNaverMap,
}))

vi.mock('../analytics/googleAnalytics.ts', () => ({
  setAnalyticsPageActive: vi.fn(),
  trackEvent: vi.fn(() => true),
}))

const { boundaryMetadata, defaultBoundaryRepository } = vi.hoisted(() => ({
  boundaryMetadata: [
    { regionCode: '41110', name: '경기도 수원시', bounds: { southWestLat: 37.2, southWestLng: 126.9, northEastLat: 37.4, northEastLng: 127.1 }, path: '/test/41110.geojson' },
    { regionCode: '41111', name: '경기도 수원시 장안구', bounds: { southWestLat: 37.3, southWestLng: 126.95, northEastLat: 37.4, northEastLng: 127.05 }, path: '/test/41111.geojson' },
    { regionCode: '47940', name: '경상북도 울릉군', bounds: { southWestLat: 37.2, southWestLng: 130.7, northEastLat: 37.6, northEastLng: 132 }, representativePoint: { latitude: 37.50275955, longitude: 130.86686789 }, path: '/test/47940.geojson' },
  ],
  defaultBoundaryRepository: { find: async (regionCode: string) => ({ regionCode, version: 'test', polygons: [] }) },
}))
vi.mock('./regions/regionBoundaryCatalog.ts', () => ({
  findRegionBoundaryMetadata: (code: string) => boundaryMetadata.find((entry) => entry.regionCode === code) ?? null,
  findRegionBoundaryName: (code: string) => boundaryMetadata.find((entry) => entry.regionCode === code)?.name ?? null,
  regionBoundaryRepository: defaultBoundaryRepository,
}))

const INITIAL_BOUNDS: MapBounds = {
  southWestLat: 37.5,
  southWestLng: 126.9,
  northEastLat: 37.62,
  northEastLng: 127.1,
}

const NEXT_BOUNDS: MapBounds = {
  southWestLat: 37.4,
  southWestLng: 126.8,
  northEastLat: 37.55,
  northEastLng: 127,
}

const JITTERED_INITIAL_BOUNDS: MapBounds = {
  southWestLat: 37.500001,
  southWestLng: 126.900001,
  northEastLat: 37.620001,
  northEastLng: 127.100001,
}

const KOREA_TEST_BOUNDS: MapBounds = {
  southWestLat: 33,
  southWestLng: 124,
  northEastLat: 39,
  northEastLng: 132,
}

const INITIAL_CENTER = {
  latitude: 37.56,
  longitude: 127,
}

const NEXT_CENTER = {
  latitude: 37.475,
  longitude: 126.9,
}

const PRECISION_CENTER = {
  latitude: 37.5666103,
  longitude: 126.9783882,
}

const SEOUL_CITY_HALL_CENTER = {
  latitude: 37.5666103,
  longitude: 126.9783882,
}

const TEST_REGIONS = [
  {
    regionCode: '11',
    provinceName: '서울특별시',
    districtName: null,
    displayName: '서울특별시 전체',
  },
  {
    regionCode: '11140',
    provinceName: '서울특별시',
    districtName: '중구',
    displayName: '서울특별시 중구',
  },
  {
    regionCode: '41',
    provinceName: '경기도',
    districtName: null,
    displayName: '경기도 전체',
  },
  {
    regionCode: '41130',
    provinceName: '경기도',
    districtName: '성남시',
    displayName: '경기도 성남시',
  },
  {
    regionCode: '41135',
    provinceName: '경기도',
    districtName: '성남시 분당구',
    displayName: '경기도 성남시 분당구',
  },
] as const

beforeEach(() => {
  localStorage.clear()
  vi.mocked(trackEvent).mockClear()
  localStorage.setItem('toadzip:welcome-completed', JSON.stringify({ expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000 }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  localStorage.clear()
})

describe('PublicHousingExplorer', () => {
  it('지역 검색 없이 최근 본 메뉴를 열어 빈 기록을 안내하고 다시 누르면 접는다', () => {
    const repository = createRepository()
    renderExplorer(repository)
    const navigation = within(screen.getByRole('navigation', { name: '주요 메뉴' }))
    const toggle = navigation.getByRole('button', { name: '최근 본 단지' })
    fireEvent.click(toggle)
    expect(screen.getByRole('region', { name: '최근 본 단지' })).toHaveTextContent('최근 본 단지가 없어요.')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
    fireEvent.click(toggle)
    expect(screen.queryByRole('region', { name: '최근 본 단지' })).not.toBeInTheDocument()
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('최근 본 목록은 지역에 제한되지 않고 최대 20개를 표시하며 메뉴 전환 뒤에도 스크롤을 유지한다', async () => {
    localStorage.setItem('toadzip.recent-complexes.v1', JSON.stringify(Array.from({ length: 21 }, (_, index) => ({
      complexId: String(index + 1), name: `최근 단지 ${index + 1}`, address: '부산광역시',
    }))))
    const repository = createRepository()
    renderSearchedExplorer(repository)
    const toggle = screen.getByRole('button', { name: '최근 본 단지' })
    fireEvent.click(toggle)
    const recent = screen.getByRole('region', { name: '최근 본 단지' })
    expect(within(recent).getAllByRole('listitem')).toHaveLength(20)
    expect(within(recent).queryByText('최근 본 기록')).not.toBeInTheDocument()
    expect(within(recent).queryByLabelText('최근 본 단지 20곳')).not.toBeInTheDocument()
    expect(within(recent).getByRole('button', { name: /^최근 단지 20/ })).toBeVisible()
    expect(within(recent).queryByRole('button', { name: /^최근 단지 21/ })).not.toBeInTheDocument()
    const scroll = recent.querySelector('.housing-results__scroll') as HTMLElement
    scroll.scrollTop = 800
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(scroll.scrollTop).toBe(800)
    expect(screen.getByRole('region', { name: '최근 본 단지' })).toBe(recent)
    await waitFor(() => expect(repository.findComplexPage).toHaveBeenCalledOnce())
  })

  it('공고 메뉴를 다시 누르면 상세를 유지하며 목록을 접고 다시 열 때 재조회하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?complexId=17')
    const detail = await screen.findByRole('region', { name: '서울가람 행복주택 단지 상세 내용' })
    const toggle = screen.getByRole('button', { name: '공고 목록' })
    fireEvent.click(toggle)
    const list = screen.getByRole('region', { name: '공고 목록' })
    const card = await within(list).findByRole('article')
    const scroll = card.closest('.housing-results__scroll') as HTMLElement
    scroll.scrollTop = 160
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: '공고 목록' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '서울가람 행복주택 단지 상세 내용' })).toBe(detail)
    expect(screen.getByTestId('location-search')).toHaveTextContent('complexId=17')
    fireEvent.click(toggle)
    expect(screen.getByRole('region', { name: '공고 목록' })).toBe(list)
    expect(scroll.scrollTop).toBe(160)
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(repository.findComplexDetail).toHaveBeenCalledOnce()
  })

  it('지역 검색 전에는 좌측 단지 메뉴와 단지 목록을 제공하지 않는다', () => {
    const repository = createRepository()
    renderExplorer(repository)
    const navigation = within(screen.getByRole('navigation', { name: '주요 메뉴' }))
    expect(navigation.queryByText('단지')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '단지 목록' })).not.toBeInTheDocument()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(screen.getByRole('searchbox')).toBeVisible()
  })

  it('목록을 접고 다시 열면 검색 지역과 페이지·스크롤을 유지하고 지도 이동은 목록을 다시 조회하지 않는다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository, '/?boundaryRegionCode=41111&complexRentalTypes=HAPPY_HOUSING')
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    const scroll = card.closest('.housing-results__scroll') as HTMLElement
    scroll.scrollTop = 160
    const query = screen.getByTestId('location-search').textContent
    fireEvent.click(screen.getByRole('button', { name: '목록 접기' }))
    expect(screen.queryByRole('region', { name: '단지 목록' })).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).toHaveTextContent(query!)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /단지 목록 보기$/ }))
    expect(screen.getByRole('article', { name: '서울가람 행복주택' })).toBe(card)
    expect(scroll.scrollTop).toBe(160)
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '목록 접기' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('첫 방문 예시 판교는 기존 지역 경계를 해제하고 판교역 주변으로 이동한다', () => {
    localStorage.clear()
    renderExplorer(createRepository(), '/?boundaryRegionCode=41111')
    fireEvent.click(screen.getByRole('button', { name: '살고 싶은 지역 검색하기' }))
    fireEvent.click(screen.getByRole('button', { name: /성남 판교/ }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).not.toHaveTextContent('boundaryRegionCode')
    expect(screen.getByText('카메라 37.39473,127.11119')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14')
  })

  it.each([
    ['complexId=17', '단지 목록', '서울가람 행복주택 단지 상세 내용'],
    ['complexId=17', '공고 목록', '서울가람 행복주택 단지 상세 내용'],
    ['announcementId=201', '단지 목록', '성남 청년 행복주택 입주자 모집 공고 상세 내용'],
    ['announcementId=201', '공고 목록', '성남 청년 행복주택 입주자 모집 공고 상세 내용'],
    ['complexId=17', '최근 본 단지', '서울가람 행복주택 단지 상세 내용'],
    ['announcementId=201', '최근 본 단지', '성남 청년 행복주택 입주자 모집 공고 상세 내용'],
  ])('열린 상세(%s)를 유지한 채 %s을 열고 접는다', async (detailQuery, tab, detailName) => {
    const repository = createRepository()
    renderSearchedExplorer(repository, `/?${detailQuery}`, searchRepository([], []))
    const detail = await screen.findByRole('region', { name: detailName })
    detail.scrollTop = 160
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    const triggerName = tab === '단지 목록' ? /단지 목록 보기$/ : tab
    const trigger = screen.getByRole('button', { name: triggerName })
    trigger.focus()
    fireEvent.click(trigger)
    expect(screen.getByRole('button', { name: triggerName })).toHaveFocus()
    expect(screen.getByRole('region', { name: detailName })).toBe(detail)
    expect(detail.scrollTop).toBe(160)
    expect(screen.getByRole('region', { name: tab })).toBeVisible()
    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.getByTestId('location-search')).toHaveTextContent(detailQuery)

    fireEvent.click(screen.getByRole('button', { name: '목록 접기' }))
    expect(screen.queryByRole('region', { name: tab })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: detailName })).toBe(detail)
    expect(detail.scrollTop).toBe(160)
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest!)
    expect(repository.findMap).toHaveBeenCalledOnce()
    expect(detailQuery.startsWith('complexId') ? repository.findComplexDetail : repository.findAnnouncementDetail)
      .toHaveBeenCalledOnce()
  })

  it('지역 목록에서 단지를 선택해도 목록을 유지하고 상세를 함께 표시한다', async () => {
    renderSearchedExplorer(createRepository())
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(within(card).getByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    expect(await screen.findByRole('region', { name: '서울가람 행복주택 단지 상세 내용' })).toBeVisible()
    expect(screen.getByRole('region', { name: '단지 목록' })).toBeVisible()
    expect(screen.getByRole('article', { name: '서울가람 행복주택' })).toBe(card)
  })

  it('모바일 목록은 하단에서 세 단계로 조절하며 접혀도 펼치기 손잡이를 유지한다', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(max-width: 767px)' || query === '(max-width: 1023px)' }))
    try {
      renderSearchedExplorer(createRepository())
      await screen.findByRole('article', { name: '서울가람 행복주택' })
      const handle = screen.getByRole('slider', { name: '목록 높이' })
      expect(handle).toHaveAttribute('aria-valuetext', '절반 펼침')
      fireEvent.keyDown(handle, { key: 'ArrowUp' })
      expect(handle).toHaveAttribute('aria-valuetext', '크게 펼침')
      fireEvent.keyDown(handle, { key: 'Home' })
      expect(handle).toHaveAttribute('aria-valuetext', '접힘')
      expect(screen.queryByRole('region', { name: '단지 목록' })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '목록 펼치기' }))
      expect(screen.getByRole('region', { name: '단지 목록' })).toBeVisible()
      expect(handle).toHaveAttribute('aria-valuetext', '절반 펼침')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('모바일 상세는 독립 모달이고 닫으면 이전 하단 목록 높이와 스크롤을 복원한다', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(max-width: 767px)' || query === '(max-width: 1023px)' }))
    try {
      renderSearchedExplorer(createRepository())
      const opener = await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' })
      const handle = screen.getByRole('slider', { name: '목록 높이' })
      fireEvent.keyDown(handle, { key: 'End' })
      const scroll = opener.closest('.housing-results__scroll') as HTMLElement
      scroll.scrollTop = 230
      opener.focus()
      fireEvent.click(opener)
      await screen.findByRole('region', { name: '서울가람 행복주택 단지 상세 내용' })
      const modal = screen.getByRole('dialog', { name: '단지 상세' })
      expect(modal).toBeInstanceOf(HTMLDialogElement)
      expect(modal).toHaveAttribute('aria-modal', 'true')
      expect(modal).toHaveAttribute('open')
      fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
      expect(screen.getByRole('slider', { name: '목록 높이' })).toHaveAttribute('aria-valuetext', '크게 펼침')
      expect(scroll.scrollTop).toBe(230)
      await waitFor(() => expect(opener).toHaveFocus())
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it.each(['목록 선택', '상세 직접 접근'])('모바일 %s에서는 목록을 접어 상세를 먼저 보여준다', async (entry) => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(max-width: 767px)' || query === '(max-width: 1023px)' }))
    try {
      renderSearchedExplorer(createRepository(), entry === '상세 직접 접근' ? '/?complexId=17' : '/')
      if (entry === '목록 선택') {
        const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
        fireEvent.click(within(card).getByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
      }
      const detail = await screen.findByRole('region', { name: '서울가람 행복주택 단지 상세 내용' })
      expect(screen.queryByRole('region', { name: '단지 목록' })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /단지 목록 보기$/ }))
      expect(screen.getByRole('region', { name: '단지 목록' })).toBeVisible()
      expect(screen.getByRole('region', { name: '서울가람 행복주택 단지 상세 내용' })).toBe(detail)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it.each([
    ['단지 목록', '서울가람 행복주택 단지 상세 보기', '단지 상세 닫기', '닫기'],
    ['공고 목록', '성남 청년 행복주택 입주자 모집 공고 상세 보기', '공고 상세 닫기', 'Escape'],
  ])('모바일 %s에서 연 상세를 닫으면 목록과 카드 포커스를 복원한다', async (tab, action, close, method) => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(max-width: 767px)' || query === '(max-width: 1023px)' }))
    try {
      renderSearchedExplorer(createRepository())
      fireEvent.click(screen.getByRole('button', { name: tab === '단지 목록' ? /단지 목록 보기$/ : '공고 목록' }))
      const opener = await within(screen.getByRole('region', { name: tab })).findByRole('button', { name: action })
      opener.focus()
      fireEvent.click(opener)
      await screen.findByRole('region', { name: /상세 내용$/ })
      const closeButton = screen.getByRole('button', { name: close })
      if (method === 'Escape') fireEvent.keyDown(closeButton, { key: 'Escape' })
      else fireEvent.click(closeButton)
      expect(screen.getByRole('region', { name: tab })).toBeVisible()
      await waitFor(() => expect(opener).toHaveFocus())
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('목록을 접은 상태에서 상세를 닫으면 숨긴 카드 대신 검색 지역명으로 포커스를 돌린다', async () => {
    renderSearchedExplorer(createRepository())
    const opener = await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' })
    opener.focus()
    fireEvent.click(opener)
    await screen.findByRole('region', { name: /상세 내용$/ })
    const close = screen.getByRole('button', { name: '단지 상세 닫기' })
    fireEvent.click(screen.getByRole('button', { name: '목록 접기' }))
    close.focus()
    fireEvent.click(close)
    expect(screen.queryByRole('button', { name: '단지 상세 닫기' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /단지 목록 보기$/ })).toHaveFocus())
  })

  it('좌측 주요 메뉴에는 홈과 단지 없이 공고와 최근 본 단지를 제공한다', () => {
    renderExplorer(createRepository())
    const navigation = within(screen.getByRole('navigation', { name: '주요 메뉴' }))
    expect(navigation.queryByRole('link', { name: '홈' })).not.toBeInTheDocument()
    expect(navigation.queryByRole('tablist')).not.toBeInTheDocument()
    expect(navigation.getAllByRole('button')).toHaveLength(2)
    expect(navigation.getByRole('button', { name: '최근 본 단지' })).toHaveAttribute('aria-expanded', 'false')
    expect(navigation.getByRole('button', { name: '공고 목록' })).toHaveAttribute('aria-expanded', 'false')
  })

  it.each(['complexId=17', 'announcementId=201', 'complexId=17&boundaryRegionCode=41110'])(
    '상세에서 지역을 선택하면 상세를 닫고 지역 목록을 연다: %s', async (detailQuery) => {
      const region = searchItem('REGION', '41110', '경기도 수원시', 37.3, 127)
      renderExplorer(createRepository(), `/?${detailQuery}`, searchRepository([], [region]))
      await screen.findByRole('button', { name: /상세 닫기/ })
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
      fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))
      expectCurrentSearch({ boundaryRegionCode: '41110' })
      expect(screen.queryByRole('button', { name: /상세 닫기/ })).not.toBeInTheDocument()
      expect(await screen.findByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
      expect(screen.getByTestId('location-state')).not.toHaveTextContent('toadzipDetail')
    },
  )

  it('지역 검색 전에는 지도만 조회하고 단지 목록을 제공하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(screen.queryByRole('complementary', { name: '공공임대주택 검색 결과' })).not.toBeInTheDocument()
  })

  it('검색 지역 목록은 지도 이동과 별도로 조회하고 다른 지역 필터를 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?boundaryRegionCode=41110&complexRegionCode=11')
    await waitFor(() => expect(repository.findComplexPage).toHaveBeenCalledWith(
      null, null, 20, expect.any(AbortSignal), { regionCode: '41110' },
    ))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    expect(repository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({
      filters: { regionCode: '11' },
    }), expect.any(AbortSignal))
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(screen.getByText(/내 단지만 표시합니다/)).toBeVisible()
  })

  it('첫 방문 안내의 지역 선택이 기존 경계 이동과 URL에 연결된다', async () => {
    localStorage.clear()
    const region = searchItem('REGION', '41111', '경기도 수원시 장안구', null, null)
    renderExplorer(createRepository(), '/?complexRegionCode=11', searchRepository([], [region]))
    fireEvent.click(screen.getByRole('button', { name: '살고 싶은 지역 검색하기' }))
    fireEvent.change(screen.getByRole('searchbox', { name: '살고 싶은 지역' }), { target: { value: '장안' } })
    fireEvent.click(await screen.findByRole('button', { name: /경기도 수원시 장안구/ }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expectCurrentSearch({ complexRegionCode: '11', boundaryRegionCode: '41111' })
    expect(screen.getByText(`카메라 ${(37.3 + 37.4) / 2},127`)).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
  })

  it('저배율 영역을 v2로 조회하고 0곳 지역 마커를 표시한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(aggregateMapResult())
    renderExplorer(repository, '/', undefined, undefined, mapRepository)

    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))

    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledWith({
      bounds: KOREA_TEST_BOUNDS,
      zoom: 7,
    }, expect.any(AbortSignal)))
    expect(screen.getByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })).toBeVisible()
    expect(screen.queryByRole('complementary', { name: '공공임대주택 검색 결과' })).not.toBeInTheDocument()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
  })

  it('집계 단계의 빈 응답은 선택할 지역 마커가 없다고 구분해 표시한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository({
      ...aggregateMapResult(),
      nodes: [],
    })
    renderExplorer(repository, '/', undefined, undefined, mapRepository)

    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))

    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledOnce())
    expect(screen.queryByRole('button', { name: /지역 마커 선택/ })).not.toBeInTheDocument()
    expect(screen.queryByText(
      '지역 마커를 선택해 지도를 확대해 주세요.',
    )).not.toBeInTheDocument()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
  })

  it('지역 마커를 선택하면 대표 좌표와 다음 확대 수준으로 이동한 뒤 조회한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(aggregateMapResult())
    renderExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const marker = await screen.findByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })

    fireEvent.click(marker)

    expect(screen.getByText('카메라 37.5665,126.978')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('7')
    expect(mapRepository.findMap).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    expect(mapRepository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({ zoom: 7 }),
      expect.any(AbortSignal),
    )
  })

  it('지역 마커 이동 중 바꾼 필터는 최종 idle 영역에만 요청한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(aggregateMapResult())
    renderExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const marker = await screen.findByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })

    fireEvent.click(marker)
    applyProvinceFilter('41')

    expect(mapRepository.findMap).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    expect(mapRepository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({
        filters: { regionCode: '41' },
        zoom: 7,
      }),
      expect.any(AbortSignal),
    )
  })

  it.each(['/', '/?boundaryRegionCode=41111', '/?boundaryRegionCode=41110'])('집계 표식은 %s에서 선택한 지역 경계와 개별 단지 확대 수준으로 한 번 이동한다', async (entry) => {
    const result: HousingMapAggregateResult = {
      ...aggregateMapResult(),
      resolvedStage: 3,
      nodes: [{ ...aggregateMapResult().nodes[0]!, groupKey: 'BASIC_REGION:41110',
        groupLabel: '수원시', latitude: 37.28, longitude: 127.01, nextStage: 4, expansionZoom: 12.6 }],
    }
    renderExplorer(createRepository(), entry, undefined, undefined,
      createMapRepository(result))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    const request = Number(screen.getByTestId('map-camera-request').textContent)
    fireEvent.click(await screen.findByRole('button', { name: '수원시 0곳 지역 마커 선택' }))

    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expectCurrentSearch({ boundaryRegionCode: '41110' })
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
    expect(screen.getByText('카메라 37.28,127.01')).toBeVisible()
    expect(Number(screen.getByTestId('map-camera-request').textContent)).toBe(request + 1)
  })

  it('지역 검색은 지역 면적과 관계없이 대표좌표에서 개별 마커가 보이는 배율로 이동한다', async () => {
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.275, 127.016)
    renderExplorer(createRepository(), '/', searchRepository([], [region]))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))
    expect(screen.getByText('카메라 37.275,127.016')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
  })

  it('도서 지역 직접 진입은 바다의 경계 중심 대신 저장된 대표좌표로 이동한다', async () => {
    renderExplorer(createRepository(), '/?boundaryRegionCode=47940')
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('47940'))
    expect(screen.getByText('카메라 37.50275955,130.86686789')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
  })

  it.each(['41110', '41111'])('면적이 다른 지역 %s도 개별 단지 전환 직후 배율을 유지한다', async (regionCode) => {
    renderExplorer(createRepository(), `/?boundaryRegionCode=${regionCode}`)
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent(regionCode))
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
  })

  it('지역 클러스터가 보이는 목록에서 단지를 선택하면 개별 마커가 보일 만큼 확대한다', async () => {
    const mapRepository = createMapRepository(aggregateMapResult())
    mapRepository.findMap.mockResolvedValueOnce(aggregateMapResult()).mockResolvedValue(individualMapResult())
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))
    await screen.findByRole('button', { name: '서울 0곳 지역 마커 선택' })
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.7')
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    expect(await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })).toHaveAttribute('data-selected', 'true')
  })

  it('검색 지역 밖의 집계 표식은 해당 지역의 대표 좌표로 이동한다', async () => {
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110', undefined, undefined,
      createMapRepository(aggregateMapResult()))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    fireEvent.click(await screen.findByRole('button', { name: '서울 0곳 지역 마커 선택' }))

    expect(screen.getByText('카메라 37.5665,126.978')).toBeVisible()
    expect(screen.getByTestId('map-camera-max-zoom')).toBeEmptyDOMElement()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('7')
  })

  it('지역 이동 중에는 기존 마커를 유지하고 다음 응답을 받은 뒤 교체한다', async () => {
    const repository = createRepository()
    const nextMap = createDeferred<HousingMapIndividualResult>()
    const mapRepository = createMapRepository(aggregateMapResult())
    mapRepository.findMap
      .mockResolvedValueOnce(aggregateMapResult())
      .mockReturnValueOnce(nextMap.promise)
    renderExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const aggregateMarker = await screen.findByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })

    fireEvent.click(aggregateMarker)
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))

    expect(screen.getByRole('region', { name: '공공임대주택 지도' }))
      .toHaveAttribute('data-transitioning', 'true')
    expect(aggregateMarker).toBeVisible()
    await act(async () => nextMap.resolve(individualMapResult()))
    expect(await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
    expect(screen.queryByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('region', {
      name: '공공임대주택 지도',
    })).toHaveAttribute('data-transitioning', 'false'))
  })

  it('지역 이동 중 다시 조작하면 진행 중 조회를 취소하고 다음 idle만 반영한다', async () => {
    const repository = createRepository()
    const interruptedMap = createDeferred<HousingMapIndividualResult>()
    const finalMap = createDeferred<HousingMapIndividualResult>()
    const mapRepository = createMapRepository(aggregateMapResult())
    mapRepository.findMap
      .mockResolvedValueOnce(aggregateMapResult())
      .mockReturnValueOnce(interruptedMap.promise)
      .mockReturnValueOnce(finalMap.promise)
    renderExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const aggregateMarker = await screen.findByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })

    fireEvent.click(aggregateMarker)
    fireEvent.click(screen.getByRole('button', { name: '지도 전환 중단' }))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    const interruptedSignal = mapRepository.findMap.mock.calls[1]?.[1]
    fireEvent.click(screen.getByRole('button', { name: '지도 전환 중단' }))

    expect(interruptedSignal?.aborted).toBe(true)
    await act(async () => interruptedMap.resolve(individualMapResult()))
    expect(screen.getByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(3))
    await act(async () => finalMap.resolve(individualMapResult()))

    expect(await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
  })

  it('지도 밖 검색 지역의 목록도 조회하고 지도에는 현재 영역 단지를 표시한다', async () => {
    const repository = createRepository()
    repository.findComplexPage.mockResolvedValue(complexPageFor(18, '검색 지역 단지'))
    renderExplorer(repository, '/?boundaryRegionCode=41110&complexRentalTypes=NATIONAL_RENTAL')
    expect(await screen.findByRole('article', { name: '검색 지역 단지' })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenCalledExactlyOnceWith(
      null, null, 20, expect.any(AbortSignal), { regionCode: '41110', rentalTypes: ['NATIONAL_RENTAL'] },
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    expect(repository.findMap).toHaveBeenLastCalledWith(
      { bounds: INITIAL_BOUNDS, zoom: 14, filters: { rentalTypes: ['NATIONAL_RENTAL'] } }, expect.any(AbortSignal),
    )
    expect(screen.queryByRole('article', { name: '서울가람 행복주택' })).not.toBeInTheDocument()
  })

  it('명시적 지역 필터를 바꿔도 검색 지역 목록은 그대로 유지한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    applyProvinceFilter('41')
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(card).toBeVisible()
    expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({ filters: { regionCode: '41' } }), expect.any(AbortSignal),
    )
  })

  it('가격 조건 변경은 지도 응답을 기다리지 않고 검색 지역 목록을 갱신한다', async () => {
    const repository = createRepository()
    const pendingMap = createDeferred<HousingMapIndividualResult>()
    repository.findMap.mockResolvedValueOnce(individualMapResult()).mockReturnValueOnce(pendingMap.promise)
    renderSearchedExplorer(repository)
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', { name: '임대보증금 최솟값' }), { target: { value: '100000000' } })
    await waitFor(() => expect(repository.findComplexPage).toHaveBeenCalledTimes(2))
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      null, null, 20, expect.any(AbortSignal), { regionCode: '11', minDeposit: 100000000 },
    )
    expect(screen.getByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
    await act(async () => pendingMap.reject(new Error('지도 조회 실패')))
    expect(await screen.findByText('지도 조회 실패')).toBeVisible()
    expect(screen.getByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
  })

  it('지도 이동과 실패는 진행 중인 지역 목록 더보기와 스크롤을 유지한다', async () => {
    const repository = createRepository()
    const more = createDeferred<ComplexPage>()
    repository.findComplexPage.mockResolvedValueOnce(complexPageWithNext()).mockReturnValueOnce(more.promise)
    repository.findMap.mockResolvedValueOnce(individualMapResult()).mockRejectedValueOnce(new Error('이동 지도 실패'))
    renderSearchedExplorer(repository)
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    const scroll = card.closest('.housing-results__scroll')!
    scroll.scrollTop = 140
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: /^단지 더 보기/ }))
    const signal = repository.findComplexPage.mock.calls[1][3]
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await screen.findByText('이동 지도 실패')
    expect(signal.aborted).toBe(false)
    expect(scroll.scrollTop).toBe(140)
    await act(async () => more.resolve(complexPageFor(18, '다음 페이지 단지')))
    expect(await screen.findByRole('article', { name: '다음 페이지 단지' })).toBeVisible()
    expect(card).toBeVisible()
    expect(scroll.scrollTop).toBe(140)
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
  })

  it('검색 지역을 해제하면 목록과 경계를 닫고 지도는 유지한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    const marker = await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.click(screen.getByRole('button', { name: '경계 지우기' }))
    expect(screen.queryByRole('complementary', { name: '공공임대주택 검색 결과' })).not.toBeInTheDocument()
    expect(marker).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(repository.findMap).toHaveBeenCalledOnce()
  })

  it('지역 단계에서 필터를 되돌려도 단지 목록을 요청하지 않는다', async () => {
    const repository = createRepository()
    const filteredMap = createDeferred<HousingMapAggregateResult>()
    const mapRepository = createMapRepository(aggregateMapResult())
    mapRepository.findMap
      .mockResolvedValueOnce(aggregateMapResult())
      .mockReturnValueOnce(filteredMap.promise)
    renderExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('button', { name: '서울 0곳 지역 마커 선택' })

    applyProvinceFilter('41')
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    const filteredSignal = mapRepository.findMap.mock.calls[1]?.[1]
    applyProvinceFilter('')

    expect(filteredSignal?.aborted).toBe(true)
    expect(mapRepository.findMap).toHaveBeenCalledTimes(2)
    expect(repository.findComplexPage).not.toHaveBeenCalled()
  })

  it('통합 검색 지역은 단지 확대 수준으로 이동하고 사용자의 지역 필터로 최종 지도 영역만 조회한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(individualMapResult())
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.27532584, 127.01641895)
    renderExplorer(repository, '/?complexRegionCode=11',
      searchRepository([], [region]), undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledOnce())
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))

    expect(screen.getByText('카메라 37.27532584,127.01641895')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
    expect(mapRepository.findMap).toHaveBeenCalledOnce()
    expectCurrentSearch({ complexRegionCode: '11', boundaryRegionCode: '41110' })
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))

    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    expect(mapRepository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({
      bounds: boundsAt(37.27532584, 127.01641895), filters: { regionCode: '11' }, zoom: 12.6,
    }), expect.any(AbortSignal))
    expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()
  })

  it('검색 지역으로 이동한 뒤 사용자가 적용한 지역 필터만 요청에 추가한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(individualMapResult())
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.27532584, 127.01641895)
    renderExplorer(repository, '/', searchRepository([], [region]), undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledOnce())
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    expect(mapRepository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({
      bounds: boundsAt(37.27532584, 127.01641895), zoom: 12.6,
    }), expect.any(AbortSignal))

    expect(mapRepository.findMap.mock.calls[1]?.[0]).not.toHaveProperty('filters')

    applyProvinceFilter('11')

    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(3))
    expect(mapRepository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({
      bounds: boundsAt(37.27532584, 127.01641895), zoom: 12.6, filters: { regionCode: '11' },
    }), expect.any(AbortSignal))
  })

  it('검색 지역 선택은 URL의 지역 필터를 바꾸지 않고 같은 필터 재적용은 중복 조회하지 않는다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(individualMapResult())
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.27532584, 127.01641895)
    renderExplorer(repository, '/?complexRegionCode=41',
      searchRepository([], [region]), undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledOnce())
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    expectCurrentSearch({ complexRegionCode: '41', boundaryRegionCode: '41110' })

    applyProvinceFilter('41')
    await act(async () => new Promise((resolve) => window.setTimeout(resolve, 350)))

    expect(mapRepository.findMap).toHaveBeenCalledTimes(2)
    expect(mapRepository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({
      filters: { regionCode: '41' },
    }), expect.any(AbortSignal))
  })

  it('지역 결과를 선택하면 검색을 닫고 목록을 열며 다음 지도 이동에도 유지한다', async () => {
    const repository = createRepository()
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.27532584, 127.01641895)
    renderExplorer(repository, '/', searchRepository([], [region]))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))
    expect(await screen.findByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(3))
    expect(repository.findComplexPage).toHaveBeenCalledExactlyOnceWith(null, null, 20, expect.any(AbortSignal), { regionCode: '41110' })
  })

  it('서버 지도 실패는 목록과 분리해 안내하고 같은 요청을 다시 시도한다', async () => {
    const repository = createRepository()
    const mapRepository = createMapRepository(aggregateMapResult())
    mapRepository.findMap
      .mockRejectedValueOnce(new Error('지도 집계 실패'))
      .mockResolvedValueOnce(aggregateMapResult())
    renderExplorer(repository, '/', undefined, undefined, mapRepository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const retry = await screen.findByRole('button', { name: '지도 다시 시도' })

    expect(screen.getByRole('alert')).toHaveTextContent('지도 집계 실패')
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    fireEvent.click(retry)
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('button', {
      name: '서울 0곳 지역 마커 선택',
    })).toBeVisible()
  })

  it('지도 이동이 끝나면 최신 영역을 자동 조회한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    expect(repository.findMap).toHaveBeenCalledOnce()
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))
    expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({ bounds: NEXT_BOUNDS, zoom: 14 }),
      expect.any(AbortSignal),
    )
  })

  it('검색 목록은 지역으로 조회하고 지도에는 처음 유효 영역을 적용한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    await waitFor(() => {
      expect(repository.findMap).toHaveBeenCalledOnce()
      expect(repository.findComplexPage).toHaveBeenCalledOnce()
    })
    expect(repository.findMap).toHaveBeenCalledWith(
      expect.objectContaining({ bounds: INITIAL_BOUNDS, zoom: 14 }),
      expect.any(AbortSignal),
    )
    expect(repository.findComplexPage).toHaveBeenCalledWith(
      null,
      null,
      20,
      expect.any(AbortSignal),
      { regionCode: '11' },
    )
    expect(
      await screen.findByRole('heading', { name: '서울가람 행복주택' }),
    ).toBeVisible()
    expect(screen.getByLabelText('조회된 단지 1곳')).toBeVisible()
  })

  it('단지 필터는 지도 우상단의 토픽별 도구모음으로 공고 탭에서도 유지한다', () => {
    const repository = createRepository()
    renderExplorer(repository)

    const complexFilter = screen.getByRole('toolbar', {
      name: '단지 검색 필터',
    })
    expect(complexFilter.closest('main')).toHaveClass(
      'housing-map-workspace',
    )
    expect(within(complexFilter).getAllByRole('button').map(
      (button) => button.getAttribute('aria-label'),
    )).toEqual([
      '지역 필터 열기',
      '임대유형 필터 열기',
      '모집상태 필터 열기',
      '가격 필터 열기',
      '전용면적 필터 열기',
      '준공년도 필터 열기',
      '공급기관 필터 열기',
      '모집유형 필터 열기',
    ])
    expect(screen.queryByRole('complementary', { name: '공공임대주택 검색 결과' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))

    expect(complexFilter).toBeVisible()
    expect(within(screen.getByRole('region', {
      name: '공고 목록',
    })).getByRole('region', {
      name: '공고 검색 필터',
    })).toBeVisible()
  })

  it('공고 조회 건수와 필터를 함께 표시하고 필터를 펼쳐도 건수를 유지한다', () => {
    renderExplorer(createRepository())
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))

    const panel = screen.getByRole('region', { name: '공고 목록' })
    const toolbar = within(panel).getByRole('group', { name: '공고 목록 도구' })
    expect(within(toolbar).getByText('조회 결과')).toBeVisible()
    fireEvent.click(within(toolbar).getByRole('button', { name: '공고 필터 열기' }))

    expect(within(toolbar).getByText('조회 결과')).toBeVisible()
    const collapse = within(toolbar).getByRole('button', { name: '공고 필터 접기' })
    expect(collapse).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(collapse)
    expect(within(toolbar).getByRole('button', { name: '공고 필터 열기' })).toHaveFocus()
    expect(within(panel).queryByRole('checkbox', { name: '행복주택' })).not.toBeInTheDocument()
  })

  it('가격 토픽만 적용해도 기존 지역·임대유형 조건을 보존한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(
      repository,
      '/?complexRegionCode=11&complexRentalTypes=NATIONAL_RENTAL',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    }), { target: { value: '100000000' } })
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    }), { target: { value: '200000000' } })

    await waitFor(() => {
      expect(repository.findMap).toHaveBeenCalledTimes(3)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(3)
    })
    expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({
        bounds: INITIAL_BOUNDS,
        zoom: 14,
        filters: {
          maxDeposit: 200_000_000,
          minDeposit: 100_000_000,
          regionCode: '11',
          rentalTypes: ['NATIONAL_RENTAL'],
        },
      }),
      expect.any(AbortSignal),
    )
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexRegionCode')).toBe('11')
    expect(search.getAll('complexRentalTypes')).toEqual(['NATIONAL_RENTAL'])
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexMaxDeposit')).toBe('200000000')
  })

  it('개별 필터 적용값을 URL과 지도·지역 목록·다음 페이지에 전달한다', async () => {
    const repository = createRepository()
    repository.findComplexPage.mockResolvedValue(complexPageWithNext())
    renderSearchedExplorer(repository, '/?complexRegionCode=41&complexRentalTypes=NATIONAL_RENTAL&complexApplicationStatuses=APPLYING&complexMinDeposit=100000000&complexMaxDeposit=300000000&complexMinMonthlyRent=100000&complexMaxMonthlyRent=500000&complexMinExclusiveArea=33&complexMaxExclusiveArea=66&complexBuiltYearFrom=2015&complexBuiltYearTo=2026')
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    for (const [topic, option] of [['공급기관', 'LH'], ['모집유형', '신규 모집']]) {
      fireEvent.click(screen.getByRole('button', { name: `${topic} 필터 열기` }))
      fireEvent.click(screen.getByRole('checkbox', { name: option }))
      fireEvent.click(screen.getByRole('button', { name: `${topic} 필터 적용` }))
    }
    const filters = {
      regionCode: '41', rentalTypes: ['NATIONAL_RENTAL'], applicationStatuses: ['APPLYING'],
      agencyCodes: ['LH'], recruitmentTypes: ['NEW'], builtYearFrom: 2015, builtYearTo: 2026,
      minDeposit: 100000000, maxDeposit: 300000000, minMonthlyRent: 100000, maxMonthlyRent: 500000,
      minExclusiveArea: 33, maxExclusiveArea: 66,
    }
    await waitFor(() => expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({ bounds: INITIAL_BOUNDS, filters }), expect.any(AbortSignal),
    ))
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(null, null, 20, expect.any(AbortSignal), { ...filters, regionCode: '11' })
    const query = currentSearch()
    expect(query).toMatchObject({ boundaryRegionCode: '11', complexRegionCode: '41', complexAgencyCodes: 'LH', complexRecruitmentTypes: 'NEW' })
    repository.findComplexPage.mockResolvedValueOnce(complexPageFor(18, '다음 페이지 단지'))
    fireEvent.click(await screen.findByRole('button', { name: /^단지 더 보기/ }))
    expect(await screen.findByRole('article', { name: '다음 페이지 단지' })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(null, 'cursor-2', 20, expect.any(AbortSignal), { ...filters, regionCode: '11' })
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 초기화' }))
    expect(currentSearch().complexRentalTypes).toBeUndefined()
    expect(currentSearch().complexAgencyCodes).toBe('LH')
  })

  it('공유 URL의 시군구와 복수 조건을 폼에서 다시 적용해도 보존한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(
      repository,
      '/?complexRegionCode=41135'
        + '&complexRentalTypes=NATIONAL_RENTAL'
        + '&complexRentalTypes=HAPPY_HOUSING'
        + '&complexAgencyCodes=LH'
        + '&complexAgencyCodes=GH',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    const detailFilter = screen.getByRole('region', {
      name: '지역 필터',
    })
    expect(within(detailFilter).getByLabelText('시·도')).toHaveValue('41')
    await waitFor(() => {
      expect(within(detailFilter).getByLabelText('시·군·구'))
        .toHaveValue('41135')
    })
    fireEvent.click(within(detailFilter).getByRole('button', { name: '지역 필터 적용' }))
    fireEvent.click(screen.getByRole('button', { name: '공급기관 필터 열기' }))
    expect(screen.getByRole('checkbox', { name: 'LH' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'GH' })).toBeChecked()

    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    const rentalFilter = screen.getByRole('region', {
      name: '임대유형 필터',
    })
    expect(within(rentalFilter).getByRole('checkbox', {
      name: '국민임대',
    })).toBeChecked()
    expect(within(rentalFilter).getByRole('checkbox', {
      name: '행복주택',
    })).toBeChecked()

    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexRegionCode')).toBe('41135')
    expect(new Set(search.getAll('complexRentalTypes'))).toEqual(new Set([
      'NATIONAL_RENTAL',
      'HAPPY_HOUSING',
    ]))
    expect(search.getAll('complexAgencyCodes')).toEqual(['LH', 'GH'])
  })

  it('연속 지도 이동은 마지막 idle로부터 100ms 후 한 번만 조회한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    vi.useFakeTimers()

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(50))
    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(99))
    expect(repository.findMap).toHaveBeenCalledOnce()
    await act(async () => vi.advanceTimersByTimeAsync(1))

    // Precision changes the zoom as well; the final idle alone is queried.
    expect(repository.findMap).toHaveBeenCalledTimes(2)
    expect(repository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({ zoom: 14.256 }), expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(99))
    expect(repository.findMap).toHaveBeenCalledTimes(2)
    await act(async () => vi.advanceTimersByTimeAsync(1))
    expect(repository.findMap).toHaveBeenCalledTimes(3)
    expect(repository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({ bounds: NEXT_BOUNDS }), expect.any(AbortSignal))
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
  })

  it('서버 지도는 마지막 idle 100ms 뒤 조회하고 느린 목록을 기다리지 않고 마커를 표시한다', async () => {
    vi.useFakeTimers()
    const repository = createRepository()
    const pendingList = createDeferred<ComplexPage>()
    repository.findComplexPage.mockReturnValue(pendingList.promise)
    const pendingMap = createDeferred<HousingMapIndividualResult>()
    const mapRepository = createMapRepository(individualMapResult())
    mapRepository.findMap.mockReturnValue(pendingMap.promise)
    renderSearchedExplorer(repository, '/', undefined, undefined, mapRepository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(50))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(99))
    expect(mapRepository.findMap).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTimeAsync(1))

    expect(mapRepository.findMap).toHaveBeenCalledExactlyOnceWith({
      bounds: NEXT_BOUNDS,
      zoom: 14,
    }, expect.any(AbortSignal))
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    await act(async () => pendingMap.resolve(individualMapResult()))

    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(screen.queryByRole('article', { name: '서울가람 행복주택' }))
      .not.toBeInTheDocument()
  })

  it('지도 idle 대기 중 단지 필터를 바꿔도 이전 조건 요청이 덮어쓰지 않는다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '국민임대' }))

    await waitFor(() => {
      expect(repository.findMap).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    })
    await act(async () => new Promise((resolve) => {
      window.setTimeout(resolve, 350)
    }))

    const filters = { rentalTypes: ['NATIONAL_RENTAL'] }
    expect(repository.findMap).toHaveBeenCalledTimes(2)
    expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({ bounds: NEXT_BOUNDS, zoom: 14, filters }),
      expect.any(AbortSignal),
    )
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      null,
      null,
      20,
      expect.any(AbortSignal),
      { ...filters, regionCode: '11' },
    )
  })

  it('단지 범위 손잡이는 서로 교차하지 않도록 상대 값에 맞춘다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    const minimum = screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    })
    const maximum = screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    })
    fireEvent.change(maximum, { target: { value: '100000000' } })
    fireEvent.change(minimum, { target: { value: '300000000' } })

    expect(minimum).toHaveValue('100000000')
    expect(screen.getByRole('status', {
      name: '임대보증금 선택 범위',
    })).toHaveTextContent('1억~1억')

    await waitFor(() => {
      expect(repository.findMap).toHaveBeenCalledTimes(3)
      expect(repository.findComplexPage).toHaveBeenCalledTimes(3)
    })
    expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({
        bounds: INITIAL_BOUNDS,
        zoom: 14,
        filters: { maxDeposit: 100_000_000, minDeposit: 100_000_000 },
      }),
      expect.any(AbortSignal),
    )
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexMaxDeposit')).toBe('100000000')
  })

  it('공고 필터는 단지 필터와 독립적으로 공고 목록에만 적용한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })
    const complexRequestCount = repository.findComplexPage.mock.calls.length
    const mapRequestCount = repository.findMap.mock.calls.length
    const scroll = screen.getByRole('region', { name: '공고 목록' })
      .querySelector<HTMLElement>('.housing-results__scroll')
    if (!scroll) throw new Error('공고 목록 스크롤 영역 없음')
    scroll.scrollTop = 120

    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    const announcementFilter = screen.getByRole('region', {
      name: '공고 검색 필터',
    })
    fireEvent.change(within(announcementFilter).getByLabelText('시·도'), {
      target: { value: '41' },
    })
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '공고중' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'GH' }))
    fireEvent.click(screen.getByRole('checkbox', {
      name: '예비입주자 모집',
    }))
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))

    await waitFor(() => {
      expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)
    })
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      {
        agencyCodes: ['GH'],
        applicationStatuses: ['BEFORE_APPLICATION'],
        recruitmentTypes: ['WAITLIST'],
        regionCode: '41',
        rentalTypes: ['HAPPY_HOUSING'],
      },
    )
    expect(repository.findComplexPage).toHaveBeenCalledTimes(complexRequestCount)
    expect(repository.findMap).toHaveBeenCalledTimes(mapRequestCount)
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('announcementRegionCode')).toBe('41')
    expect(search.get('complexRegionCode')).toBeNull()
    expect(scroll.scrollTop).toBe(0)

    scroll.scrollTop = 80
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(scroll.scrollTop).toBe(80)
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('button', { name: /단지 목록 보기$/ }))
    fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
    expect(within(screen.getByRole('region', {
      name: '지역 필터',
    })).getByLabelText('시·도')).toHaveValue('')
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    expect(within(announcementFilter).getByLabelText('시·도'))
      .toHaveValue('41')
  })

  it('지도 응답의 임대 조건을 정보형 마커 표시값으로 변환한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const marker = await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    expect(marker).toHaveAttribute('data-agency-label', 'LH')
    expect(marker).toHaveAttribute('data-rental-type-label', '행복')
    expect(marker).toHaveAttribute('data-deposit-label', '5천~')
    expect(marker).toHaveAttribute('data-monthly-rent-label', '20만~')
  })

  it('같은 지도 영역의 반복 idle은 지도와 지역 목록을 다시 조회하지 않는다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    expect(repository.findMap).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
  })

  it('통합 검색을 시작하고 종료해도 두 목록의 컨테이너와 읽던 위치를 유지한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository, '/', searchRepository([], []))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    const complexScroll = screen.getByRole('region', { name: '단지 목록' })
      .querySelector<HTMLElement>('.housing-results__scroll')
    if (!complexScroll) throw new Error('단지 목록 스크롤 영역 없음')
    complexScroll.scrollTop = 240
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    await screen.findByRole('heading', { name: '성남 청년 행복주택 입주자 모집 공고' })
    const announcementScroll = screen.getByRole('region', { name: '공고 목록' })
      .querySelector<HTMLElement>('.housing-results__scroll')
    if (!announcementScroll) throw new Error('공고 목록 스크롤 영역 없음')
    announcementScroll.scrollTop = 360

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    expect(complexScroll.isConnected).toBe(true)
    expect(announcementScroll.isConnected).toBe(true)
    expect(screen.getByRole('button', { name: '공고 목록' })).toBeVisible()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })

    expect(announcementScroll.scrollTop).toBe(360)
    fireEvent.click(screen.getByRole('button', { name: /단지 목록 보기$/ }))
    expect(complexScroll.scrollTop).toBe(240)
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
  })

  it('공고의 최초 로딩과 빈 결과 사이에도 같은 본문 스크롤 영역을 유지한다', async () => {
    const pending = createDeferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValue(pending.promise)
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    const panel = screen.getByRole('region', { name: '공고 목록' })
    const scroll = panel.querySelector('.housing-results__scroll')
    expect(scroll).not.toBeNull()
    await act(() => pending.resolve({
      ...announcementPage(),
      items: [],
      hasNext: false,
      nextCursor: null,
    }))
    expect(await screen.findByText('현재 확인되는 공고가 없습니다.')).toBeVisible()
    expect(panel.querySelector('.housing-results__scroll')).toBe(scroll)
  })

  it('검색 중에는 왼쪽 패널 전체에 통합 검색 결과만 표시한다', async () => {
    const repository = createRepository()
    const complex = searchItem('COMPLEX', '17', '서울가람 행복주택', 37.5, 126.9)
    renderSearchedExplorer(repository, '/', searchRepository([complex], []))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })

    expect(await screen.findByRole('heading', { name: '단지' })).toBeVisible()
    expect(screen.getByRole('button', { name: /단지 목록 보기$/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: '이 지역에서 검색' })).not.toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })

    expect(screen.getByRole('button', { name: /단지 목록 보기$/ })).toBeVisible()
  })

  it('검색한 단지를 선택하면 해당 좌표로 이동하고 중앙 마커를 선택 강조한다', async () => {
    const repository = createRepository()
    const complex = searchItem('COMPLEX', '99', '검색된 행복주택', 37.5, 126.9)
    repository.findComplexDetail.mockResolvedValue({ ...complexDetail(), complexId: '99', name: '검색된 행복주택' })
    renderExplorer(repository, '/', searchRepository([complex], []))

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울가람' } })
    fireEvent.click(await screen.findByRole('button', { name: /검색된 행복주택/ }))

    expect(await screen.findByText('카메라 37.5,126.9')).toBeVisible()
    expect(await screen.findByRole('button', {
      name: '검색된 행복주택 지도 마커 선택',
    })).toHaveAttribute('data-selected', 'true')
    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()
    expect(await screen.findByRole('complementary', { name: '검색된 행복주택 단지 상세 정보' })).toBeVisible()
  })

  it('검색한 공고를 선택하면 연결 단지 좌표로 이동한다', async () => {
    const repository = createRepository()
    const announcement = searchItem('ANNOUNCEMENT', '201', '서울 행복주택 공고', 37.5, 126.9)
    renderExplorer(repository, '/', searchRepository([announcement], []))

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울 행복' } })
    fireEvent.click(await screen.findByRole('button', { name: /서울 행복주택 공고/ }))

    expect(await screen.findByText('카메라 37.5,126.9')).toBeVisible()
    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.queryByRole('heading', { name: '검색결과' })).not.toBeInTheDocument()
  })

  it('경계가 있는 좌표 없는 지역은 전체 경계로 이동하고 기존 주택 지역 필터를 보존한다', async () => {
    const pending = createDeferred<RegionBoundary>()
    const boundaryRepository = { find: vi.fn().mockReturnValue(pending.promise) }
    const region = searchItem('REGION', '41111', '경기도 수원시 장안구', null, null)
    const repository = createRepository()
    renderExplorer(repository, '/?complexRegionCode=11', searchRepository([], [region]), undefined, undefined, boundaryRepository)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '장안' } })
    fireEvent.click(await screen.findByRole('button', { name: /경기도 수원시 장안구/ }))
    expectCurrentSearch({ complexRegionCode: '11', boundaryRegionCode: '41111' })
    expect(screen.getByText(`카메라 ${(37.3 + 37.4) / 2},127`)).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
    expect(within(screen.getByRole('region', { name: '검색 지역 표시' })).getByText('경기도 수원시 장안구')).toBeVisible()
    expect(screen.getByRole('region', { name: '통합 검색' })).toContainElement(screen.getByRole('region', { name: '검색 지역 표시' }))
    expect(screen.queryByRole('link', { name: '저작권' })).not.toBeInTheDocument()
    const request = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => pending.resolve({ regionCode: '41111', version: 'test', polygons: [] }))
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('41111')
    expect(screen.getByRole('link', { name: '저작권' })).toHaveAttribute('href', '/map-data-credits.html')
    expect(screen.getByTestId('map-camera-request').textContent).toBe(request)
    expect(screen.getByText('카메라 37.475,126.9')).toBeVisible()
    expect(screen.queryByRole('button', { name: '검색결과 닫기' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '검색 지역 표시' })).getByText('경기도 수원시 장안구')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '경계 지우기' }))
    expectCurrentSearch({ complexRegionCode: '11' })
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('none')
    expect(screen.queryByRole('link', { name: '저작권' })).not.toBeInTheDocument()
    expect(screen.getByTestId('map-camera-request').textContent).toBe(request)
    expect(screen.getByText('카메라 37.475,126.9')).toBeVisible()
  })

  it('URL에서 경계를 복원하고 같은 지역 재선택, 다시 보기와 뒤로 앞으로마다 한 번만 이동한다', async () => {
    const region = searchItem('REGION', '41110', '경기도 수원시', null, null)
    renderExplorer(createRepository(), '/?boundaryRegionCode=41111', searchRepository([], [region]))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41111'))
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent('1')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /^경기도 수원시/ }))
    expectCurrentSearch({ boundaryRegionCode: '41110' })
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent('2')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /^경기도 수원시/ }))
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent('3')
    fireEvent.click(screen.getByRole('button', { name: '해당 지역으로 이동' }))
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent('4')
    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41111'))
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent('5')
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent('6')
  })

  it('경계 실패는 명시적으로 알리고 재시도해도 카메라를 다시 이동하지 않는다', async () => {
    const boundaryRepository = { find: vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ regionCode: '41110', version: 'test', polygons: [] }) }
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110', undefined, undefined, undefined, boundaryRepository)
    expect(await screen.findByText('지역 경계를 불러오지 못했습니다.')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    const request = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '경계 다시 시도' }))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expect(screen.getByTestId('map-camera-request').textContent).toBe(request)
    expect(screen.getByText('카메라 37.475,126.9')).toBeVisible()
  })

  it('지역 경계는 가격과 임대유형 변경, 마커 상세 선택 이후에도 유지된다', async () => {
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110')
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    const request = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', { name: '임대보증금 최댓값' }), { target: { value: '200000000' } })
    expectCurrentSearch({ boundaryRegionCode: '41110', complexMaxDeposit: '200000000' })
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expectCurrentSearch({
      boundaryRegionCode: '41110', complexMaxDeposit: '200000000', complexRentalTypes: 'HAPPY_HOUSING',
    })
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110')
    expect(screen.getByTestId('map-camera-request').textContent).toBe(request)
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await screen.findByRole('button', { name: '단지 상세 닫기' })
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110')
    const query = new URLSearchParams(screen.getByTestId('location-search').textContent ?? '')
    expect(query.get('boundaryRegionCode')).toBe('41110')
    expect(query.get('complexMaxDeposit')).toBe('200000000')
    expect(query.get('complexRentalTypes')).toBe('HAPPY_HOUSING')
  })

  it('지도에 실제로 겹친 도구막대 크기를 중심 위치에 반영한다', async () => {
    const original = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('map-surface')) {
        return { x: 400, y: 64, top: 64, left: 400, right: 1200, bottom: 664, width: 800, height: 600, toJSON() {} }
      }
      if (this.getAttribute('aria-label') === '단지 검색 필터') {
        return { x: 420, y: 80, top: 80, left: 420, right: 1180, bottom: 126, width: 760, height: 46, toJSON() {} }
      }
      return original.call(this)
    })
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110')
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expect(JSON.parse(screen.getByTestId('map-camera-offset').textContent ?? '{}')).toEqual({ x: 0, y: 27 })
  })

  it('지역 이동은 펼쳐지는 목록의 최종 폭을 피해 중심을 맞춘다', async () => {
    const original = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('map-surface')) return new DOMRect(60, 0, 1220, 720)
      if (this.classList.contains('housing-browse-panel')) return new DOMRect(60, 0, 460, 720)
      // At the start of the slide the list itself is still outside the map.
      if (this.classList.contains('housing-results')) return new DOMRect(-400, 130, 460, 590)
      return original.call(this)
    })
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110')
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expect(JSON.parse(screen.getByTestId('map-camera-offset').textContent ?? '{}').x).toBe(226)

    fireEvent.click(screen.getByRole('button', { name: '목록 접기' }))
    fireEvent.click(screen.getByRole('button', { name: '해당 지역으로 이동' }))
    expect(JSON.parse(screen.getByTestId('map-camera-offset').textContent ?? '{}').x).toBe(0)
  })

  it('모바일 전체 화면 목록은 지도 중심을 옆으로 밀지 않고 검색창 여백만 반영한다', async () => {
    const original = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('map-surface') || this.classList.contains('housing-browse-panel')) {
        return new DOMRect(52, 0, 338, 844)
      }
      if (this.classList.contains('housing-search-overlay')) {
        return new DOMRect(62, 12, 318, this.closest('.is-searching') ? 832 : 48)
      }
      return original.call(this)
    })
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.3, 127)
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110', searchRepository([], [region]))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expect(JSON.parse(screen.getByTestId('map-camera-offset').textContent ?? '{}'))
      .toEqual({ x: 0, y: 26 })
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))
    expect(JSON.parse(screen.getByTestId('map-camera-offset').textContent ?? '{}'))
      .toEqual({ x: 0, y: 26 })
  })

  it('미지원 시도 선택은 이전 경계를 지우고 실제 대표 좌표로 이동한다', async () => {
    const region = searchItem('REGION', '41', '경기도 전체', 37.2, 127.1)
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110', searchRepository([], [region]))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '경기' } })
    fireEvent.click(await screen.findByRole('button', { name: /경기도 전체/ }))
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('none')
    expect(screen.getByText('이 지역은 경계 정보를 제공하지 않습니다.')).toBeVisible()
    expect(screen.getByText('카메라 37.2,127.1')).toBeVisible()
  })

  it('지역 검색은 단지 확대 수준으로 이동하고 필터를 추가하지 않으며 지도 idle 뒤 조회한다', async () => {
    const repository = createRepository()
    const region = searchItem('REGION', '41110', '경기도 수원시', 37.27532584, 127.01641895)
    renderExplorer(repository, '/', searchRepository([], [region]))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '수원' } })
    fireEvent.click(await within(await screen.findByRole('region', { name: '지역' })).findByRole('button', { name: /경기도 수원시/ }))

    expect(repository.findMap).toHaveBeenCalledOnce()
    expect(screen.getByText('카메라 37.27532584,127.01641895')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12.6')
    expectCurrentSearch({ boundaryRegionCode: '41110' })
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))
    expect(repository.findMap).toHaveBeenLastCalledWith(
      expect.objectContaining({ bounds: boundsAt(37.27532584, 127.01641895), zoom: 12.6 }),
      expect.any(AbortSignal),
    )
  })

  it('경계와 좌표가 없는 지역도 목록을 조회하고 카메라는 유지한다', async () => {
    const repository = createRepository()
    const region = searchItem('REGION', '99999', '미지원 지역', null, null)
    renderExplorer(repository, '/', searchRepository([], [region]))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '장안' } })
    const unavailable = await screen.findByRole('button', { name: /미지원 지역/ })

    expect(unavailable).toBeEnabled()
    fireEvent.click(unavailable)
    expectCurrentSearch({ boundaryRegionCode: '99999' })
    expect(await screen.findByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenCalledWith(null, null, 20, expect.any(AbortSignal), { regionCode: '99999' })
    expect(screen.getByText('카메라 37.56,127')).toBeVisible()
    expect(repository.findMap).toHaveBeenCalledOnce()
  })

  it('검색결과를 닫아도 열린 상세와 현재 지도 위치 및 기존 목록 탭을 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/', searchRepository([], []))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const marker = await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    fireEvent.click(marker)
    const detail = await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    fireEvent.click(screen.getByRole('button', { name: '검색결과 닫기' }))

    expect(detail).toBeVisible()
    expectCurrentSearch({ complexId: '17' })
    expect(screen.getByRole('button', { name: '공고 목록' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
  })

  it('지도 이동은 검색 목록의 내용, 조회 횟수, 스크롤을 바꾸지 않는다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValueOnce(individualMapResult()).mockResolvedValueOnce(individualMapResult([mapComplexFor(18, '지도 밖 단지')]))
    renderSearchedExplorer(repository)
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    const scroll = card.closest('.housing-results__scroll')!
    scroll.scrollTop = 95
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))
    expect(repository.findMap).toHaveBeenLastCalledWith(expect.objectContaining({ bounds: NEXT_BOUNDS }), expect.any(AbortSignal))
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(card).toBeVisible()
    expect(scroll.scrollTop).toBe(95)
  })

  it('저배율 집계 지도에서도 검색한 지역 목록을 표시한다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValue(aggregateMapResult())
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))
    expect(await screen.findByRole('button', { name: '서울 0곳 지역 마커 선택' })).toBeVisible()
    expect(await screen.findByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenCalledExactlyOnceWith(null, null, 20, expect.any(AbortSignal), { regionCode: '11' })
  })

  it('더 보기 실패는 첫 페이지 대신 실패한 cursor를 다시 요청한다', async () => {
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(complexPageWithNext())
      .mockRejectedValueOnce(new Error('다음 페이지 실패'))
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
    renderSearchedExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: /^단지 더 보기/ }))
    await screen.findByText('다음 페이지 실패')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenNthCalledWith(
      2,
      null,
      'cursor-2',
      20,
      expect.any(AbortSignal),
      { regionCode: '11' },
    )
    expect(repository.findComplexPage).toHaveBeenNthCalledWith(
      3,
      null,
      'cursor-2',
      20,
      expect.any(AbortSignal),
      { regionCode: '11' },
    )
  })

  it.each([
    ['바로 선택할 때', false],
    ['카드의 상세 응답을 기다리다 선택할 때', true],
  ])('지도 마커를 %s 상세만 열고 카메라와 지도 및 목록 조회를 유지한다', async (_label, pendingCardSelection) => {
    const repository = createRepository()
    const detailResponse = createDeferred<ComplexDetail>()
    repository.findComplexDetail.mockReturnValue(detailResponse.promise)
    const mapRepository = createMapRepository(individualMapResult())
    renderSearchedExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(mapRepository.findMap).toHaveBeenCalled())
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    if (pendingCardSelection) {
      fireEvent.click(within(card).getByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
      await screen.findByText('단지 상세를 불러오고 있습니다.')
    }

    fireEvent.click(screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await act(async () => detailResponse.resolve(complexDetail()))

    expect(screen.getByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })).toBeVisible()
    expect(screen.getByText('카메라 37.56,127')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
    expect(card).toHaveAttribute('aria-current', 'true')
    expect(mapRepository.findMap).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
  })

  it('지도 마커 선택과 목록 카드 선택 상태를 같은 ID로 동기화한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    const card = screen.getByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(
      screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }),
    )

    expect(
      card,
    ).toHaveAttribute('aria-current', 'true')
    expect(repository.findComplexDetail).toHaveBeenCalledWith(
      '17',
      expect.any(AbortSignal),
    )
  })

  it('목록의 기관 코드와 이미지를 카드에 전달하고 상세 요청 없이 표시한다', async () => {
    const repository = createRepository()
    const page = complexPage()
    repository.findComplexPage.mockResolvedValueOnce({
      ...page,
      items: page.items.map((item) => ({
        ...item,
        thumbnailImageUrl: 'https://example.com/complex.jpg',
      })),
    })
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    expect(within(card).getByRole('img')).toHaveAttribute(
      'src', 'https://example.com/complex.jpg',
    )
    expect(within(card).getByText('LH')).toBeInTheDocument()
    expect(repository.findComplexDetail).not.toHaveBeenCalled()
  })

  it('검색 지역 페이지를 더 읽는 동안 최소 건수를 표시하고 마지막 페이지에서 확정한다', async () => {
    const repository = createRepository()
    const items = Array.from({ length: 45 }, (_, index) => complexPageFor(index + 1, `단지 ${index + 1}`).items[0])
    repository.findMap.mockResolvedValue(individualMapResult(items.map((item) => ({ ...mapComplex(), complexId: item.complexId }))))
    repository.findComplexPage
      .mockResolvedValueOnce({ ...complexPageWithNext(), items: items.slice(0, 20) })
      .mockResolvedValueOnce({ ...complexPageWithNext(), items: items.slice(20, 40), nextCursor: 'cursor-3' })
      .mockResolvedValueOnce({ ...complexPage(), items: items.slice(40) })
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    expect(await screen.findByLabelText('조회된 단지 20곳 이상')).toHaveTextContent('20곳 이상')
    const more = screen.getByRole('button', { name: /^단지 더 보기/ })
    expect(more).toHaveTextContent('(20 | —)')
    fireEvent.click(more)
    await waitFor(() => expect(screen.getByRole('button', { name: /^단지 더 보기/ })).toHaveTextContent('(40 | —)'))
    expect(screen.getByLabelText('조회된 단지 40곳 이상')).toHaveTextContent('40곳 이상')
    fireEvent.click(screen.getByRole('button', { name: /^단지 더 보기/ }))
    await screen.findByRole('article', { name: '단지 45' })
    expect(screen.queryByRole('button', { name: /^단지 더 보기/ })).not.toBeInTheDocument()
    expect(screen.getByLabelText('조회된 단지 45곳')).toHaveTextContent('45곳')
  })

  it('지도 마커 수와 관계없이 검색 지역 목록 건수를 표시한다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValue(individualMapResult(Array.from({ length: 25 }, (_, index) => mapComplexFor(index + 1, `지도 단지 ${index + 1}`))))
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    expect(await screen.findByLabelText('조회된 단지 1곳')).toHaveTextContent('1곳')
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledOnce())
    expect(screen.getByLabelText('조회된 단지 1곳')).toHaveTextContent('1곳')
  })

  it('단지 초기 조회와 전체 결과 수를 count와 지도 busy에 반영한다', async () => {
    const repository = createRepository()
    const mapDeferred = createDeferred<HousingMapIndividualResult>()
    const pageDeferred = createDeferred<ComplexPage>()
    repository.findMap.mockReturnValueOnce(mapDeferred.promise)
    repository.findComplexPage.mockReturnValueOnce(pageDeferred.promise)
    renderSearchedExplorer(repository)

    expect(screen.getByLabelText('단지 목록 불러오는 중')).toHaveTextContent(
      '불러오는 중',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toHaveAttribute('aria-busy', 'true')

    await act(async () => {
      mapDeferred.resolve(individualMapResult())
      pageDeferred.resolve(complexPageWithNext())
    })

    expect(await screen.findByLabelText(
      '조회된 단지 1곳 이상',
    )).toHaveTextContent('1곳 이상')
    expect(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).toHaveAttribute('aria-busy', 'false')
    expect(within(screen.getByRole('complementary', {
      name: '공공임대주택 검색 결과',
    })).getAllByRole('status')).toHaveLength(1)
  })

  it('카드와 marker hover 및 focus를 연결하되 marker 선택은 목록을 스크롤하지 않는다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    const card = await screen.findByRole('article', {
      name: '서울가람 행복주택',
    })
    const marker = screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    const cardAction = within(card).getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    const scroll = card.closest<HTMLElement>('.housing-results__scroll')
    if (!scroll) throw new Error('단지 목록 스크롤 영역 없음')
    const pageScrollTop = document.documentElement.scrollTop
    Object.defineProperty(scroll, 'clientHeight', { configurable: true, value: 300 })
    vi.spyOn(scroll, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 200, 440, 300))
    vi.spyOn(card, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 600, 400, 180))
    let listScrollTop = 0
    const updateScroll = vi.fn((value: number) => {
      listScrollTop = value
    })
    Object.defineProperty(scroll, 'scrollTop', {
      configurable: true,
      get: () => listScrollTop,
      set: updateScroll,
    })
    const scrollIntoView = vi.fn()
    card.scrollIntoView = scrollIntoView

    fireEvent.mouseEnter(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    expect(marker).toHaveAttribute('data-highlighted', 'true')
    fireEvent.mouseLeave(marker)
    expect(card).not.toHaveAttribute('data-hovered')
    expect(marker).not.toHaveAttribute('data-highlighted')

    fireEvent.mouseEnter(card)
    expect(marker).toHaveAttribute('data-highlighted', 'true')
    fireEvent.focus(cardAction)
    fireEvent.mouseLeave(card)
    expect(marker).toHaveAttribute('data-highlighted', 'true')

    fireEvent.mouseEnter(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    expect(updateScroll).not.toHaveBeenCalled()
    fireEvent.mouseLeave(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    expect(marker).toHaveAttribute('data-highlighted', 'true')
    fireEvent.blur(cardAction)
    expect(card).not.toHaveAttribute('data-hovered')
    expect(marker).not.toHaveAttribute('data-highlighted')
    fireEvent.focus(marker)
    expect(card).toHaveAttribute('data-hovered', 'true')
    fireEvent.blur(marker)
    expect(card).not.toHaveAttribute('data-hovered')

    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    expect(screen.getByRole('button', { name: '공고 목록' }))
      .toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(marker)

    expect(scroll.scrollTop).toBe(0)
    expect(updateScroll).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '공고 목록' })).toHaveAttribute('aria-expanded', 'true')
    expect(card).toHaveAttribute('aria-current', 'true')
    expect(scrollIntoView).not.toHaveBeenCalled()
    expect(document.documentElement.scrollTop).toBe(pageScrollTop)
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
  })

  it.each([
    ['이미 보이는 카드', 250, 180],
    ['위쪽에 가려진 카드', 150, 180],
    ['목록보다 큰 카드', 600, 400],
  ])('marker 선택 시 %s도 기존 목록 스크롤을 유지한다', async (
    _label, top, height,
  ) => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    const scroll = card.closest<HTMLElement>('.housing-results__scroll')
    if (!scroll) throw new Error('단지 목록 스크롤 영역 없음')
    scroll.scrollTop = 300
    Object.defineProperty(scroll, 'clientHeight', { configurable: true, value: 300 })
    vi.spyOn(scroll, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 200, 440, 300))
    vi.spyOn(card, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, top, 400, height))
    const scrollIntoView = vi.fn()
    card.scrollIntoView = scrollIntoView

    fireEvent.click(screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })

    expect(scroll.scrollTop).toBe(300)
    expect(card).toHaveAttribute('aria-current', 'true')
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('상세가 열린 선택 단지는 영역 밖에 유지하고 닫힌 뒤 새 결과에서 정리한다', async () => {
    const repository = createRepository()
    repository.findMap
      .mockResolvedValueOnce(individualMapResult())
      .mockResolvedValueOnce(individualMapResult([mapComplexFor(18, '서울마루 국민임대')]))
      .mockResolvedValueOnce(individualMapResult())
    repository.findComplexPage
      .mockResolvedValueOnce(complexPage())
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
      .mockResolvedValueOnce(complexPage())
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const initialMarker = await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    fireEvent.click(initialMarker)
    const openedDetail = await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    await screen.findByRole('button', { name: '서울마루 국민임대 지도 마커 선택' })
    expect(screen.getByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })).toBe(openedDetail)
    expect(openedDetail).toBeVisible()
    expect(repository.findComplexDetail).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toHaveAttribute('data-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    await waitFor(() => expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
      .not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '서울마루 국민임대 지도 마커 선택' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const returnedMarker = await screen.findByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    expect(returnedMarker).not.toHaveAttribute('data-selected')
  })

  it('상세 닫기는 일반 마커를 유지하되 선택과 임시 마커를 즉시 해제한다', async () => {
    const repository = createRepository()
    repository.findComplexDetail.mockResolvedValue({ ...complexDetail(), complexId: '18', name: '다른 단지' })
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const normalMarker = await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.click(screen.getByRole('button', { name: '단지 18 직접 열기' }))
    const temporaryMarker = await screen.findByRole('button', { name: '다른 단지 지도 마커 선택' })
    expect(temporaryMarker).toHaveAttribute('data-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expect(temporaryMarker).not.toBeInTheDocument())
    expect(normalMarker).toBeVisible()
    expect(normalMarker).not.toHaveAttribute('data-selected')
  })

  it('최근 본 단지는 지도 결과와 별도로 남고 접기와 재방문 및 상세 재조회를 지원한다', async () => {
    const repository = createRepository()
    const view = renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    const toggle = screen.getByRole('button', { name: '최근 본 단지' })
    expect(screen.getByRole('navigation', { name: '주요 메뉴' })).toContainElement(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const resultsScroll = document.querySelector('.housing-results__scroll')
    if (!resultsScroll) throw new Error('단지 목록 스크롤 영역 없음')
    resultsScroll.scrollTop = 300
    fireEvent.click(toggle)
    expect(resultsScroll.scrollTop).toBe(300)
    const recent = screen.getByRole('region', { name: '최근 본 단지' })
    expect(within(recent).getByLabelText('공급기관 LH, 임대유형 행복주택')).toBeVisible()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(within(recent).getAllByRole('listitem')).toHaveLength(1)
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(within(recent).queryByRole('button', { name: /서울가람 행복주택/ })).not.toBeInTheDocument()
    view.unmount()

    renderSearchedExplorer(repository)
    const restoredToggle = screen.getByRole('button', { name: '최근 본 단지' })
    expect(restoredToggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(restoredToggle)
    const restoredRecent = screen.getByRole('region', { name: '최근 본 단지' })
    expect(within(restoredRecent).getByLabelText('공급기관 LH, 임대유형 행복주택')).toBeVisible()
    fireEvent.click(within(restoredRecent).getByRole('button', { name: /서울가람 행복주택/ }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    expect(repository.findComplexDetail).toHaveBeenCalledTimes(2)
  })

  it.each([767, 1023])('%spx 이하 화면에서 최근 본 상세를 열고 닫으면 기록 목록과 포커스를 복원한다', async (breakpoint) => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === `(max-width: ${breakpoint}px)` || query === '(max-width: 1023px)' }))
    try {
      renderExplorer(createRepository(), '/?complexId=17')
      await screen.findByRole('region', { name: /상세 내용$/ })
      fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
      const toggle = screen.getByRole('button', { name: '최근 본 단지' })
      fireEvent.click(toggle)
      const recent = screen.getByRole('region', { name: '최근 본 단지' })
      const opener = within(recent).getByRole('button', { name: /서울가람 행복주택/ })
      opener.focus()
      fireEvent.click(opener)
      await screen.findByRole('region', { name: /상세 내용$/ })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
      expect(screen.getByRole('region', { name: '최근 본 단지' })).toBe(recent)
      expect(toggle).toHaveAttribute('aria-expanded', 'true')
      await waitFor(() => expect(opener).toHaveFocus())
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('단지 상세 응답 대기 중 사용자가 이동한 지도는 응답 도착 후에도 유지한다', async () => {
    const repository = createRepository()
    const pending = createDeferred<ComplexDetail>()
    repository.findComplexDetail.mockReturnValue(pending.promise)
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    await act(async () => pending.resolve(complexDetail()))

    expect(screen.getByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })).toBeVisible()
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
  })

  it('상단 소개와 로컬 배지 없이도 상세 진입과 지도 마커 경로를 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?complexId=17')

    const detailHeading = await screen.findByRole('heading', {
      name: '서울가람 행복주택',
      level: 2,
    })
    await waitFor(() => expect(detailHeading).toHaveFocus())
    expect(screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })).toBeVisible()
    expect(screen.queryByText('로컬 mock')).not.toBeInTheDocument()
  })

  it('기존 지도 query는 최초 카메라에 한 번 반영하고 URL에서 제거한다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?source=shared&mapLat=37.58123&mapLng=126.99123&mapZoom=15.75',
    )

    await waitFor(() => {
      expect(screen.getByText('카메라 37.58123,126.99123')).toBeVisible()
      expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('15.75')
      expectCurrentSearch({ source: 'shared' })
    })
  })

  it.each([
    '?source=shared&mapLat=37.5#map',
    '?source=shared&mapLat=37.5&mapLat=37.6&mapLng=127&mapZoom=14#map',
    '?source=shared&mapLat=91&mapLng=127&mapZoom=14#map',
  ])(
    '부분, 중복 또는 범위 밖 지도 query %s는 모두 제거하고 기본 카메라로 복구한다',
    async (initialEntry) => {
      const repository = createRepository()
      renderExplorer(repository, initialEntry)

      await waitFor(() => {
        expectCurrentSearch({ source: 'shared' })
      })
      expect(screen.getByTestId('location-hash')).toHaveTextContent('#map')
      expect(screen.getByText(
        `카메라 ${SEOUL_CITY_HALL_CENTER.latitude},${SEOUL_CITY_HALL_CENTER.longitude}`,
      )).toBeVisible()
      expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14')
    },
  )

  it('지도 idle은 URL을 바꾸지 않고 무관 query, hash, state를 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared#results')
    fireEvent.click(screen.getByRole('button', { name: '공유 상태 설정' }))
    await waitFor(() => {
      expect(screen.getByTestId('location-state')).toHaveTextContent(
        'shared-state',
      )
    })

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))

    await waitFor(() => {
      expectCurrentSearch({ source: 'shared' })
    })
    expect(screen.getByTestId('location-hash')).toHaveTextContent('#results')
    expect(screen.getByTestId('location-state')).toHaveTextContent(
      'shared-state',
    )

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await act(async () => Promise.resolve())
    expectCurrentSearch({ source: 'shared' })
  })

  it('동일한 camera idle은 URL과 history, 조회 세대를 반복하지 않는다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(
      repository,
      '/?mapLat=37.56661&mapLng=126.97839&mapZoom=14.26',
    )
    const locationKey = screen.getByTestId('location-key').textContent

    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await waitFor(() => {
      expect(repository.findMap).toHaveBeenCalledOnce()
      expect(repository.findComplexPage).toHaveBeenCalledOnce()
    })
    expect(screen.getByTestId('location-key').textContent).toBe(locationKey)

    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await act(async () => new Promise((resolve) => {
      window.setTimeout(resolve, 350)
    }))
    expect(repository.findMap).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expectCurrentSearch({ boundaryRegionCode: '11',})
  })

  it('상세 닫기는 현재 URL만 정리하고 지도 위치와 줌 및 무관 상태를 유지한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository, '/?source=shared&mapLat=37.58123&mapLng=126.99123&mapZoom=15.50#results')
    fireEvent.click(screen.getByRole('button', { name: '공유 상태 설정' }))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    const openButton = await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' })
    const listLocationKey = screen.getByTestId('location-key').textContent
    fireEvent.click(openButton)
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    expectCurrentSearch({ boundaryRegionCode: '11', complexId: '17', source: 'shared' })
    expect(screen.getByTestId('location-key').textContent).not.toBe(listLocationKey)

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expectCurrentSearch({ boundaryRegionCode: '11', source: 'shared' }))
    expect(screen.getByTestId('location-key').textContent).not.toBe(listLocationKey)
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
    expect(screen.getByTestId('location-hash')).toHaveTextContent('#results')
    expect(screen.getByTestId('location-state')).toHaveTextContent('shared-state')
    expect(screen.getByTestId('location-state')).not.toHaveTextContent('toadzipDetail')
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    expectCurrentSearch({ boundaryRegionCode: '11', source: 'shared' })
    expect(screen.queryByRole('complementary', { name: /단지 상세 정보/ })).not.toBeInTheDocument()
  })

  it.each(['단지 상세 닫기', '브라우저 뒤로'])('%s 후 선택을 해제하고 URL과 focus를 복원한다', async (closeAction) => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    expect(screen.getByText('카메라 37.56,127')).toBeVisible()
    expectCurrentSearch({ boundaryRegionCode: '11',})

    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    const focus = vi.spyOn(openButton, 'focus')
    openButton.focus()
    fireEvent.click(openButton)

    const detailHeading = await screen.findByRole('heading', {
      name: '서울가람 행복주택',
      level: 2,
    })
    await waitFor(() => expect(detailHeading).toHaveFocus())
    expectCurrentSearch({ boundaryRegionCode: '11', complexId: '17' })
    expect(screen.getByText('카메라 37.5,126.9')).toBeVisible()
    const marker = screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    expect(marker).toHaveAttribute('data-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: closeAction }))

    await waitFor(() => expect(openButton).toHaveFocus())
    expect(focus).toHaveBeenLastCalledWith({ preventScroll: true })
    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
    expectCurrentSearch({ boundaryRegionCode: '11',})
    expect(screen.getByText('카메라 37.5,126.9')).toBeVisible()
    expect(marker).not.toHaveAttribute('data-selected')
    expect(screen.getByRole('article', { name: '서울가람 행복주택' }))
      .not.toHaveAttribute('aria-current', 'true')
  })

  it('다른 목록을 연 뒤 상세 닫기를 누르면 선택한 결과 탭으로 focus가 돌아간다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())

    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    const announcementTab = screen.getByRole('button', { name: '공고 목록' })
    fireEvent.click(announcementTab)
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    expect(screen.queryByRole('button', { name: '단지 상세 닫기' })).not.toBeInTheDocument()

    await waitFor(() => expect(announcementTab).toHaveFocus())
    expect(announcementTab).toHaveAttribute('aria-expanded', 'true')
    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
  })

  it('단지 카드의 대표 공고를 닫으면 원래 단지 탭과 action으로 돌아간다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    const openAnnouncement = screen.getByRole('button', {
      name: '대표 공고 상세 보기',
    })
    openAnnouncement.focus()
    fireEvent.click(openAnnouncement)
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expectCurrentSearch({ boundaryRegionCode: '11', announcementId: '117' })

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))

    await waitFor(() => expect(openAnnouncement).toHaveFocus())
    expect(screen.getByRole('button', { name: /단지 목록 보기$/ }))
      .toHaveAttribute('aria-expanded', 'true')
    expectCurrentSearch({ boundaryRegionCode: '11',})
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
  })

  it('내부에서 연 상세은 뒤로 갔다가 앞으로 온 뒤에도 닫기로 원래 목록에 복귀한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    const openComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openComplex.focus()
    fireEvent.click(openComplex)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => {
      expectCurrentSearch({ boundaryRegionCode: '11',})
    })
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    await waitFor(() => {
      expectCurrentSearch({ boundaryRegionCode: '11', complexId: '17' })
    })

    fireEvent.click(await screen.findByRole('button', {
      name: '단지 상세 닫기',
    }))
    await waitFor(() => {
      expectCurrentSearch({ boundaryRegionCode: '11',})
    })
  })

  it('직접 URL 상세는 목록 요청 전에도 조회하고 임시 마커를 추가한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&complexId=17')

    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    const marker = screen.getByRole('button', {
      name: '서울가람 행복주택 지도 마커 선택',
    })
    expect(marker).toBeVisible()
    expect(marker).toHaveAttribute('data-deposit-label', '5천~')
    expect(marker).toHaveAttribute('data-monthly-rent-label', '20만~')
    expect(repository.findComplexPage).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
  })

  it('없는 단지와 일시 오류를 구분하고 일시 오류만 다시 시도한다', async () => {
    const repository = createRepository()
    repository.findComplexDetail.mockRejectedValueOnce(
      new PublicHousingHttpError(404, {
        code: 'COMPLEX_NOT_FOUND',
        message: '단지를 찾을 수 없습니다.',
        traceId: 'trace-test',
      }),
    )
    renderExplorer(repository, '/?complexId=999')

    expect(await screen.findByText('단지를 찾을 수 없습니다.')).toBeVisible()
    const detailState = screen.getByRole('complementary', {
      name: '단지 상세 정보',
    })
    expect(detailState).toHaveFocus()
    expect(screen.queryByRole('button', { name: '다시 시도' }))
      .not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '공공임대주택 지도' })).toBeVisible()

    fireEvent.keyDown(detailState, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    })
  })

  it('일시 오류는 현재 URL의 같은 단지 상세를 다시 요청한다', async () => {
    const repository = createRepository()
    repository.findComplexDetail
      .mockRejectedValueOnce(new Error('연결이 잠시 끊겼습니다.'))
      .mockResolvedValueOnce(complexDetail())
    renderExplorer(repository, '/?complexId=17')

    expect(await screen.findByText('단지 상세를 불러오지 못했습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(repository.findComplexDetail).toHaveBeenCalledTimes(2)
  })

  it('닫힌 뒤 늦게 끝난 상세 응답은 화면을 다시 열지 않는다', async () => {
    let resolveDetail: (detail: ComplexDetail) => void = () => undefined
    const repository = createRepository()
    repository.findComplexDetail.mockReturnValueOnce(
      new Promise<ComplexDetail>((resolve) => {
        resolveDetail = resolve
      }),
    )
    renderExplorer(repository, '/?complexId=17')

    expect(await screen.findByText('단지 상세를 불러오고 있습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    await act(async () => resolveDetail(complexDetail()))

    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
  })

  it('잘못된 complexId는 API로 보내지 않고 unrelated query만 남겨 정규화한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&complexId=017&complexId=18')

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
    expect(repository.findComplexDetail).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('단지 상세 정보')).not.toBeInTheDocument()
  })

  it('공고 탭을 처음 열 때 지도와 분리된 공고 cursor 목록을 불러와 유지한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    const announcementTab = screen.getByRole('button', { name: '공고 목록' })
    fireEvent.click(announcementTab)

    const announcementHeading = await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })
    expect(announcementHeading).toBeVisible()
    expect(announcementTab).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('1건')).toBeVisible()
    expect(screen.getByText('공급 75세대')).toBeVisible()
    expect(screen.queryByText('공급 단지')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /입주자 모집 공고 상세 보기/ }))
      .toBeVisible()
    expect(screen.getByRole('region', { name: '공공임대주택 지도' })).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )

    fireEvent.click(screen.getByRole('button', { name: /단지 목록 보기$/ }))
    fireEvent.click(announcementTab)

    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })).toBeVisible()
  })

  it('공고 카드에서 상세 B를 열고 닫으면 URL과 focus가 원래 카드로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))

    const openButton = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)

    const detail = await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })
    await waitFor(() => expect(within(detail).getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
      level: 2,
    })).toHaveFocus())
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))

    await waitFor(() => expect(openButton).toHaveFocus())
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    expect(screen.getByRole('button', { name: '공고 목록' }))
      .toHaveAttribute('aria-expanded', 'true')
  })

  it('공고 상세에서 단지를 열어도 공고 목록을 유지하고 명시적으로 공고로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    const openButton = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)

    const openComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openComplex.focus()
    fireEvent.click(openComplex)
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByTestId('location-search')).toHaveTextContent('complexId=17')
    expect(screen.getByRole('button', { name: '공고 목록' })).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    const back = screen.getByRole('button', { name: '← 공고로 돌아가기' })
    expect(back).toHaveTextContent('←')
    expect(back).not.toHaveTextContent('공고로 돌아가기')
    expect(back.closest('header')).toContainElement(screen.getByRole('button', { name: '단지 상세 닫기' }))
    fireEvent.click(back)
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    const restoredOpenComplex = screen.getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(restoredOpenComplex).toHaveFocus())
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      'announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    await waitFor(() => expect(openButton).toHaveFocus())
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
  })

  it('공고에서 연 단지의 닫기는 부모 공고를 다시 열지 않고 현재 지도와 공고 목록을 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared')
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    const announcementAction = await screen.findByRole('button', { name: '성남 청년 행복주택 입주자 모집 공고 상세 보기' })
    announcementAction.focus()
    fireEvent.click(announcementAction)
    const complexAction = await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' })
    complexAction.focus()
    fireEvent.click(complexAction)
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    expect(screen.getByRole('button', { name: '← 공고로 돌아가기' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expectCurrentSearch({ source: 'shared' }))
    expect(screen.queryByRole('complementary', { name: /상세 정보/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '← 공고로 돌아가기' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '공고 목록' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
    await waitFor(() => expect(announcementAction).toHaveFocus())
  })

  it('단지 상세에서 공고를 열어도 단지 목록을 유지하고 명시적으로 단지로 돌아간다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    const complexButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    complexButton.focus()
    fireEvent.click(complexButton)

    const openAnnouncement = await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    openAnnouncement.focus()
    fireEvent.click(openAnnouncement)
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expect(screen.getByRole('button', { name: /단지 목록 보기$/ }))
      .toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '← 단지로 돌아가기' }))
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByRole('button', { name: /단지 목록 보기$/ }))
      .toHaveAttribute('aria-expanded', 'true')
    const restoredOpenAnnouncement = screen.getByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    await waitFor(() => expect(restoredOpenAnnouncement).toHaveFocus())
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
  })

  it('교차 상세을 두 번 중첩해도 각 부모 상세의 호출 버튼으로 차례로 돌아간다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    fireEvent.click(await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    }))

    const firstOpenComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    firstOpenComplex.focus()
    fireEvent.click(firstOpenComplex)
    const complexPanel = await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    const firstOpenAnnouncement = within(complexPanel).getByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    firstOpenAnnouncement.focus()
    fireEvent.click(firstOpenAnnouncement)

    fireEvent.click(screen.getByRole('button', { name: '← 단지로 돌아가기' }))
    const restoredComplexPanel = await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    const restoredOpenAnnouncement = within(restoredComplexPanel).getByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    })
    await waitFor(() => expect(restoredOpenAnnouncement).toHaveFocus())

    fireEvent.click(screen.getByRole('button', { name: '← 공고로 돌아가기' }))
    const restoredOpenComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(restoredOpenComplex).toHaveFocus())
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
  })

  it('직접 announcementId URL은 목록 요청 전에 상세를 조회하고 닫을 때 무관 query를 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&announcementId=201')

    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expect(repository.findAnnouncementDetail).toHaveBeenCalledWith(
      '201',
      expect.any(AbortSignal),
    )
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
    await waitFor(() => expect(screen.getByRole('searchbox')).toHaveFocus())
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
  })

  it('두 상세 ID가 함께 있으면 정규화하고 상세 API를 호출하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&complexId=17&announcementId=201')

    await waitFor(() => {
      expect(screen.getByTestId('location-search')).toHaveTextContent(
        '?source=shared',
      )
    })
    expect(repository.findComplexDetail).not.toHaveBeenCalled()
    expect(repository.findAnnouncementDetail).not.toHaveBeenCalled()
  })

  it('공고 상세 404와 일시 오류를 구분하고 일시 오류만 재시도한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementDetail
      .mockRejectedValueOnce(new PublicHousingHttpError(404, {
        code: 'ANNOUNCEMENT_NOT_FOUND',
        message: '공고를 찾을 수 없습니다.',
        traceId: 'trace-test',
      }))
    const view = renderExplorer(repository, '/?announcementId=999')

    expect(await screen.findByText('공고를 찾을 수 없습니다.')).toBeVisible()
    expect(screen.queryByRole('button', { name: '다시 시도' }))
      .not.toBeInTheDocument()

    view.unmount()
    repository.findAnnouncementDetail
      .mockRejectedValueOnce(new Error('연결이 잠시 끊겼습니다.'))
      .mockResolvedValueOnce(announcementDetail())
    renderExplorer(repository, '/?announcementId=201')
    expect(await screen.findByText('공고 상세를 불러오지 못했습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
  })

  it('닫힌 뒤 늦게 끝난 공고 상세 응답은 화면을 다시 열지 않는다', async () => {
    let resolveDetail: (detail: AnnouncementDetail) => void = () => undefined
    const repository = createRepository()
    repository.findAnnouncementDetail.mockReturnValueOnce(
      new Promise<AnnouncementDetail>((resolve) => {
        resolveDetail = resolve
      }),
    )
    renderExplorer(repository, '/?announcementId=201')

    expect(await screen.findByText('공고 상세를 불러오고 있습니다.'))
      .toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))
    await act(async () => resolveDetail(announcementDetail()))

    expect(screen.queryByRole('complementary', {
      name: /공고 상세 정보/,
    })).not.toBeInTheDocument()
    expect(screen.getByTestId('location-search')).toBeEmptyDOMElement()
  })

  it('교차 상세의 브라우저 뒤로와 앞으로는 공고와 단지를 URL 순서대로 복원한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    fireEvent.click(await screen.findByRole('button', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 보기',
    }))
    const openComplex = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openComplex.focus()
    fireEvent.click(openComplex)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    const firstRestoredOpenComplex = screen.getByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    await waitFor(() => expect(firstRestoredOpenComplex).toHaveFocus())
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      'announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByTestId('location-search')).toHaveTextContent(
      '?complexId=17',
    )

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    await waitFor(() => expectCurrentSearch({}))
    expect(screen.queryByRole('complementary', { name: /상세 정보/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '공고 목록' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('공고 첫 로딩은 완료된 0건으로 알리지 않는다', () => {
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValueOnce(
      new Promise<AnnouncementPage>(() => undefined),
    )
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))

    const count = screen.getByLabelText('공고 목록 불러오는 중')
    expect(count).toHaveTextContent('불러오는 중')
    expect(count).not.toHaveTextContent('0건')
  })

  it('공고 메뉴는 키보드로 접근 가능한 기본 버튼으로 열림 상태와 제어 대상을 알린다', async () => {
    renderExplorer(createRepository())
    const button = screen.getByRole('button', { name: '공고 목록' })
    expect(button).toHaveAttribute('type', 'button')
    expect(button.tabIndex).toBe(0)
    expect(button).toHaveAttribute('aria-expanded', 'false')
    button.focus()
    fireEvent.click(button)
    expect(button).toHaveFocus()
    expect(button).toHaveAttribute('aria-expanded', 'true')
    const list = screen.getByRole('region', { name: '공고 목록' })
    expect(button).toHaveAttribute('aria-controls', list.id)
    await within(list).findByRole('heading', { name: '성남 청년 행복주택 입주자 모집 공고' })
    fireEvent.click(button)
    expect(button).toHaveFocus()
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('공고 탭에서 현재 지도 영역을 검색해도 공고 목록은 유지한다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalled())
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })

    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))

    await waitFor(() => {
      expect(repository.findMap).toHaveBeenCalledTimes(2)
      expect(repository.findComplexPage).toHaveBeenCalledOnce()
    })
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })).toBeVisible()
  })

  it('공고 탭과 스크롤을 유지하며 지도 마커의 단지 상세를 연다', async () => {
    const repository = createRepository()
    renderSearchedExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    const announcementPanel = await screen.findByRole('region', {
      name: '공고 목록',
    })
    const scroll = announcementPanel.querySelector<HTMLElement>(
      '.housing-results__scroll',
    )
    if (scroll === null) {
      throw new Error('공고 목록 scroll container를 찾을 수 없습니다.')
    }
    scroll.scrollTop = 120
    fireEvent.click(screen.getByRole('button', { name: /단지 목록 보기$/ }))
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    expect(scroll.scrollTop).toBe(120)

    fireEvent.click(within(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))

    expect(screen.getByRole('button', { name: '공고 목록' }))
      .toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
  })
})

describe('PublicHousingExplorer GA4 행동 연결', () => {
  it('직접 접속의 상세 로딩 완료를 한 번 기록하고 지도와 필터 변경에 중복하지 않는다', async () => {
    const repository = createRepository()
    const pending = createDeferred<ComplexDetail>()
    repository.findComplexDetail.mockReturnValue(pending.promise)
    renderExplorer(repository, '/?complexId=17')
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('page_view', {}))
    expect(analyticsCalls('view_complex')).toEqual([])

    await act(() => pending.resolve(complexDetail()))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_complex', {
      complex_id: '17', entry_point: 'direct',
    }))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    applyProvinceFilter('41')
    await act(async () => Promise.resolve())
    expect(analyticsCalls('view_complex')).toHaveLength(1)
    expect(analyticsCalls('page_view')).toHaveLength(1)
  })

  it('상세 오류는 집계하지 않고 재시도 성공 시 최초 열람을 기록한다', async () => {
    const repository = createRepository()
    repository.findComplexDetail.mockRejectedValueOnce(new Error('network')).mockResolvedValue(complexDetail())
    renderExplorer(repository, '/?complexId=17')
    await screen.findByText('단지 상세를 불러오지 못했습니다.')
    expect(analyticsCalls('view_complex')).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_complex', {
      complex_id: '17', entry_point: 'direct',
    }))
  })

  it('지도 마커와 닫은 뒤 최근 단지 선택을 서로 다른 새 열람으로 기록한다', async () => {
    renderSearchedExplorer(createRepository())
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_complex', {
      complex_id: '17', entry_point: 'map',
    }))
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    fireEvent.click(screen.getByRole('button', { name: '최근 본 단지' }))
    const recent = screen.getByRole('region', { name: '최근 본 단지' })
    fireEvent.click(within(recent).getByRole('button', { name: /서울가람 행복주택/ }))
    await waitFor(() => expect(analyticsCalls('view_complex')).toEqual([
      ['view_complex', { complex_id: '17', entry_point: 'map' }],
      ['view_complex', { complex_id: '17', entry_point: 'recent' }],
    ]))
  })

  it('목록에서 연 공고와 연결 단지, 브라우저 복귀를 구분한다', async () => {
    renderExplorer(createRepository())
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    fireEvent.click(await screen.findByRole('button', { name: '성남 청년 행복주택 입주자 모집 공고 상세 보기' }))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_announcement', {
      announcement_id: '201', entry_point: 'list',
    }))
    fireEvent.click(screen.getByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_complex', {
      complex_id: '17', entry_point: 'detail',
    }))
    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_announcement', {
      announcement_id: '201', entry_point: 'history',
    }))
    expect(analyticsCalls('page_view')).toHaveLength(1)
  })

  it.each([
    { type: 'COMPLEX', id: '17', params: { result_type: 'complex', complex_id: '17' }, view: 'view_complex' },
    { type: 'ANNOUNCEMENT', id: '201', params: { result_type: 'announcement', announcement_id: '201' }, view: 'view_announcement' },
    { type: 'REGION', id: '41111', params: { result_type: 'region' }, view: null },
  ] as const)('검색 결과 $type 선택에는 검색 원문이나 지역 좌표를 보내지 않는다', async ({ type, id, params, view }) => {
    const item = searchItem(type, id, '선택할 검색 결과', 37.5, 126.9)
    renderExplorer(createRepository(), '/', searchRepository(type === 'REGION' ? [] : [item], type === 'REGION' ? [item] : []))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '민감한 검색 문구' } })
    fireEvent.click(await screen.findByRole('button', { name: /선택할 검색 결과/ }))
    expect(analyticsCalls('select_search_result')).toEqual([['select_search_result', params]])
    if (view) {
      await waitFor(() => expect(analyticsCalls(view)[0]?.[1]).toEqual({
        [type === 'COMPLEX' ? 'complex_id' : 'announcement_id']: id,
        entry_point: 'search',
      }))
    }
    expect(JSON.stringify(vi.mocked(trackEvent).mock.calls)).not.toContain('민감한 검색 문구')
  })

  it('초기 필터와 뒤로가기는 기록하지 않고 실제 적용과 초기화만 기록한다', async () => {
    renderExplorer(createRepository(), '/?complexRegionCode=11')
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('page_view', {}))
    expect(analyticsCalls('apply_filter')).toEqual([])
    applyProvinceFilter('41')
    applyProvinceFilter('41')
    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await waitFor(() => expectCurrentSearch({ complexRegionCode: '11' }))
    applyProvinceFilter('')
    expect(analyticsCalls('apply_filter')).toEqual([
      ['apply_filter', { filter_target: 'complex', filter_types: 'region', filter_count: 1 }],
      ['apply_filter', { filter_target: 'complex', filter_types: 'none', filter_count: 0 }],
    ])
  })

  it('즉시 적용되는 데스크톱 필터와 적용 버튼이 있는 모바일 필터를 실제 적용 시점에 기록한다', async () => {
    renderExplorer(createRepository())
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('page_view', {}))
    fireEvent.click(screen.getByRole('button', { name: '임대유형 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    expect(analyticsCalls('apply_filter')).toEqual([
      ['apply_filter', { filter_target: 'complex', filter_types: 'rental', filter_count: 1 }],
    ])
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: '모바일 임대유형 필터 열기' }))
    const sheet = screen.getByRole('dialog', { name: '임대유형 필터' })
    fireEvent.click(within(sheet).getByRole('button', { name: '임대유형 필터 초기화' }))
    expect(analyticsCalls('apply_filter')).toHaveLength(1)
    fireEvent.click(within(sheet).getByRole('button', { name: '임대유형 적용' }))
    expect(analyticsCalls('apply_filter')).toEqual([
      ['apply_filter', { filter_target: 'complex', filter_types: 'rental', filter_count: 1 }],
      ['apply_filter', { filter_target: 'complex', filter_types: 'none', filter_count: 0 }],
    ])
  })

  it('공고 필터는 초안 변경을 보내지 않고 적용한 조건의 종류만 기록한다', async () => {
    renderExplorer(createRepository())
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('page_view', {}))
    fireEvent.click(screen.getByRole('button', { name: '공고 목록' }))
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 열기' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'GH' }))
    expect(analyticsCalls('apply_filter')).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(analyticsCalls('apply_filter')).toEqual([
      ['apply_filter', { filter_target: 'announcement', filter_types: 'rental,agency', filter_count: 2 }],
    ])
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(analyticsCalls('apply_filter')).toHaveLength(1)
  })
})

function analyticsCalls(name: string) {
  return vi.mocked(trackEvent).mock.calls.filter(([event]) => event === name)
}

function applyProvinceFilter(provinceCode: string) {
  fireEvent.click(screen.getByRole('button', { name: '지역 필터 열기' }))
  const detailFilter = screen.getByRole('region', { name: '지역 필터' })
  fireEvent.change(within(detailFilter).getByLabelText('시·도'), {
    target: { value: provinceCode },
  })
  fireEvent.click(within(detailFilter).getByRole('button', {
    name: '지역 필터 적용',
  }))
}

function renderExplorer(
  repository: PublicHousingRepository & HousingMapRepository,
  initialEntry = '/',
  integratedSearchRepository?: IntegratedSearchRepository,
  regionRepository = createRegionRepository(),
  mapRepository: HousingMapRepository = repository,
  boundaryRepository?: RegionBoundaryRepository,
) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <PublicHousingExplorer
        boundaryRepository={boundaryRepository}
        mapRepository={mapRepository}
        regionRepository={regionRepository}
        repository={repository}
        searchRepository={integratedSearchRepository}
      />
      <LocationSearch />
    </MemoryRouter>,
  )
}

function renderSearchedExplorer(...args: Parameters<typeof renderExplorer>) {
  const url = new URL(args[1] ?? '/', 'http://localhost')
  if (!url.searchParams.has('boundaryRegionCode')) url.searchParams.set('boundaryRegionCode', '11')
  args[1] = `${url.pathname}${url.search}${url.hash}`
  return renderExplorer(...args)
}

function createRegionRepository(): PublicHousingRegionRepository {
  return {
    search: vi.fn().mockImplementation((keyword: string) => Promise.resolve(
      TEST_REGIONS.filter(({ provinceName }) => provinceName === keyword),
    )),
  }
}

function searchRepository(
  results: readonly SearchResultItem[],
  regions: readonly SearchResultItem[],
): IntegratedSearchRepository {
  const response: IntegratedSearchResponse = {
    announcements: results.filter(({ type }) => type === 'ANNOUNCEMENT'),
    complexes: results.filter(({ type }) => type === 'COMPLEX'),
    failures: [],
    hasNext: false,
    page: 0,
    query: '서울',
    regions,
    size: 8,
    totalCount: null,
  }
  return { search: vi.fn().mockResolvedValue(response) }
}

function searchItem(
  type: SearchResultItem['type'],
  id: string,
  title: string,
  latitude: number | null,
  longitude: number | null,
): SearchResultItem {
  return {
    applicationStatus: null,
    id,
    latitude,
    longitude,
    publishedAt: null,
    regionCode: type === 'REGION' ? id : null,
    subtitle: '서울특별시',
    title,
    type,
  }
}

function LocationSearch() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <div>
      <output data-testid="location-search">{location.search}</output>
      <output data-testid="location-hash">{location.hash}</output>
      <output data-testid="location-key">{location.key}</output>
      <output data-testid="location-state">
        {JSON.stringify(location.state)}
      </output>
      <button type="button" onClick={() => navigate(-1)}>브라우저 뒤로</button>
      <button type="button" onClick={() => navigate(1)}>브라우저 앞으로</button>
      <button
        type="button"
        onClick={() => navigate({
          hash: location.hash,
          pathname: location.pathname,
          search: location.search,
        }, {
          replace: true,
          state: { source: 'shared-state' },
        })}
      >
        공유 상태 설정
      </button>
      <button type="button" onClick={() => navigate('/?complexId=18')}>
        단지 18 직접 열기
      </button>
    </div>
  )
}

function FakeNaverMap({
  aggregateMarkers = [],
  regionBoundary,
  cameraTarget,
  cameraRequestId,
  dataBusy = false,
  markers = [],
  onAggregateMarkerSelect,
  onMarkerHighlight,
  onMarkerSelect,
  onTransitionInterrupt,
  onViewportChange,
  transitioning = false,
}: NaverMapProps) {
  const [, setRevision] = useState(0)
  const currentCameraRef = useRef({ ...INITIAL_CENTER, zoom: 14 })
  const currentBoundsRef = useRef(INITIAL_BOUNDS)
  const appliedRequestRef = useRef<number | undefined>(undefined)
  if (cameraTarget && (appliedRequestRef.current !== cameraRequestId
    || appliedRequestRef.current === undefined)) {
    appliedRequestRef.current = cameraRequestId
    currentCameraRef.current = {
      latitude: cameraTarget.latitude,
      longitude: cameraTarget.longitude,
      zoom: cameraTarget.zoom ?? currentCameraRef.current.zoom,
    }
    currentBoundsRef.current = cameraTarget.bounds ?? boundsAt(cameraTarget.latitude, cameraTarget.longitude)
  }
  const currentCamera = currentCameraRef.current
  function reportViewport(bounds: MapBounds, center: typeof INITIAL_CENTER, zoom: number) {
    currentBoundsRef.current = bounds
    currentCameraRef.current = { ...center, zoom }
    setRevision((current) => current + 1)
    onViewportChange?.({ bounds, center, zoom })
  }

  return (
    <section
      className="map-surface"
      aria-label="공공임대주택 지도"
      aria-busy={dataBusy}
      data-transitioning={String(transitioning)}
    >
      <output>카메라 {currentCamera.latitude},{currentCamera.longitude}</output>
      <output data-testid="map-boundary">{regionBoundary?.regionCode ?? 'none'}</output>
      <output data-testid="map-camera-padding">{JSON.stringify(cameraTarget?.boundsPadding)}</output>
      <output data-testid="map-camera-offset">{JSON.stringify(cameraTarget?.screenOffset)}</output>
      <output data-testid="map-camera-max-zoom">{cameraTarget?.boundsMaxZoom}</output>
      <output data-testid="map-camera-bounds">{JSON.stringify(currentBoundsRef.current)}</output>
      <output data-testid="map-camera-zoom">{currentCamera.zoom}</output>
      <output data-testid="map-camera-request">{cameraRequestId}</output>
      <button
        type="button"
        onClick={() => onTransitionInterrupt?.()}
      >
        지도 전환 중단
      </button>
      <button type="button" onClick={() => reportViewport(INITIAL_BOUNDS, INITIAL_CENTER, 14)}>
        초기 영역 알림
      </button>
      <button type="button" onClick={() => reportViewport(NEXT_BOUNDS, NEXT_CENTER, 14)}>
        다음 영역 알림
      </button>
      <button type="button" onClick={() => reportViewport(JITTERED_INITIAL_BOUNDS, INITIAL_CENTER, 14)}>
        미세 이동 영역 알림
      </button>
      <button type="button" onClick={() => reportViewport(INITIAL_BOUNDS, PRECISION_CENTER, 14.256)}>
        정밀 영역 알림
      </button>
      <button type="button" onClick={() => reportViewport(
        currentBoundsRef.current, currentCameraRef.current, currentCameraRef.current.zoom,
      )}>
        현재 카메라 idle
      </button>
      <button type="button" onClick={() => reportViewport({
        southWestLat: 36, southWestLng: 125, northEastLat: 38, northEastLng: 128,
      }, { latitude: 37, longitude: 126.5 }, 14)}>
        넓은 영역 알림
      </button>
      <button type="button" onClick={() => reportViewport(KOREA_TEST_BOUNDS, { latitude: 36, longitude: 128 }, 7)}>
        전국 영역 알림
      </button>
      {markers.map((marker) => (
        <button
          key={`complex-${marker.id}`}
          className="housing-map-marker"
          type="button"
          data-complex-id={marker.id}
          data-highlighted={marker.highlighted || undefined}
          data-map-complex-marker="true"
          data-selected={marker.selected || undefined}
          data-agency-label={marker.agencyLabel}
          data-rental-type-label={marker.rentalTypeLabel}
          data-deposit-label={marker.deposit
            ? `${marker.deposit.digits}${marker.deposit.unit}~` : '공고문 확인'}
          data-monthly-rent-label={marker.monthlyRent
            ? `${marker.monthlyRent.digits}${marker.monthlyRent.unit}~` : '공고문 확인'}
          onMouseEnter={() => onMarkerHighlight?.(marker.id)}
          onMouseLeave={() => onMarkerHighlight?.(null)}
          onFocus={() => onMarkerHighlight?.(marker.id)}
          onBlur={() => onMarkerHighlight?.(null)}
          onClick={() => {
            onMarkerSelect?.(marker.id)
            setRevision((current) => current + 1)
          }}
        >
          {marker.name} 지도 마커 선택
        </button>
      ))}
      {aggregateMarkers.map((marker) => (
        <button
          key={`aggregate-${marker.groupKey}`}
          type="button"
          onClick={() => onAggregateMarkerSelect?.(marker)}
        >
          {marker.groupLabel} {marker.uniqueComplexCount}곳 지역 마커 선택
        </button>
      ))}
    </section>
  )
}

function boundsAt(latitude: number, longitude: number): MapBounds {
  return {
    southWestLat: latitude - 0.06,
    southWestLng: longitude - 0.1,
    northEastLat: latitude + 0.06,
    northEastLng: longitude + 0.1,
  }
}

function currentSearch() {
  return Object.fromEntries(new URLSearchParams(screen.getByTestId('location-search').textContent ?? ''))
}

function expectCurrentSearch(expected: Record<string, string>) {
  const search = screen.getByTestId('location-search').textContent ?? ''
  expect(Object.fromEntries(new URLSearchParams(search))).toEqual(expected)
}

function createRepository(): PublicHousingRepository & HousingMapRepository & {
  findAnnouncementDetail: ReturnType<typeof vi.fn>
  findAnnouncementPage: ReturnType<typeof vi.fn>
  findComplexDetail: ReturnType<typeof vi.fn>
  findComplexPage: ReturnType<typeof vi.fn>
  findMap: ReturnType<typeof vi.fn>
} {
  return {
    findAnnouncementDetail: vi.fn().mockResolvedValue(announcementDetail()),
    findAnnouncementPage: vi.fn().mockResolvedValue(announcementPage()),
    findComplexDetail: vi.fn().mockResolvedValue(complexDetail()),
    findComplexPage: vi.fn().mockResolvedValue(complexPage()),
    findMap: vi.fn().mockResolvedValue(individualMapResult()),
  }
}

function createMapRepository(result: HousingMapAggregateResult
  | HousingMapIndividualResult): HousingMapRepository & {
    findMap: ReturnType<typeof vi.fn>
  } {
  return { findMap: vi.fn().mockResolvedValue(result) }
}

function aggregateMapResult(): HousingMapAggregateResult {
  return {
    nodes: [{
      expansionZoom: 7,
      groupKey: 'SEOUL',
      groupLabel: '서울',
      latitude: 37.5665,
      longitude: 126.978,
      nextStage: 2,
      type: 'AGGREGATE',
      uniqueComplexCount: 0,
    }],
    policyVersion: '2026-09-03',
    regionDatasetVersion: '2026-09-03',
    representation: 'AGGREGATE',
    resolvedStage: 1,
  }
}

function individualMapResult(
  complexes: readonly MapComplex[] = [mapComplex()],
): HousingMapIndividualResult {
  return {
    nodes: complexes.map((complex) => ({ ...complex, type: 'INDIVIDUAL' })),
    policyVersion: '2026-09-03',
    regionDatasetVersion: '2026-09-03',
    representation: 'INDIVIDUAL',
    resolvedStage: 4,
  }
}

function announcementDetail(): AnnouncementDetail {
  return {
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: '201',
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    attachments: [],
    competition: null,
    correctionOrCancellationReason: null,
    dDay: 2,
    documentLinkUrl: 'https://example.com/announcements/201',
    publicationType: 'ORIGINAL',
    publishedAt: '2026-08-20',
    raw: {} as RawAnnouncementDetail,
    receptionPlaces: [],
    recruitmentType: 'NEW',
    regionNames: ['경기도 성남시'],
    rentalType: 'HAPPY_HOUSING',
    schedules: [],
    supplyComplexCount: 1,
    supplyHouseholdCount: 75,
    supplyRows: [{
      complex: {
        address: '서울특별시 중구 세종대로 110',
        complexId: '17',
        name: '서울가람 행복주택',
        overviewImageUrl: null,
        totalHouseholdCount: 100,
      },
      housingType: {
        exclusiveArea: 36.12,
        floorPlan3dImageUrl: null,
        floorPlanImageUrl: null,
        housingTypeId: '301',
        name: '36A',
        supplyArea: 48.2,
      },
      occupancyExpectedYearMonth: '2026-12',
      sourceComplexName: '서울가람 행복주택',
      sourceHousingTypeName: '36A',
      supplyRowId: '401',
      supplyType: 'NEW',
      targets: [],
      totalSupplyHouseholdCount: 75,
    }],
    targets: ['청년'],
    title: '성남 청년 행복주택 입주자 모집 공고',
    viewCount: 614,
    winnerAnnouncementAt: '2026-09-10',
  }
}

function announcementPage(): AnnouncementPage {
  const item = announcementListItem()
  const raw: RawAnnouncementPage = {
    hasNext: false,
    items: [item.raw],
    nextCursor: null,
  }
  return { hasNext: false, items: [item], nextCursor: null, raw }
}

function announcementListItem(): AnnouncementListItem {
  const raw: RawAnnouncementListItem = {
    actualCompetitionRate: null,
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: 201,
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    dDay: 2,
    predictedCompetitionRate: null,
    publicationType: 'ORIGINAL',
    publishedAt: '2026-08-20',
    recruitmentType: 'NEW',
    regionNames: ['경기도 성남시'],
    rentalType: 'HAPPY_HOUSING',
    supplyComplexCount: 2,
    supplyHouseholdCount: 75,
    thumbnailImageUrl: null,
    title: '성남 청년 행복주택 입주자 모집 공고',
    viewCount: 614,
  }
  return { ...raw, announcementId: '201', raw }
}

function complexDetail(): ComplexDetail {
  const raw = {} as RawComplexDetail
  return {
    address: {
      latitude: 37.5,
      longitude: 126.9,
      regionName: '서울특별시 중구',
      roadAddress: '서울특별시 중구 세종대로 110',
    },
    agency: { code: 'LH', name: '한국토지주택공사' },
    buildingType: 'APARTMENT',
    completionDate: '2020-01-01',
    complexId: '17',
    corridorType: 'STAIR',
    currentAnnouncements: [{
      actualCompetitionRate: null,
      announcementId: '201',
      applicationEndAt: '2026-08-30',
      applicationStartAt: '2026-08-28',
      applicationStatus: 'APPLYING',
      dDay: 2,
      publicationType: 'ORIGINAL',
      targets: ['청년'],
      title: '성남 청년 행복주택 입주자 모집 공고',
    }],
    hasElevator: true,
    heatingType: 'INDIVIDUAL',
    housingTypes: [],
    images: [],
    moveOutCountLastYear: 7,
    name: '서울가람 행복주택',
    overviewImageUrl: null,
    raw,
    rentalType: 'HAPPY_HOUSING',
    totalHouseholdCount: 100,
    totalParkingCount: 80,
    depositMin: 50_000_000,
    depositMax: 70_000_000,
    monthlyRentMin: 200_000,
    monthlyRentMax: 300_000,
  }
}

function complexPage(): ComplexPage {
  const rawItem = rawComplexListItem()
  const raw: RawComplexPage = {
    hasNext: false,
    items: [rawItem],
    nextCursor: null,
  }
  return {
    hasNext: false,
    items: [complexListItem(rawItem)],
    nextCursor: null,
    raw,
  }
}

function complexPageWithNext(): ComplexPage {
  const page = complexPage()
  return {
    ...page,
    hasNext: true,
    nextCursor: 'cursor-2',
    raw: {
      ...page.raw,
      hasNext: true,
      nextCursor: 'cursor-2',
    },
  }
}

function complexPageFor(complexId: number, name: string): ComplexPage {
  const rawItem = {
    ...rawComplexListItem(),
    complexId,
    name,
  }
  const raw: RawComplexPage = {
    hasNext: false,
    items: [rawItem],
    nextCursor: null,
  }
  return {
    hasNext: false,
    items: [complexListItem(rawItem)],
    nextCursor: null,
    raw,
  }
}

function complexListItem(raw: RawComplexListItem): ComplexListItem {
  return {
    agency: raw.agency,
    complexId: String(raw.complexId),
    depositMax: raw.depositMax,
    depositMin: raw.depositMin,
    exclusiveAreaMax: raw.exclusiveAreaMax,
    exclusiveAreaMin: raw.exclusiveAreaMin,
    monthlyRentMax: raw.monthlyRentMax,
    monthlyRentMin: raw.monthlyRentMin,
    name: raw.name,
    raw,
    regionName: raw.regionName,
    rentalType: raw.rentalType,
    representativeAnnouncement: {
      announcementId: '117',
      applicationEndAt: '2026-08-30',
      applicationStatus: 'APPLYING',
      dDay: 2,
      publicationType: 'ORIGINAL',
    },
    thumbnailImageUrl: null,
  }
}

function rawComplexListItem(): RawComplexListItem {
  return {
    agency: { code: 'LH', name: '한국토지주택공사' },
    complexId: 17,
    depositMax: 70_000_000,
    depositMin: 50_000_000,
    exclusiveAreaMax: 44.87,
    exclusiveAreaMin: 36.12,
    monthlyRentMax: 300_000,
    monthlyRentMin: 200_000,
    name: '서울가람 행복주택',
    regionName: '서울특별시 중구',
    rentalType: 'HAPPY_HOUSING',
    representativeAnnouncement: {
      announcementId: 117,
      applicationEndAt: '2026-08-30',
      applicationStatus: 'APPLYING',
      dDay: 2,
      publicationType: 'ORIGINAL',
    },
    thumbnailImageUrl: null,
  }
}

function mapComplex(): MapComplex {
  const raw: RawMapComplex = {
    agency: { code: 'LH', name: '한국토지주택공사' },
    complexId: 17,
    depositMax: 70_000_000,
    depositMin: 50_000_000,
    exclusiveAreaMax: 44.87,
    exclusiveAreaMin: 36.12,
    latitude: 37.56,
    longitude: 126.98,
    monthlyRentMax: 300_000,
    monthlyRentMin: 200_000,
    name: '서울가람 행복주택',
    rentalType: 'HAPPY_HOUSING',
  }
  return {
    ...raw,
    complexId: '17',
    raw,
  }
}

function mapComplexFor(complexId: number, name: string): MapComplex {
  const current = mapComplex()
  const raw = { ...current.raw, complexId, name }
  return {
    ...current,
    complexId: String(complexId),
    name,
    raw,
  }
}

function createDeferred<T>() {
  let resolvePromise: (value: T) => void = () => {
    throw new Error('Promise resolve 함수가 준비되지 않았습니다.')
  }
  let rejectPromise: (reason: unknown) => void = () => {
    throw new Error('Promise reject 함수가 준비되지 않았습니다.')
  }
  const promise = new Promise<T>((resolve, reject) => {
    resolvePromise = resolve
    rejectPromise = reject
  })
  return { promise, resolve: resolvePromise, reject: rejectPromise }
}
