import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { useLayoutEffect, useState } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { trackEvent } from '../analytics/googleAnalytics.ts'
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
  ComplexSearchSnapshot,
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
  ],
  defaultBoundaryRepository: { find: async (regionCode: string) => ({ regionCode, version: 'test', polygons: [] }) },
}))
vi.mock('./regions/regionBoundaryCatalog.ts', () => ({
  findRegionBoundaryMetadata: (code: string) => boundaryMetadata.find((entry) => entry.regionCode === code) ?? null,
  findRegionBoundaryName: (code: string) => boundaryMetadata.find((entry) => entry.regionCode === code)?.name ?? null,
  findRegionSearchBounds: (code: string) => boundaryMetadata.find((entry) => entry.regionCode === code)?.bounds ?? null,
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
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  localStorage.clear()
})

describe('PublicHousingExplorer', () => {
  it.each([true, false])('같은 확정 영역으로 되돌아오면 취소된 검색을 재시작한다 (이전 결과: %s)', async (hasPreviousResults) => {
    vi.useFakeTimers()
    const repository = createRepository()
    const interrupted = createDeferred<ComplexSearchSnapshot>()
    const latest = searchSnapshot(complexPageFor(18, '복귀 영역 단지'), [mapComplexFor(18, '복귀 영역 단지')])
    if (hasPreviousResults) repository.findComplexSearch.mockResolvedValueOnce(searchSnapshot())
    repository.findComplexSearch.mockReturnValueOnce(interrupted.promise).mockResolvedValueOnce(latest)
    renderExplorer(repository, hasPreviousResults ? '/' : '/?searchMode=area&searchBounds=37.4,126.8,37.55,127')
    if (hasPreviousResults) {
      fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
      await act(async () => Promise.resolve())
      fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
      await act(async () => vi.advanceTimersByTimeAsync(300))
    } else {
      fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
      await act(async () => Promise.resolve())
    }
    const requestCount = hasPreviousResults ? 2 : 1
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(requestCount)
    const signal = repository.findComplexSearch.mock.calls[requestCount - 1][2] as AbortSignal
    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))
    expect(signal.aborted).toBe(true)
    await act(async () => vi.advanceTimersByTimeAsync(100))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(299))
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(requestCount)
    await act(async () => vi.advanceTimersByTimeAsync(1))
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(requestCount + 1)
    expect(repository.findComplexSearch).toHaveBeenLastCalledWith({ mode: 'area', bounds: NEXT_BOUNDS }, 20, expect.any(AbortSignal), {})
    expect(screen.getByRole('article', { name: '복귀 영역 단지' })).toBeVisible()
    await act(async () => interrupted.resolve(searchSnapshot()))
    expect(screen.getByRole('article', { name: '복귀 영역 단지' })).toBeVisible()
    expect(screen.queryByRole('article', { name: '서울가람 행복주택' })).not.toBeInTheDocument()
  })

  it('예약 이동 중 단지 선택은 새 영역 예약만 취소하고 중단된 확정 검색을 재개한다', async () => {
    vi.useFakeTimers()
    const repository = createRepository()
    const interrupted = createDeferred<ComplexSearchSnapshot>()
    repository.findComplexSearch.mockResolvedValueOnce(searchSnapshot()).mockReturnValueOnce(interrupted.promise)
      .mockResolvedValueOnce(searchSnapshot(complexPageFor(18, '확정 영역 단지'), [mapComplexFor(18, '확정 영역 단지')]))
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await act(async () => Promise.resolve())
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(300))
    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))
    fireEvent.click(screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await act(async () => vi.advanceTimersByTimeAsync(350))
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(3)
    expect(repository.findComplexSearch).toHaveBeenLastCalledWith({ mode: 'area', bounds: NEXT_BOUNDS }, 20, expect.any(AbortSignal), {})
    expect(screen.getByRole('article', { name: '확정 영역 단지' })).toBeVisible()
    expect(screen.getByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })).toBeVisible()
    expect(new URLSearchParams(screen.getByTestId('location-search').textContent ?? '').get('searchBounds')).toBe('37.4,126.8,37.55,127')
  })

  it('최초 검색 실패는 완료된 0곳으로 표시하지 않으며 같은 범위를 재시도한다', async () => {
    const repository = createRepository()
    repository.findComplexSearch.mockRejectedValueOnce(new Error('첫 검색 실패')).mockResolvedValueOnce(searchSnapshot())
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByText('첫 검색 실패')
    expect(screen.queryByText('이 지역에서 확인되는 단지가 없습니다.')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('조회된 단지 0곳')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(2)
    expect(repository.findComplexSearch).toHaveBeenLastCalledWith({ mode: 'area', bounds: INITIAL_BOUNDS }, 20, expect.any(AbortSignal), {})
  })

  it.each([0, 1])('지역 결과 %s곳의 좌표가 없으면 지역 범위로 이동하고 사유를 안내한다', async (count) => {
    const repository = createRepository()
    const page = count === 0 ? { ...complexPage(), items: [] } : complexPage()
    repository.findComplexSearch.mockResolvedValue({ ...searchSnapshot(page, []), bounds: null, locatedCount: 0 })
    renderExplorer(repository, '/?complexRegionCode=41110')
    await waitFor(() => expect(screen.getByTestId('map-camera-bounds')).toHaveTextContent(JSON.stringify(boundaryMetadata[0].bounds)))
    expect(screen.getByText(count === 0 ? '조건에 맞는 단지가 없습니다.' : '검색된 단지의 좌표가 없어 지도에 핀을 표시할 수 없습니다.')).toBeVisible()
    expect(screen.queryByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })).not.toBeInTheDocument()
    if (count === 1) expect(screen.getByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
  })

  it('다른 지역을 검색한 뒤 뒤로가면 지역·경계·카메라·누적 목록과 스크롤을 복원한다', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const repository = createRepository()
    repository.findComplexSearch.mockResolvedValueOnce({ ...searchSnapshot(complexPageWithNext()), complexIds: ['17', '18'], totalCount: 2 })
      .mockResolvedValueOnce(searchSnapshot(complexPageFor(19, '다른 지역 단지'), [mapComplexFor(19, '다른 지역 단지')]))
    repository.findComplexPage.mockResolvedValueOnce(complexPageFor(18, '둘째 단지'))
    renderExplorer(repository, '/?searchMode=region&boundaryRegionCode=41110&complexRegionCode=41110&announcementRegionCode=41110&mapLat=37.3&mapLng=127&mapZoom=12', searchRepository([], [searchItem('REGION', '41111', '장안구', null, null)]))
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: /^단지 더 보기/ }))
    const second = await screen.findByRole('article', { name: '둘째 단지' })
    const scroll = second.closest<HTMLElement>('.housing-results__scroll')!
    scroll.scrollTop = 240
    fireEvent.scroll(scroll)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '장안' } })
    fireEvent.click(await screen.findByRole('button', { name: /장안구/ }))
    fireEvent.click(screen.getByRole('button', { name: '검색결과 닫기' }))
    await screen.findByRole('article', { name: '다른 지역 단지' })
    await waitFor(() => expect(screen.getByText(/^카메라 /).textContent).toBe('카메라 37.56,127'))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
    await screen.findByRole('article', { name: '둘째 단지' })
    await waitFor(() => expect(scroll.scrollTop).toBe(240))
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110')
    expect(screen.getByText(/^카메라 /).textContent).toBe('카메라 37.3,127')
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('12')
    expect(new URLSearchParams(screen.getByTestId('location-search').textContent ?? '').get('complexRegionCode')).toBe('41110')
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(2)
  })

  it('초기 지도 영역의 핀·목록·총수를 하나의 검색 응답으로 표시한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    expect(repository.findComplexSearch).toHaveBeenCalledExactlyOnceWith(
      { mode: 'area', bounds: INITIAL_BOUNDS }, 20, expect.any(AbortSignal), {},
    )
    expect(repository.findMap).not.toHaveBeenCalled()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })).toBeVisible()
    expect(screen.getByTestId('location-search')).toHaveTextContent('searchMode=area')
  })

  it('지도 탐색에서 직접 이동하면 300ms 후 마지막 영역을 검색한다', async () => {
    vi.useFakeTimers()
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await act(async () => Promise.resolve())
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(150))
    fireEvent.click(screen.getByRole('button', { name: '전국 영역 알림' }))
    await act(async () => vi.advanceTimersByTimeAsync(299))
    expect(repository.findComplexSearch).toHaveBeenCalledOnce()
    await act(async () => vi.advanceTimersByTimeAsync(1))
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(2)
    expect(repository.findComplexSearch).toHaveBeenLastCalledWith(
      { mode: 'area', bounds: KOREA_TEST_BOUNDS }, 20, expect.any(AbortSignal), {},
    )
  })

  it('지역 검색은 지도를 움직여도 지역 전체 결과와 경계를 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?searchMode=region&boundaryRegionCode=41110&complexRegionCode=41110&announcementRegionCode=41110')
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await act(async () => new Promise((resolve) => setTimeout(resolve, 350)))
    expect(repository.findComplexSearch).toHaveBeenCalledOnce()
    expect(repository.findComplexSearch).toHaveBeenCalledWith({ mode: 'region', regionCode: '41110' }, 20, expect.any(AbortSignal), { regionCode: '41110' })
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110')
    expect(screen.getByTestId('location-search')).toHaveTextContent('searchMode=region')
  })

  it('영역 다시 검색은 지역 조건·경계를 해제하고 비지역 필터와 카메라를 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?complexRegionCode=41110&complexRentalTypes=NATIONAL_RENTAL')
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    const camera = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '이 영역 다시 검색' }))
    await waitFor(() => expect(repository.findComplexSearch).toHaveBeenLastCalledWith(
      { mode: 'area', bounds: NEXT_BOUNDS }, 20, expect.any(AbortSignal), { rentalTypes: ['NATIONAL_RENTAL'] },
    ))
    const query = new URLSearchParams(screen.getByTestId('location-search').textContent ?? '')
    expect(query.get('searchMode')).toBe('area')
    expect(query.get('searchBounds')).toBe('37.4,126.8,37.55,127')
    for (const key of ['boundaryRegionCode', 'complexRegionCode', 'announcementRegionCode']) expect(query.has(key)).toBe(false)
    expect(screen.getByTestId('map-boundary')).toHaveTextContent('none')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(camera ?? '')
  })

  it('지역 선택은 검색 필터·경계·URL을 같은 지역으로 바꾸고 전체 좌표에 화면을 맞춘다', async () => {
    const repository = createRepository()
    const bounds = { southWestLat: 37.2, southWestLng: 126.9, northEastLat: 37.4, northEastLng: 127.1 }
    repository.findComplexSearch.mockResolvedValue({ ...searchSnapshot(), bounds })
    renderExplorer(repository, '/?complexRegionCode=11', searchRepository([], [searchItem('REGION', '41110', '수원시', null, null)]))
    fireEvent.change(screen.getByRole('searchbox', { name: '지역, 단지, 공고 검색' }), { target: { value: '수원' } })
    fireEvent.click(await screen.findByRole('button', { name: /수원시/ }))
    await waitFor(() => expect(repository.findComplexSearch).toHaveBeenLastCalledWith({ mode: 'region', regionCode: '41110' }, 20, expect.any(AbortSignal), { regionCode: '41110' }))
    const query = new URLSearchParams(screen.getByTestId('location-search').textContent ?? '')
    for (const key of ['boundaryRegionCode', 'complexRegionCode', 'announcementRegionCode']) expect(query.get(key)).toBe('41110')
    expect(query.get('searchMode')).toBe('region')
    expect(screen.getByTestId('map-camera-bounds')).toHaveTextContent(JSON.stringify(bounds))
  })

  it('단지 선택과 프로그램 지도 이동은 기존 목록·줌·검색 영역을 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    const scroll = card.closest<HTMLElement>('.housing-results__scroll')!
    scroll.scrollTop = 210
    fireEvent.click(within(card).getByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await act(async () => new Promise((resolve) => setTimeout(resolve, 350)))
    expect(repository.findComplexSearch).toHaveBeenCalledOnce()
    expect(scroll.scrollTop).toBe(210)
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14')
    expect(new URLSearchParams(screen.getByTestId('location-search').textContent ?? '').get('searchBounds')).toBe('37.5,126.9,37.62,127.1')
  })

  it('새 검색 중 기존 핀·목록을 유지하고 하나의 응답으로 함께 교체한다', async () => {
    const repository = createRepository()
    const next = createDeferred<ComplexSearchSnapshot>()
    repository.findComplexSearch.mockResolvedValueOnce(searchSnapshot()).mockReturnValueOnce(next.promise)
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await waitFor(() => expect(repository.findComplexSearch).toHaveBeenCalledTimes(2))
    expect(card).toBeVisible()
    expect(screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })).toBeVisible()
    await act(async () => next.resolve(searchSnapshot(complexPageFor(18, '새 단지'), [mapComplexFor(18, '새 단지')])))
    expect(screen.queryByRole('article', { name: '서울가람 행복주택' })).not.toBeInTheDocument()
    expect(screen.getByRole('article', { name: '새 단지' })).toBeVisible()
    expect(screen.getByRole('button', { name: '새 단지 지도 마커 선택' })).toBeVisible()
  })

  it('취소된 이전 검색 응답은 새 목록을 덮어쓰지 않는다', async () => {
    const repository = createRepository()
    const previous = createDeferred<ComplexSearchSnapshot>()
    repository.findComplexSearch.mockReturnValueOnce(previous.promise).mockResolvedValueOnce(searchSnapshot(complexPageFor(18, '새 단지'), [mapComplexFor(18, '새 단지')]))
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const signal = repository.findComplexSearch.mock.calls[0][2] as AbortSignal
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    expect(signal.aborted).toBe(true)
    await screen.findByRole('article', { name: '새 단지' })
    await act(async () => previous.resolve(searchSnapshot()))
    expect(screen.queryByRole('article', { name: '서울가람 행복주택' })).not.toBeInTheDocument()
    expect(screen.getByRole('article', { name: '새 단지' })).toBeVisible()
  })

  it('검색 실패는 기존 결과를 보존하고 실패한 범위를 재시도한다', async () => {
    const repository = createRepository()
    repository.findComplexSearch.mockResolvedValueOnce(searchSnapshot()).mockRejectedValueOnce(new Error('일시 검색 오류')).mockResolvedValueOnce(searchSnapshot(complexPageFor(18, '새 단지'), [mapComplexFor(18, '새 단지')]))
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    await screen.findByText('일시 검색 오류')
    expect(screen.getByRole('article', { name: '서울가람 행복주택' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await screen.findByRole('article', { name: '새 단지' })
    expect(repository.findComplexSearch).toHaveBeenLastCalledWith({ mode: 'area', bounds: NEXT_BOUNDS }, 20, expect.any(AbortSignal), {})
  })

  it('카메라와 검색 범위 URL을 분리하고 같은 idle은 검색과 이력을 반복하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared#results')
    fireEvent.click(screen.getByRole('button', { name: '공유 상태 설정' }))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    const key = screen.getByTestId('location-key').textContent
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    await act(async () => Promise.resolve())
    const query = new URLSearchParams(screen.getByTestId('location-search').textContent ?? '')
    expect(query.get('mapLat')).toBe('37.56000')
    expect(query.get('mapLng')).toBe('127.00000')
    expect(query.get('mapZoom')).toBe('14.00')
    expect(query.get('source')).toBe('shared')
    expect(query.get('searchBounds')).toBe('37.5,126.9,37.62,127.1')
    expect(screen.getByTestId('location-hash')).toHaveTextContent('#results')
    expect(screen.getByTestId('location-state')).toHaveTextContent('shared-state')
    expect(screen.getByTestId('location-key').textContent).toBe(key)
    expect(repository.findComplexSearch).toHaveBeenCalledOnce()
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
      '임대유형 필터 열기',
      '모집상태 필터 열기',
      '가격 필터 열기',
      '전용면적 필터 열기',
      '준공년도 필터 열기',
      '상세 필터 열기',
    ])
    expect(within(screen.getByRole('complementary', {
      name: '공공임대주택 검색 결과',
    })).queryByRole('toolbar', {
      name: '단지 검색 필터',
    })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

    expect(complexFilter).toBeVisible()
    expect(within(screen.getByRole('tabpanel', {
      name: '공고 목록',
    })).getByRole('region', {
      name: '공고 검색 필터',
    })).toBeVisible()
  })

  it('공고 조회 건수와 필터를 함께 표시하고 필터를 펼쳐도 건수를 유지한다', () => {
    renderExplorer(createRepository())
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

    const panel = screen.getByRole('tabpanel', { name: '공고 목록' })
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
    renderExplorer(
      repository,
      '/?complexRegionCode=11&complexRentalTypes=NATIONAL_RENTAL',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '가격 필터 열기' }))
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최솟값',
    }), { target: { value: '100000000' } })
    fireEvent.change(screen.getByRole('slider', {
      name: '임대보증금 최댓값',
    }), { target: { value: '200000000' } })

    await waitFor(() => expect(repository.findComplexSearch).toHaveBeenLastCalledWith(
      { mode: 'region', regionCode: '11' }, 20, expect.any(AbortSignal),
      { maxDeposit: 200_000_000, minDeposit: 100_000_000, regionCode: '11', rentalTypes: ['NATIONAL_RENTAL'] },
    ))
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexRegionCode')).toBe('11')
    expect(search.getAll('complexRentalTypes')).toEqual(['NATIONAL_RENTAL'])
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexMaxDeposit')).toBe('200000000')
  })

  it('공유 URL의 시군구와 복수 조건을 폼에서 다시 적용해도 보존한다', async () => {
    const repository = createRepository()
    renderExplorer(
      repository,
      '/?complexRegionCode=41135'
        + '&complexRentalTypes=NATIONAL_RENTAL'
        + '&complexRentalTypes=HAPPY_HOUSING'
        + '&complexAgencyCodes=LH'
        + '&complexAgencyCodes=GH',
    )
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.click(screen.getByRole('button', { name: '상세 필터 열기' }))
    const detailFilter = screen.getByRole('region', {
      name: '상세 필터',
    })
    expect(within(detailFilter).getByLabelText('시·도')).toHaveValue('41')
    await waitFor(() => {
      expect(within(detailFilter).getByLabelText('시·군·구'))
        .toHaveValue('41135')
    })
    expect(within(detailFilter).getByRole('checkbox', { name: 'LH' }))
      .toBeChecked()
    expect(within(detailFilter).getByRole('checkbox', { name: 'GH' }))
      .toBeChecked()
    fireEvent.click(within(detailFilter).getByRole('button', {
      name: '상세 필터 적용',
    }))

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

  it('단지 범위 손잡이는 서로 교차하지 않도록 상대 값에 맞춘다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
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

    await waitFor(() => expect(repository.findComplexSearch).toHaveBeenLastCalledWith(
      { mode: 'area', bounds: INITIAL_BOUNDS }, 20, expect.any(AbortSignal),
      { maxDeposit: 100_000_000, minDeposit: 100_000_000 },
    ))
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('complexMinDeposit')).toBe('100000000')
    expect(search.get('complexMaxDeposit')).toBe('100000000')
  })

  it('공고 고유 필터는 독립적으로 적용하고 지역 조건은 단지와 공유한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })
    const complexRequestCount = repository.findComplexPage.mock.calls.length
    const mapRequestCount = repository.findMap.mock.calls.length
    const scroll = screen.getByRole('tabpanel', { name: '공고 목록' })
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
        scope: { mode: 'region', regionCode: '41' },
        rentalTypes: ['HAPPY_HOUSING'],
      },
    )
    expect(repository.findComplexPage).toHaveBeenCalledTimes(complexRequestCount)
    expect(repository.findMap).toHaveBeenCalledTimes(mapRequestCount)
    const search = new URLSearchParams(
      screen.getByTestId('location-search').textContent ?? '',
    )
    expect(search.get('announcementRegionCode')).toBe('41')
    expect(search.get('complexRegionCode')).toBe('41')
    await waitFor(() => expect(scroll.scrollTop).toBe(0))

    scroll.scrollTop = 80
    fireEvent.click(screen.getByRole('button', { name: '공고 필터 적용' }))
    expect(scroll.scrollTop).toBe(80)
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(screen.getByRole('button', { name: '상세 필터 열기' }))
    expect(within(screen.getByRole('region', {
      name: '상세 필터',
    })).getByLabelText('시·도')).toHaveValue('41')
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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

  it('통합 검색을 시작하고 종료해도 두 목록의 컨테이너와 읽던 위치를 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/', searchRepository([], []))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('article', { name: '서울가람 행복주택' })
    const complexScroll = screen.getByRole('tabpanel', { name: '단지 목록' })
      .querySelector<HTMLElement>('.housing-results__scroll')
    if (!complexScroll) throw new Error('단지 목록 스크롤 영역 없음')
    complexScroll.scrollTop = 240
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    await screen.findByRole('heading', { name: '성남 청년 행복주택 입주자 모집 공고' })
    const announcementScroll = screen.getByRole('tabpanel', { name: '공고 목록' })
      .querySelector<HTMLElement>('.housing-results__scroll')
    if (!announcementScroll) throw new Error('공고 목록 스크롤 영역 없음')
    announcementScroll.scrollTop = 360

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    expect(complexScroll.isConnected).toBe(true)
    expect(announcementScroll.isConnected).toBe(true)
    expect(screen.queryByRole('tab', { name: '공고 목록' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })

    expect(announcementScroll.scrollTop).toBe(360)
    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    expect(complexScroll.scrollTop).toBe(240)
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
  })

  it('공고의 최초 로딩과 빈 결과 사이에도 같은 본문 스크롤 영역을 유지한다', async () => {
    const pending = createDeferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValue(pending.promise)
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    const panel = screen.getByRole('tabpanel', { name: '공고 목록' })
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
    renderExplorer(repository, '/', searchRepository([complex], []))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })

    expect(await screen.findByRole('heading', { name: '단지' })).toBeVisible()
    expect(screen.queryByRole('tab', { name: '단지 목록' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이 지역에서 검색' })).not.toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })

    expect(screen.getByRole('tab', { name: '단지 목록' })).toBeVisible()
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
  })

  it('검색한 공고를 선택하면 연결 단지 좌표로 이동한다', async () => {
    const repository = createRepository()
    const announcement = searchItem('ANNOUNCEMENT', '201', '서울 행복주택 공고', 37.5, 126.9)
    renderExplorer(repository, '/', searchRepository([announcement], []))

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울 행복' } })
    fireEvent.click(await screen.findByRole('button', { name: /서울 행복주택 공고/ }))

    expect(await screen.findByText('카메라 37.5,126.9')).toBeVisible()
  })

  it('경계 실패는 명시적으로 알리고 재시도해도 카메라를 다시 이동하지 않는다', async () => {
    const boundaryRepository = { find: vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ regionCode: '41110', version: 'test', polygons: [] }) }
    renderExplorer(createRepository(), '/?boundaryRegionCode=41110', undefined, undefined, undefined, boundaryRepository)
    expect(await screen.findByText('지역 경계를 불러오지 못했습니다.')).toBeVisible()
    await waitFor(() => expect(screen.getByText(/^카메라 /).textContent).toBe('카메라 37.3,127'))
    fireEvent.click(screen.getByRole('button', { name: '다음 영역 알림' }))
    const request = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '경계 다시 시도' }))
    await waitFor(() => expect(screen.getByTestId('map-boundary')).toHaveTextContent('41110'))
    expect(screen.getByTestId('map-camera-request').textContent).toBe(request)
    expect(screen.getByText(/^카메라 /).textContent).toBe('카메라 37.475,126.9')
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

  it('지도에 실제로 겹친 도구막대 크기를 fit 여백에 반영한다', async () => {
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
    await waitFor(() => expect(screen.getByTestId('map-camera-padding')).not.toBeEmptyDOMElement())
    expect(JSON.parse(screen.getByTestId('map-camera-padding').textContent ?? '{}')).toEqual({ top: 78, right: 24, bottom: 24, left: 24 })
  })

  it('검색결과를 닫아도 열린 상세와 현재 지도 위치 및 기존 목록 탭을 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/', searchRepository([], []))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const marker = await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    fireEvent.click(marker)
    const detail = await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '서울' } })
    fireEvent.click(screen.getByRole('button', { name: '검색결과 닫기' }))

    expect(detail).toBeVisible()
    expectCurrentSearch({ complexId: '17' })
    expect(screen.getByRole('tab', { name: '공고 목록' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
  })

  it('더 보기 실패는 첫 페이지 대신 실패한 cursor를 다시 요청한다', async () => {
    const repository = createRepository()
    repository.findComplexSearch.mockResolvedValue(searchSnapshot(complexPageWithNext()))
    repository.findComplexPage
      .mockRejectedValueOnce(new Error('다음 페이지 실패'))
      .mockResolvedValueOnce(complexPageFor(18, '서울마루 국민임대'))
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: /^단지 더 보기/ }))
    await screen.findByText('다음 페이지 실패')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('heading', {
      name: '서울마루 국민임대',
    })).toBeVisible()
    expect(repository.findComplexPage).toHaveBeenNthCalledWith(
      1,
      INITIAL_BOUNDS,
      'cursor-2',
      20,
      expect.any(AbortSignal),
      { scope: { mode: 'area', bounds: INITIAL_BOUNDS } },
    )
    expect(repository.findComplexPage).toHaveBeenNthCalledWith(
      2,
      INITIAL_BOUNDS,
      'cursor-2',
      20,
      expect.any(AbortSignal),
      { scope: { mode: 'area', bounds: INITIAL_BOUNDS } },
    )
  })

  it.each([
    ['바로 선택할 때', false],
    ['카드의 상세 응답을 기다리다 선택할 때', true],
  ])('지도 마커를 %s 줌과 검색을 유지한 채 필요한 만큼만 표시하도록 요청한다', async (_label, pendingCardSelection) => {
    const repository = createRepository()
    const detailResponse = createDeferred<ComplexDetail>()
    repository.findComplexDetail.mockReturnValue(detailResponse.promise)
    const mapRepository = createMapRepository(individualMapResult())
    renderExplorer(repository, '/', undefined, undefined, mapRepository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    if (pendingCardSelection) {
      fireEvent.click(within(card).getByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
      await screen.findByText('단지 상세를 불러오고 있습니다.')
    }

    fireEvent.click(screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await act(async () => detailResponse.resolve(complexDetail()))

    expect(screen.getByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })).toBeVisible()
    expect(screen.getByTestId('map-camera-reveal')).not.toBeEmptyDOMElement()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14')
    expect(card).toHaveAttribute('aria-current', 'true')
    expect(mapRepository.findMap).not.toHaveBeenCalled()
    expect(repository.findComplexSearch).toHaveBeenCalledOnce()
    expect(repository.findComplexPage).not.toHaveBeenCalled()
  })

  it('지도 마커 선택과 목록 카드 선택 상태를 같은 ID로 동기화한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(
      screen.getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }),
    )

    expect(
      screen.getByRole('article', { name: '서울가람 행복주택' }),
    ).toHaveAttribute('aria-current', 'true')
    expect(repository.findComplexDetail).toHaveBeenCalledWith(
      '17',
      expect.any(AbortSignal),
    )
  })

  it('목록의 기관 코드와 이미지를 카드에 전달하고 상세 요청 없이 표시한다', async () => {
    const repository = createRepository()
    const page = complexPage()
    repository.findComplexSearch.mockResolvedValueOnce(searchSnapshot({
      ...page,
      items: page.items.map((item) => ({
        ...item,
        thumbnailImageUrl: 'https://example.com/complex.jpg',
      })),
    }))
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const card = await screen.findByRole('article', { name: '서울가람 행복주택' })
    expect(within(card).getByRole('img')).toHaveAttribute(
      'src', 'https://example.com/complex.jpg',
    )
    expect(within(card).getByText('LH')).toBeInTheDocument()
    expect(repository.findComplexDetail).not.toHaveBeenCalled()
  })

  it('페이지 크기 대신 조회된 전체 단지 수를 표시하고 더보기 진행 수를 갱신한다', async () => {
    const repository = createRepository()
    const items = Array.from({ length: 45 }, (_, index) => complexPageFor(index + 1, `단지 ${index + 1}`).items[0])
    repository.findComplexSearch.mockResolvedValue({ ...searchSnapshot({ ...complexPageWithNext(), items: items.slice(0, 20) }), totalCount: 45, complexIds: items.map((item) => item.complexId) })
    repository.findComplexPage
      .mockResolvedValueOnce({ ...complexPageWithNext(), items: items.slice(20, 40), nextCursor: 'cursor-3' })
      .mockResolvedValueOnce({ ...complexPage(), items: items.slice(40) })
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const results = within(screen.getByRole('tabpanel', { name: '단지 목록' }))
    expect(await results.findByLabelText('조회된 단지 45곳')).toHaveTextContent('45곳')
    const more = results.getByRole('button', { name: /^단지 더 보기/ })
    expect(more).toHaveTextContent('(20 | 45)')
    fireEvent.click(more)
    await waitFor(() => {
      expect(more).toHaveAccessibleName(/^단지 더 보기/)
      expect(more).toHaveTextContent('(40 | 45)')
      expect(more).toBeEnabled()
    })
    expect(results.getByLabelText('조회된 단지 45곳')).toHaveTextContent('45곳')
    fireEvent.click(more)
    await results.findByRole('article', { name: '단지 45' })
    expect(results.queryByRole('button', { name: /^단지 더 보기/ })).not.toBeInTheDocument()
    expect(results.getByLabelText('조회된 단지 45곳')).toHaveTextContent('45곳')
  })

  it('카드와 marker hover 및 focus를 연결하되 marker 선택은 목록을 스크롤하지 않는다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

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

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    expect(screen.getByRole('tab', { name: '공고 목록' }))
      .toHaveAttribute('aria-selected', 'true')
    fireEvent.click(marker)

    expect(scroll.scrollTop).toBe(0)
    expect(updateScroll).not.toHaveBeenCalled()
    expect(screen.getByRole('tab', { name: '공고 목록' })).toHaveAttribute('aria-selected', 'true')
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
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
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
    repository.findComplexSearch
      .mockResolvedValueOnce(searchSnapshot())
      .mockResolvedValueOnce(searchSnapshot(complexPageFor(18, '서울마루 국민임대'), [mapComplexFor(18, '서울마루 국민임대')]))
      .mockResolvedValueOnce(searchSnapshot())
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

    await screen.findByRole('heading', { name: '서울마루 국민임대' })
    expect(screen.getByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })).toBe(openedDetail)
    expect(openedDetail).toBeVisible()
    expect(repository.findComplexDetail).toHaveBeenCalledOnce()
    expect(repository.findComplexSearch).toHaveBeenCalledTimes(2)
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
    const view = renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    const toggle = screen.getByRole('button', { name: '최근 본 단지 1곳' })
    expect(toggle.parentElement).toHaveTextContent('조회 결과')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const resultsScroll = document.querySelector('.housing-results__scroll')
    if (!resultsScroll) throw new Error('단지 목록 스크롤 영역 없음')
    resultsScroll.scrollTop = 300
    fireEvent.click(toggle)
    expect(resultsScroll.scrollTop).toBe(0)
    const recent = screen.getByRole('region', { name: '최근 본 단지' })
    expect(within(recent).getByLabelText('공급기관 LH, 임대유형 행복주택')).toBeVisible()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByLabelText('조회된 단지 1곳')).toBeVisible()
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(within(recent).queryByRole('button', { name: /서울가람 행복주택/ })).not.toBeInTheDocument()
    view.unmount()

    renderExplorer(repository)
    const restoredToggle = screen.getByRole('button', { name: '최근 본 단지 1곳' })
    expect(restoredToggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(restoredToggle)
    const restoredRecent = screen.getByRole('region', { name: '최근 본 단지' })
    expect(within(restoredRecent).getByLabelText('공급기관 LH, 임대유형 행복주택')).toBeVisible()
    fireEvent.click(within(restoredRecent).getByRole('button', { name: /서울가람 행복주택/ }))
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    expect(repository.findComplexDetail).toHaveBeenCalledTimes(2)
  })

  it('단지 상세 응답 대기 중 사용자가 이동한 지도는 응답 도착 후에도 유지한다', async () => {
    const repository = createRepository()
    const pending = createDeferred<ComplexDetail>()
    repository.findComplexDetail.mockReturnValue(pending.promise)
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
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

  it('상세 닫기는 현재 URL만 정리하고 지도 위치와 줌 및 무관 상태를 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared&mapLat=37.58123&mapLng=126.99123&mapZoom=15.50#results')
    fireEvent.click(screen.getByRole('button', { name: '공유 상태 설정' }))
    fireEvent.click(screen.getByRole('button', { name: '현재 카메라 idle' }))
    const openButton = await screen.findByRole('button', { name: '서울가람 행복주택 단지 상세 보기' })
    const listLocationKey = screen.getByTestId('location-key').textContent
    fireEvent.click(openButton)
    await screen.findByRole('complementary', { name: '서울가람 행복주택 단지 상세 정보' })
    expectCurrentSearch({ complexId: '17', source: 'shared' })
    expect(screen.getByTestId('location-key').textContent).not.toBe(listLocationKey)

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expectCurrentSearch({ source: 'shared' }))
    expect(screen.getByTestId('location-key').textContent).not.toBe(listLocationKey)
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
    expect(screen.getByTestId('location-hash')).toHaveTextContent('#results')
    expect(screen.getByTestId('location-state')).toHaveTextContent('shared-state')
    expect(screen.getByTestId('location-state')).not.toHaveTextContent('toadzipDetail')
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    expectCurrentSearch({ source: 'shared' })
    expect(screen.queryByRole('complementary', { name: /단지 상세 정보/ })).not.toBeInTheDocument()
  })

  it.each(['단지 상세 닫기', '브라우저 뒤로'])('%s 후 선택을 해제하고 URL과 focus를 복원한다', async (closeAction) => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    expect(screen.getByText('카메라 37.56,127')).toBeVisible()
    expectCurrentSearch({})

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
    expectCurrentSearch({ complexId: '17' })
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
    expectCurrentSearch({})
    expect(screen.getByText('카메라 37.5,126.9')).toBeVisible()
    expect(marker).not.toHaveAttribute('data-selected')
    expect(screen.getByRole('article', { name: '서울가람 행복주택' }))
      .not.toHaveAttribute('aria-current', 'true')
  })

  it('단지 상세를 연 카드가 숨겨지면 닫을 때 현재 결과 탭으로 focus가 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))

    const openButton = await screen.findByRole('button', {
      name: '서울가람 행복주택 단지 상세 보기',
    })
    openButton.focus()
    fireEvent.click(openButton)
    await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })

    const announcementTab = screen.getByRole('tab', { name: '공고 목록' })
    fireEvent.click(announcementTab)
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))

    await waitFor(() => expect(announcementTab).toHaveFocus())
    expect(announcementTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).not.toBeInTheDocument()
  })

  it('단지 카드의 대표 공고를 닫으면 원래 단지 탭과 action으로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    const openAnnouncement = screen.getByRole('button', {
      name: '대표 공고 상세 보기',
    })
    openAnnouncement.focus()
    fireEvent.click(openAnnouncement)
    expect(await screen.findByRole('complementary', {
      name: '성남 청년 행복주택 입주자 모집 공고 상세 정보',
    })).toBeVisible()
    expectCurrentSearch({ announcementId: '117' })

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))

    await waitFor(() => expect(openAnnouncement).toHaveFocus())
    expect(screen.getByRole('tab', { name: '단지 목록' }))
      .toHaveAttribute('aria-selected', 'true')
    expectCurrentSearch({})
    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
  })

  it('내부에서 연 상세은 뒤로 갔다가 앞으로 온 뒤에도 닫기로 원래 목록에 복귀한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
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
      expectCurrentSearch({})
    })
    fireEvent.click(screen.getByRole('button', { name: '브라우저 앞으로' }))
    await waitFor(() => {
      expectCurrentSearch({ complexId: '17' })
    })

    fireEvent.click(await screen.findByRole('button', {
      name: '단지 상세 닫기',
    }))
    await waitFor(() => {
      expectCurrentSearch({})
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
      expectCurrentSearch({})
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
    expectCurrentSearch({})
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
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })

    const announcementTab = screen.getByRole('tab', { name: '공고 목록' })
    fireEvent.click(announcementTab)

    const announcementHeading = await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })
    expect(announcementHeading).toBeVisible()
    expect(announcementTab).toHaveAttribute('aria-selected', 'true')
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
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'], regionCode: null, scope: { mode: 'area', bounds: INITIAL_BOUNDS } },
    )

    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(announcementTab)

    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })).toBeVisible()
  })

  it('공고 카드에서 상세 B를 열고 닫으면 URL과 focus가 원래 카드로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

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
      'announcementId=201',
    )

    fireEvent.click(screen.getByRole('button', { name: '공고 상세 닫기' }))

    await waitFor(() => expect(openButton).toHaveFocus())
    expectCurrentSearch({})
    expect(screen.getByRole('tab', { name: '공고 목록' }))
      .toHaveAttribute('aria-selected', 'true')
  })

  it('공고 상세에서 단지를 열어도 공고 목록을 유지하고 명시적으로 공고로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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
    expect(screen.getByRole('tab', { name: '공고 목록' })).toHaveAttribute('aria-selected', 'true')

    await waitFor(() => expect(screen.getByText(/^카메라 /).textContent).toBe('카메라 37.5,126.9'))
    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    // 사용자 이동의 debounce와 URL 반영이 끝난 뒤 상세 간 이동을 시작한다.
    await waitFor(() => {
      const query = new URLSearchParams(screen.getByTestId('location-search').textContent ?? '')
      expect(query.get('mapLat')).toBe('37.56661')
      expect(query.get('mapZoom')).toBe('14.26')
    })
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
    expectCurrentSearch({})
    await waitFor(() => {
      expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
      expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
      expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
    })
  })

  it('공고에서 연 단지의 닫기는 부모 공고를 다시 열지 않고 현재 지도와 공고 목록을 유지한다', async () => {
    const repository = createRepository()
    renderExplorer(repository, '/?source=shared')
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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
    expect(screen.getByRole('tab', { name: '공고 목록' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('카메라 37.5666103,126.9783882')).toBeVisible()
    expect(screen.getByTestId('map-camera-zoom')).toHaveTextContent('14.256')
    expect(screen.getByTestId('map-camera-request')).toHaveTextContent(cameraRequest ?? '')
    await waitFor(() => expect(announcementAction).toHaveFocus())
  })

  it('단지 상세에서 공고를 열어도 단지 목록을 유지하고 명시적으로 단지로 돌아간다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
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
    expect(screen.getByRole('tab', { name: '단지 목록' }))
      .toHaveAttribute('aria-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: '정밀 영역 알림' }))
    const cameraRequest = screen.getByTestId('map-camera-request').textContent
    fireEvent.click(screen.getByRole('button', { name: '← 단지로 돌아가기' }))
    expect(await screen.findByRole('complementary', {
      name: '서울가람 행복주택 단지 상세 정보',
    })).toBeVisible()
    expect(screen.getByRole('tab', { name: '단지 목록' }))
      .toHaveAttribute('aria-selected', 'true')
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
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const complexTab = screen.getByRole('tab', { name: '단지 목록' })
    expect(complexTab).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(complexTab).toHaveFocus())
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
    expectCurrentSearch({})
  })

  it('교차 상세의 브라우저 뒤로와 앞으로는 공고와 단지를 URL 순서대로 복원한다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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
      'complexId=17',
    )

    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    await waitFor(() => expectCurrentSearch({}))
    expect(screen.queryByRole('complementary', { name: /상세 정보/ })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '공고 목록' })).toHaveAttribute('aria-selected', 'true')
  })

  it('공고 첫 로딩은 완료된 0건으로 알리지 않는다', () => {
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValueOnce(
      new Promise<AnnouncementPage>(() => undefined),
    )
    renderExplorer(repository)

    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))

    const count = screen.getByLabelText('공고 목록 불러오는 중')
    expect(count).toHaveTextContent('불러오는 중')
    expect(count).not.toHaveTextContent('0건')
  })

  it('결과 탭은 좌우 방향키로 전환하고 활성 탭만 tab stop으로 둔다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    const complexTab = screen.getByRole('tab', { name: '단지 목록' })
    const announcementTab = screen.getByRole('tab', { name: '공고 목록' })

    complexTab.focus()
    fireEvent.keyDown(complexTab, { key: 'ArrowRight' })

    expect(announcementTab).toHaveFocus()
    expect(announcementTab).toHaveAttribute('aria-selected', 'true')
    expect(announcementTab).toHaveAttribute('tabindex', '0')
    expect(complexTab).toHaveAttribute('tabindex', '-1')
    await screen.findByRole('heading', {
      name: '성남 청년 행복주택 입주자 모집 공고',
    })

    fireEvent.keyDown(announcementTab, { key: 'ArrowLeft' })
    expect(complexTab).toHaveFocus()
    expect(complexTab).toHaveAttribute('aria-selected', 'true')
  })

  it('공고 탭과 스크롤을 유지하며 지도 마커의 단지 상세를 연다', async () => {
    const repository = createRepository()
    renderExplorer(repository)
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    await screen.findByRole('heading', { name: '서울가람 행복주택' })
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    const announcementPanel = await screen.findByRole('tabpanel', {
      name: '공고 목록',
    })
    const scroll = announcementPanel.querySelector<HTMLElement>(
      '.housing-results__scroll',
    )
    if (scroll === null) {
      throw new Error('공고 목록 scroll container를 찾을 수 없습니다.')
    }
    scroll.scrollTop = 120
    fireEvent.click(screen.getByRole('tab', { name: '단지 목록' }))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
    expect(scroll.scrollTop).toBe(120)

    fireEvent.click(within(
      screen.getByRole('region', { name: '공공임대주택 지도' }),
    ).getByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))

    expect(screen.getByRole('tab', { name: '공고 목록' }))
      .toHaveAttribute('aria-selected', 'true')
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
    renderExplorer(createRepository())
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(await screen.findByRole('button', { name: '서울가람 행복주택 지도 마커 선택' }))
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('view_complex', {
      complex_id: '17', entry_point: 'map',
    }))
    fireEvent.click(screen.getByRole('button', { name: '단지 상세 닫기' }))
    fireEvent.click(screen.getByRole('button', { name: '최근 본 단지 1곳' }))
    const recent = screen.getByRole('region', { name: '최근 본 단지' })
    fireEvent.click(within(recent).getByRole('button', { name: /서울가람 행복주택/ }))
    await waitFor(() => expect(analyticsCalls('view_complex')).toEqual([
      ['view_complex', { complex_id: '17', entry_point: 'map' }],
      ['view_complex', { complex_id: '17', entry_point: 'recent' }],
    ]))
  })

  it('목록에서 연 공고와 연결 단지, 브라우저 복귀를 구분한다', async () => {
    renderExplorer(createRepository())
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
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
    fireEvent.click(screen.getByRole('button', { name: '전체 단지 필터 열기, 1개 적용' }))
    const sheet = screen.getByRole('dialog', { name: '단지 필터' })
    fireEvent.click(within(sheet).getByRole('button', { name: '전체 필터 초기화' }))
    expect(analyticsCalls('apply_filter')).toHaveLength(1)
    fireEvent.click(within(sheet).getByRole('button', { name: '단지 보기' }))
    expect(analyticsCalls('apply_filter')).toEqual([
      ['apply_filter', { filter_target: 'complex', filter_types: 'rental', filter_count: 1 }],
      ['apply_filter', { filter_target: 'complex', filter_types: 'none', filter_count: 0 }],
    ])
  })

  it('공고 필터는 초안 변경을 보내지 않고 적용한 조건의 종류만 기록한다', async () => {
    renderExplorer(createRepository())
    await waitFor(() => expect(trackEvent).toHaveBeenCalledWith('page_view', {}))
    fireEvent.click(screen.getByRole('button', { name: '초기 영역 알림' }))
    fireEvent.click(screen.getByRole('tab', { name: '공고 목록' }))
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
  fireEvent.click(screen.getByRole('button', { name: '상세 필터 열기' }))
  const detailFilter = screen.getByRole('region', { name: '상세 필터' })
  fireEvent.change(within(detailFilter).getByLabelText('시·도'), {
    target: { value: provinceCode },
  })
  fireEvent.click(within(detailFilter).getByRole('button', {
    name: '상세 필터 적용',
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
  const [currentCamera, setCurrentCamera] = useState({ ...INITIAL_CENTER, zoom: 14 })
  const [currentBounds, setCurrentBounds] = useState(INITIAL_BOUNDS)
  useLayoutEffect(() => {
    if (!cameraTarget) return
    setCurrentCamera((current) => ({ latitude: cameraTarget.latitude, longitude: cameraTarget.longitude,
      zoom: cameraTarget.zoom ?? current.zoom }))
    setCurrentBounds(cameraTarget.bounds ?? boundsAt(cameraTarget.latitude, cameraTarget.longitude))
  }, [cameraRequestId, cameraTarget])
  function reportViewport(bounds: MapBounds, center: typeof INITIAL_CENTER, zoom: number, cause: 'user' | 'programmatic' = 'user') {
    setCurrentBounds(bounds)
    setCurrentCamera({ ...center, zoom })
    onViewportChange?.({ bounds, center, zoom }, { cause })
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
      <output data-testid="map-camera-reveal">{JSON.stringify(cameraTarget?.revealPadding)}</output>
      <output data-testid="map-camera-padding">{JSON.stringify(cameraTarget?.boundsPadding)}</output>
      <output data-testid="map-camera-bounds">{JSON.stringify(currentBounds)}</output>
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
        currentBounds, currentCamera, currentCamera.zoom, 'programmatic',
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

function expectCurrentSearch(expected: Record<string, string>) {
  const search = screen.getByTestId('location-search').textContent ?? ''
  const query = new URLSearchParams(search)
  for (const key of ['mapLat', 'mapLng', 'mapZoom', 'searchMode', 'searchBounds', 'resultTab']) query.delete(key)
  if (!('boundaryRegionCode' in expected)) query.delete('boundaryRegionCode')
  if (!('announcementRegionCode' in expected)) query.delete('announcementRegionCode')
  if ('boundaryRegionCode' in expected && !('complexRegionCode' in expected)) query.delete('complexRegionCode')
  expect(Object.fromEntries(query)).toEqual(expected)
}

function createRepository(): PublicHousingRepository & HousingMapRepository & {
  findAnnouncementDetail: ReturnType<typeof vi.fn>
  findAnnouncementPage: ReturnType<typeof vi.fn>
  findComplexDetail: ReturnType<typeof vi.fn>
  findComplexPage: ReturnType<typeof vi.fn>
  findMap: ReturnType<typeof vi.fn>
  findComplexSearch: ReturnType<typeof vi.fn>
} {
  return {
    findAnnouncementDetail: vi.fn().mockResolvedValue(announcementDetail()),
    findAnnouncementPage: vi.fn().mockResolvedValue(announcementPage()),
    findComplexDetail: vi.fn().mockResolvedValue(complexDetail()),
    findComplexPage: vi.fn().mockResolvedValue(complexPage()),
    findMap: vi.fn().mockResolvedValue(individualMapResult()),
    findComplexSearch: vi.fn().mockImplementation((scope: { mode: string; regionCode?: string }) => Promise.resolve({ ...searchSnapshot(), bounds: scope.mode === 'region' ? boundaryMetadata.find((item) => item.regionCode === scope.regionCode)?.bounds ?? INITIAL_BOUNDS : INITIAL_BOUNDS })),
  }
}

function searchSnapshot(page: ComplexPage = complexPage(), items: readonly MapComplex[] = [mapComplex()]): ComplexSearchSnapshot {
  return { page, mapItems: items, complexIds: page.items.map((item) => item.complexId),
    totalCount: page.items.length, locatedCount: items.length, bounds: INITIAL_BOUNDS }
}

function createMapRepository(result: HousingMapAggregateResult
  | HousingMapIndividualResult): HousingMapRepository & {
    findMap: ReturnType<typeof vi.fn>
  } {
  return { findMap: vi.fn().mockResolvedValue(result) }
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
