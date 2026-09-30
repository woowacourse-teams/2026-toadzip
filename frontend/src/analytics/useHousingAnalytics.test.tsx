import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { parseDetailLocation } from '../public-housing/navigation/detailLocation.ts'
import { setAnalyticsPageActive, trackEvent } from './googleAnalytics.ts'
import { trackAppliedFilters, useHousingAnalytics } from './useHousingAnalytics.ts'

vi.mock('./googleAnalytics.ts', () => ({
  setAnalyticsPageActive: vi.fn(),
  trackEvent: vi.fn(() => true),
}))

beforeEach(() => {
  vi.mocked(trackEvent).mockReset().mockReturnValue(true)
  vi.mocked(setAnalyticsPageActive).mockClear()
})

interface HarnessProps {
  readyComplexId?: string | null
  readyAnnouncementId?: string | null
}

function Harness({ readyComplexId = null, readyAnnouncementId = null }: HarnessProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const prepare = useHousingAnalytics({
    detailLocation: parseDetailLocation(new URLSearchParams(location.search)),
    readyComplexId,
    readyAnnouncementId,
  })
  return <>
    <button onClick={() => {
      prepare('complex', '17', 'list')
      navigate('/?complexId=17')
    }}>단지 열기</button>
    <button onClick={() => {
      prepare('announcement', '201', 'detail')
      navigate('/?announcementId=201')
    }}>연결 공고</button>
    <button onClick={() => navigate('/')}>닫기</button>
    <button onClick={() => navigate('/?complexId=17&complexRegionCode=11')}>필터 변경</button>
    <button onClick={() => navigate(-1)}>뒤로</button>
    <button onClick={() => navigate(1)}>앞으로</button>
  </>
}

function renderHarness(initialEntry: string, props: HarnessProps = {}) {
  const view = (state: HarnessProps) => <StrictMode>
    <MemoryRouter initialEntries={[initialEntry]}><Harness {...state} /></MemoryRouter>
  </StrictMode>
  const result = render(view(props))
  return { ...result, rerenderHarness: (state: HarnessProps) => result.rerender(view(state)) }
}

async function flushEffects() {
  await act(async () => { await Promise.resolve() })
}

function detailEvents() {
  return vi.mocked(trackEvent).mock.calls.filter(([name]) => name.startsWith('view_'))
}

describe('useHousingAnalytics', () => {
  it('StrictMode 직접 접속에서도 페이지와 준비된 상세를 한 번씩 전송한다', async () => {
    const { unmount } = renderHarness('/?complexId=17', { readyComplexId: '17' })
    await flushEffects()

    expect(trackEvent).toHaveBeenNthCalledWith(1, 'page_view', {})
    expect(detailEvents()).toEqual([
      ['view_complex', { complex_id: '17', entry_point: 'direct' }],
    ])
    expect(vi.mocked(setAnalyticsPageActive).mock.calls).toEqual([[true]])
    unmount()
    expect(vi.mocked(setAnalyticsPageActive).mock.calls).toEqual([[true], [false]])
  })

  it('현재 URL과 일치하는 상세가 준비되기 전에는 열람을 기록하지 않는다', async () => {
    const { rerenderHarness } = renderHarness('/?complexId=17')
    await flushEffects()
    expect(detailEvents()).toEqual([])

    rerenderHarness({ readyComplexId: '18' })
    await flushEffects()
    expect(detailEvents()).toEqual([])

    rerenderHarness({ readyComplexId: '17' })
    await flushEffects()
    rerenderHarness({ readyComplexId: '17' })
    fireEvent.click(screen.getByRole('button', { name: '필터 변경' }))
    await flushEffects()
    expect(detailEvents()).toHaveLength(1)
    expect(vi.mocked(trackEvent).mock.calls.filter(([name]) => name === 'page_view')).toHaveLength(1)
  })

  it('상세를 닫고 다시 열면 새 열람이며 이미 열린 상세 재선택은 중복 집계하지 않는다', async () => {
    renderHarness('/', { readyComplexId: '17' })
    await flushEffects()
    fireEvent.click(screen.getByRole('button', { name: '단지 열기' }))
    await flushEffects()
    fireEvent.click(screen.getByRole('button', { name: '단지 열기' }))
    await flushEffects()
    expect(detailEvents()).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    await flushEffects()
    fireEvent.click(screen.getByRole('button', { name: '단지 열기' }))
    await flushEffects()
    expect(detailEvents()).toEqual([
      ['view_complex', { complex_id: '17', entry_point: 'list' }],
      ['view_complex', { complex_id: '17', entry_point: 'list' }],
    ])
  })

  it('연결 상세와 브라우저 뒤로 앞으로의 진입 경로를 구분한다', async () => {
    renderHarness('/?complexId=17', { readyComplexId: '17', readyAnnouncementId: '201' })
    await flushEffects()
    fireEvent.click(screen.getByRole('button', { name: '연결 공고' }))
    await flushEffects()
    fireEvent.click(screen.getByRole('button', { name: '뒤로' }))
    await flushEffects()
    fireEvent.click(screen.getByRole('button', { name: '앞으로' }))
    await flushEffects()
    expect(detailEvents()).toEqual([
      ['view_complex', { complex_id: '17', entry_point: 'direct' }],
      ['view_announcement', { announcement_id: '201', entry_point: 'detail' }],
      ['view_complex', { complex_id: '17', entry_point: 'history' }],
      ['view_announcement', { announcement_id: '201', entry_point: 'history' }],
    ])
  })

  it.each(['/?complexId=invalid', '/?complexId=17&announcementId=201'])(
    '잘못된 상세 URL %s에서는 열람을 전송하지 않는다', async (url) => {
      renderHarness(url, { readyComplexId: '17', readyAnnouncementId: '201' })
      await flushEffects()
      expect(detailEvents()).toEqual([])
    },
  )

  it('전송이 거절된 상세는 성공으로 표시하지 않아 이후 준비 상태에서 다시 시도할 수 있다', async () => {
    const { rerenderHarness } = renderHarness('/?complexId=17')
    await flushEffects()
    vi.mocked(trackEvent).mockReturnValueOnce(false)
    rerenderHarness({ readyComplexId: '17' })
    await flushEffects()
    rerenderHarness({ readyComplexId: null })
    await flushEffects()
    rerenderHarness({ readyComplexId: '17' })
    await waitFor(() => expect(detailEvents()).toHaveLength(2))
  })
})

describe('trackAppliedFilters', () => {
  it('조건 변경에는 선택한 종류와 개수만 전송하고 실제 값은 보내지 않는다', () => {
    trackAppliedFilters('complex', new URLSearchParams(), new URLSearchParams({
      complexRegionCode: '11',
      complexRentalTypes: 'HAPPY_HOUSING',
      complexMinDeposit: '123456',
      complexMaxDeposit: '987654',
      complexMinMonthlyRent: '0',
      complexMinExclusiveArea: '20.5',
      complexBuiltYearFrom: '2020',
      lat: '37.56661',
    }))
    expect(trackEvent).toHaveBeenCalledExactlyOnceWith('apply_filter', {
      filter_target: 'complex',
      filter_types: 'region,rental,deposit,rent,area,built_year',
      filter_count: 6,
    })
  })

  it('배열 순서, 중복, 의미 없는 값과 다른 URL 상태의 변경은 무시한다', () => {
    trackAppliedFilters('complex', new URLSearchParams(
      'complexRentalTypes=HAPPY_HOUSING&complexRentalTypes=NATIONAL_RENTAL',
    ), new URLSearchParams(
      'complexRentalTypes=NATIONAL_RENTAL&complexRentalTypes=HAPPY_HOUSING&complexRentalTypes=HAPPY_HOUSING&complexMinDeposit=invalid&complexId=17',
    ))
    trackAppliedFilters('announcement', new URLSearchParams(), new URLSearchParams(
      'announcementApplicationStatuses=CLOSED',
    ))
    expect(trackEvent).not.toHaveBeenCalled()
  })

  it('같은 종류의 값 변경도 적용으로 세고 초기화는 none과 0으로 전송한다', () => {
    const current = new URLSearchParams('announcementRegionCode=11')
    const next = new URLSearchParams('announcementRegionCode=41')
    trackAppliedFilters('announcement', current, next)
    trackAppliedFilters('announcement', next, new URLSearchParams())
    expect(vi.mocked(trackEvent).mock.calls).toEqual([
      ['apply_filter', { filter_target: 'announcement', filter_types: 'region', filter_count: 1 }],
      ['apply_filter', { filter_target: 'announcement', filter_types: 'none', filter_count: 0 }],
    ])
  })
})
