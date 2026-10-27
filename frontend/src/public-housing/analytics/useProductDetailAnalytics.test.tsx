import { StrictMode } from 'react'
import { act, cleanup, render, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DetailLocationResult } from '../navigation/detailLocation'
import { useProductDetailAnalytics } from './useProductDetailAnalytics'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { ProductAnalyticsBoundary } from '../../analytics/ProductAnalyticsBoundary'
import type { AnalyticsStopReason } from '../../privacy/consentStore'
import { HousingDetailOverlay } from '../components/HousingDetailOverlay'
import type { MarkerHoverAttribution } from '../../analytics/markerHoverAnalytics'
import type { DetailEntryPoint } from '../../analytics/googleAnalytics'

const consent = vi.hoisted(() => ({ allowed: true, listeners: new Set<() => void>(),
  stopListeners: new Set<(reason: AnalyticsStopReason) => void>(),
}))
vi.mock('../../privacy/consentStore', () => ({
  analyticsCollectionAllowed: () => consent.allowed,
  consentStore: {
    getSnapshot: () => ({ context: { consent: { scopeVersion: 'analytics-scope-3' }, requiredScopeVersion: 'analytics-scope-3' } }),
    remainingConsentLifetime: () => 180 * 24 * 60 * 60 * 1000,
    transition: () => { consent.allowed = false; consent.stopListeners.forEach(listener => listener('context-change')); consent.listeners.forEach(listener => listener()) },
    refresh: async () => {},
    subscribe(listener: () => void) {
      consent.listeners.add(listener)
      return () => consent.listeners.delete(listener)
    },
    onStop(listener: (reason: AnalyticsStopReason) => void) {
      consent.stopListeners.add(listener)
      return () => consent.stopListeners.delete(listener)
    },
  },
}))
vi.mock('../../analytics/productAnalytics', () => ({
  captureProductEvent: vi.fn(() => consent.allowed),
  captureWebPageView: vi.fn(() => consent.allowed),
  createAnalyticsId: () => crypto.randomUUID(),
  setProductPageActive: vi.fn(),
}))
const capture = vi.mocked(captureProductEvent)
const rectangle = { x: 10, y: 10, top: 10, left: 10, bottom: 300, right: 400, width: 390, height: 290, toJSON: () => ({}) }
function Probe({ location, ready, mobile }: { location: DetailLocationResult; ready: string | null; mobile?: boolean }) {
  useProductDetailAnalytics({ detailLocation: location,
    readyComplexId: location.kind === 'complex' ? ready : null,
    readyAnnouncementId: location.kind === 'announcement' ? ready : null })
  if (location.kind !== 'complex' && location.kind !== 'announcement') return null
  if (mobile !== undefined) return <HousingDetailOverlay mobile={mobile} label={location.kind === 'complex' ? '단지 상세' : '공고 상세'} onClose={() => {}}><h2>상세</h2></HousingDetailOverlay>
  return <dialog open className="housing-detail-layer" aria-label={location.kind === 'complex' ? '단지 상세' : '공고 상세'}><h2>상세</h2></dialog>
}
const wrap = ({ children }: { children: React.ReactNode }) => <StrictMode><MemoryRouter initialEntries={['/explore']}>{children}</MemoryRouter></StrictMode>
beforeEach(() => {
  consent.allowed = true
  Object.defineProperty(navigator, 'locks', { configurable: true,
    value: { request: vi.fn(async (_name: string, callback: () => unknown) => callback()) },
  })
  capture.mockClear().mockImplementation(() => consent.allowed)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rectangle)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
})

describe('직접 마커 호버와 실제 상세 표시 연결', () => {
  function hover(complexId = '12'): MarkerHoverAttribution {
    return { complex_id: complexId, exploration_id: crypto.randomUUID(), hover_id: crypto.randomUUID(), measurement_version: 'marker_hover_v1' }
  }
  function setup() {
    const dialog = document.createElement('dialog')
    dialog.open = true; dialog.className = 'housing-detail-layer'; dialog.setAttribute('aria-label', '단지 상세')
    dialog.dataset.testBlocker = 'true'; document.body.append(dialog)
    const initialProps: { location: DetailLocationResult; ready: string | null } = { location: { kind: 'none' }, ready: null }
    const view = renderHook(({ location, ready }) => useProductDetailAnalytics({
      detailLocation: location, readyComplexId: ready, readyAnnouncementId: null,
    }), { wrapper: wrap, initialProps })
    return { ...view, dialog }
  }
  it('호버 종료·재진입 후 늦게 표시되어도 처음 요청한 호버와 방문 ID를 유지한다', async () => {
    const view = setup()
    const first = hover()
    const original = { ...first }
    act(() => view.result.current.prepare('complex', '12', 'map', first))
    const request = collected('detail_open_requested')[0]?.[1]
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: null })
    // Later hover state must not be consulted by the asynchronous detail observer.
    Object.assign(first, hover())
    expect(collected('view_complex')).toHaveLength(0)
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    expect(request).toMatchObject(original)
    expect(collected('view_complex')[0]?.[1]).toMatchObject({ ...original, detail_visit_id: request?.detail_visit_id })
  })
  it('상세가 호버 종료 전에 먼저 표시되어도 같은 연결을 한 번만 남긴다', async () => {
    const view = setup(), attribution = hover()
    act(() => view.result.current.prepare('complex', '12', 'map', attribution))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    expect(collected('view_complex')).toHaveLength(1)
    expect(collected('view_complex')[0]?.[1]).toMatchObject(attribution)
  })
  it('응답 실패·취소는 실제 열람이 아니며 다음 방문에 이전 호버를 붙이지 않는다', async () => {
    const view = setup()
    act(() => view.result.current.prepare('complex', '12', 'map', hover()))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: null })
    await act(async () => {})
    expect(collected('view_complex')).toHaveLength(0)
    view.rerender({ location: { kind: 'none' }, ready: null })
    view.rerender({ location: { kind: 'complex', complexId: '13' }, ready: '12' })
    await act(async () => {})
    expect(collected('view_complex')).toHaveLength(0)
    view.rerender({ location: { kind: 'complex', complexId: '13' }, ready: '13' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    expect(collected('view_complex')[0]?.[1]).not.toHaveProperty('hover_id')
  })
  it.each(['list', 'search', 'recent', 'history', 'direct'] as DetailEntryPoint[])('%s 진입에는 전달된 호버가 있어도 연결하지 않는다', async entry => {
    const view = setup()
    act(() => view.result.current.prepare('complex', '12', entry, hover()))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    expect(collected('detail_open_requested')[0]?.[1]).not.toHaveProperty('hover_id')
    expect(collected('view_complex')[0]?.[1]).not.toHaveProperty('hover_id')
  })
  it('다른 단지 호버는 연결하지 않는다', async () => {
    const view = setup()
    act(() => view.result.current.prepare('complex', '12', 'map', hover('13')))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    expect(collected('view_complex')[0]?.[1]).not.toHaveProperty('hover_id')
  })
  it('이미 열린 상세의 재클릭은 기존 방문과 연결을 바꾸지 않는다', async () => {
    const view = setup(), first = hover()
    act(() => view.result.current.prepare('complex', '12', 'map', first))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    const original = collected('view_complex')[0]?.[1]
    const second = hover()
    act(() => view.result.current.prepare('complex', '12', 'map', second))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    expect(collected('view_complex')).toHaveLength(1)
    expect(collected('view_complex')[0]?.[1]).toMatchObject(first)
    expect(collected('detail_open_requested')[1]?.[1]).toMatchObject({ ...second, detail_visit_id: original?.detail_visit_id })
  })
  it.each(['list', 'search', 'keyboard'] as const)('이미 열린 상세를 %s에서 다시 선택해도 요청에 이전 호버를 붙이지 않는다', async entry => {
    const view = setup(), attribution = hover()
    act(() => view.result.current.prepare('complex', '12', 'map', attribution))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    act(() => view.result.current.prepare('complex', '12', entry === 'keyboard' ? 'map' : entry))
    expect(collected('detail_open_requested')[1]?.[1]).not.toHaveProperty('hover_id')
    expect(collected('view_complex')).toHaveLength(1)
    expect(collected('view_complex')[0]?.[1]).toMatchObject(attribution)
  })
  it('한 호버에서 상세를 닫고 다시 열면 서로 다른 상세 방문으로 남긴다', async () => {
    const view = setup(), attribution = hover()
    act(() => view.result.current.prepare('complex', '12', 'map', attribution))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    view.rerender({ location: { kind: 'none' }, ready: null })
    act(() => view.result.current.prepare('complex', '12', 'map', attribution))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(2))
    const visits = collected('view_complex').map(([, properties]) => properties)
    expect(visits[0]?.hover_id).toBe(visits[1]?.hover_id)
    expect(visits[0]?.detail_visit_id).not.toBe(visits[1]?.detail_visit_id)
  })
  it.each(['reset', 'context-change', 'consent-replaced'] as const)('%s 후 늦은 응답에는 이전 연결을 재사용하지 않는다', async reason => {
    const view = setup()
    act(() => view.result.current.prepare('complex', '12', 'map', hover()))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: null })
    await act(async () => notifyStop(reason))
    view.rerender({ location: { kind: 'complex', complexId: '12' }, ready: '12' })
    await setConsent(true)
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    expect(collected('view_complex')[0]?.[1]).not.toHaveProperty('hover_id')
  })
})
afterEach(() => { cleanup(); consent.listeners.clear(); consent.stopListeners.clear(); document.querySelectorAll('[data-test-blocker]').forEach(node => node.remove()); vi.restoreAllMocks(); vi.unstubAllEnvs(); vi.unstubAllGlobals() })

function collected(name: string) {
  return capture.mock.calls.filter(([event], index) => event === name && capture.mock.results[index]?.value === true)
}

async function setConsent(allowed: boolean) {
  await act(async () => { consent.allowed = allowed; consent.listeners.forEach(listener => listener()) })
}

it('모바일 상세 페이지도 방문 ID를 부여하고 데스크톱 전환 시 중복 집계하지 않는다', async () => {
  const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" mobile />, { wrapper: wrap })
  await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
  const page = document.querySelector<HTMLElement>('.housing-detail-layer')
  expect(page?.getAttribute('role')).toBe('region')
  const visit = page?.dataset.detailVisitId
  expect(visit).toBeTruthy()
  view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" mobile={false} />)
  await act(async () => {})
  expect(collected('view_complex')).toHaveLength(1)
  expect(page?.dataset.detailVisitId).toBe(visit)
})

function notifyStop(reason: AnalyticsStopReason) {
  consent.allowed = false
  consent.stopListeners.forEach(listener => listener(reason))
  consent.listeners.forEach(listener => listener())
}

describe('제품 활성 상세 계측', () => {
  it.each(['complex', 'announcement'] as const)('%s 상세가 먼저 표시되어도 동의 확인이 끝나면 현재 방문을 한 번 수집한다', async (kind) => {
    consent.allowed = false
    const location: DetailLocationResult = kind === 'complex'
      ? { kind, complexId: '12' } : { kind, announcementId: '12' }
    render(<><ProductAnalyticsBoundary /><Probe location={location} ready="12" /></>, { wrapper: wrap })
    await act(async () => {})
    expect(collected(`view_${kind}`)).toHaveLength(0)

    // Only the consent store changes: the detail, DOM and viewport stay the same.
    await setConsent(true)
    expect(collected('page_view')).toHaveLength(1)
    expect(collected(`view_${kind}`)).toHaveLength(1)
    expect(collected(`view_${kind}`)[0]?.[1]).toMatchObject({ [`${kind}_id`]: '12' })

    await setConsent(false)
    await setConsent(true)
    expect(collected(`view_${kind}`)).toHaveLength(1)
  })

  it('동의 확인 전에 닫은 상세 방문을 나중에 소급 수집하지 않는다', async () => {
    consent.allowed = false
    const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await act(async () => {})
    view.rerender(<Probe location={{ kind: 'none' }} ready={null} />)
    await setConsent(true)
    expect(collected('view_complex')).toHaveLength(0)
  })

  it.each([
    ['reset', 'complex'], ['reset', 'announcement'],
    ['context-change', 'complex'], ['context-change', 'announcement'],
  ] as const)('%s 후 새 허용 상태에서 현재 %s 상세를 새 방문 ID로 한 번 수집한다', async (reason, kind) => {
    const location: DetailLocationResult = kind === 'complex'
      ? { kind, complexId: '12' } : { kind, announcementId: '12' }
    render(<Probe location={location} ready="12" />, { wrapper: wrap })
    await waitFor(() => expect(collected(`view_${kind}`)).toHaveLength(1))
    const first = collected(`view_${kind}`)[0]

    await act(async () => notifyStop(reason))
    expect(collected(`view_${kind}`)).toHaveLength(1)
    await setConsent(true)
    expect(collected(`view_${kind}`)).toHaveLength(2)
    const second = collected(`view_${kind}`)[1]
    expect(second?.[1]?.detail_visit_id).not.toBe(first?.[1]?.detail_visit_id)
    expect(second?.[2]?.dedupeKey).not.toBe(first?.[2]?.dedupeKey)

    await setConsent(true)
    await act(async () => window.dispatchEvent(new Event('resize')))
    expect(collected(`view_${kind}`)).toHaveLength(2)
  })

  it.each(['reset', 'context-change'] as const)('%s 직후 같은 작업 안에서 새 허용 상태가 확인되어도 현재 상세를 새로 수집한다', async reason => {
    render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    await act(async () => {
      notifyStop(reason)
      consent.allowed = true
      consent.listeners.forEach(listener => listener())
    })
    expect(collected('view_complex')).toHaveLength(2)
  })

  it.each(['reset', 'context-change'] as const)('%s 후 새 허용 상태 확인 전에 닫은 상세는 다시 수집하지 않는다', async reason => {
    const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    await act(async () => notifyStop(reason))
    view.rerender(<Probe location={{ kind: 'none' }} ready={null} />)
    await setConsent(true)
    expect(collected('view_complex')).toHaveLength(1)
  })

  it('일시 중단 후 허용이 복구되어도 같은 상세를 중복 수집하지 않는다', async () => {
    render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await waitFor(() => expect(collected('view_complex')).toHaveLength(1))
    const firstVisit = collected('view_complex')[0]?.[1]?.detail_visit_id
    await act(async () => notifyStop('pause'))
    await setConsent(true)
    expect(collected('view_complex')).toHaveLength(1)
    expect(document.querySelector<HTMLElement>('dialog.housing-detail-layer')?.dataset.detailVisitId).toBe(firstVisit)
  })

  it('실제 전송 대기 중 인증 전환으로 폐기된 상세는 새 허용 상태에서 다시 전송한다', async () => {
    vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken')
    vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
    vi.stubEnv('VITE_ANALYTICS_ENV', 'dev')
    vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true')
    vi.stubEnv('MODE', 'development')
    localStorage.clear(); sessionStorage.clear()
    window.history.replaceState({}, '', '/explore')
    let firstResponse: (response: Response) => void = () => { throw new Error('request missing') }
    const network = vi.fn<typeof fetch>()
      .mockImplementationOnce(() => new Promise(resolve => { firstResponse = resolve }))
      .mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', network)
    const analytics = await vi.importActual<typeof import('../../analytics/productAnalytics')>('../../analytics/productAnalytics')
    capture.mockImplementation(analytics.captureProductEvent)
    try {
      render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
      await waitFor(() => expect(network).toHaveBeenCalledOnce())
      // capture=true means accepted into the real core queue, not HTTP acceptance.
      expect(collected('view_complex')).toHaveLength(1)
      expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()
      const firstVisit = collected('view_complex')[0]?.[1]?.detail_visit_id
      const signal = network.mock.calls[0]?.[1]?.signal
      await act(async () => notifyStop('context-change'))
      expect(signal?.aborted).toBe(true)
      await act(async () => firstResponse(new Response('{}', { status: 200 })))
      expect(sessionStorage.getItem('toadzip.posthog.sent-actions.v1')).toBeNull()

      await setConsent(true)
      await waitFor(() => expect(network).toHaveBeenCalledTimes(2))
      expect(collected('view_complex')).toHaveLength(2)
      expect(collected('view_complex')[1]?.[1]?.detail_visit_id).not.toBe(firstVisit)
      await act(async () => window.dispatchEvent(new Event('resize')))
      expect(network).toHaveBeenCalledTimes(2)
    } finally {
      cleanup()
      notifyStop('reset')
    }
  })

  it('로드 성공이 다른 팝업 뒤에 있으면 기다리고, 노출 후 재렌더와 반응형 재열기는 한 번만 센다', async () => {
    const blocker = document.createElement('dialog'); blocker.open = true; blocker.dataset.testBlocker = 'true'; document.body.append(blocker)
    const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    blocker.remove()
    await waitFor(() => expect(capture).toHaveBeenCalledTimes(1))
    expect(capture).toHaveBeenLastCalledWith('view_complex', expect.objectContaining({ complex_id: '12', entry_point: 'direct' }), expect.any(Object))
    const dialog = document.querySelector('dialog.housing-detail-layer') as HTMLDialogElement
    const visit = dialog.dataset.detailVisitId
    await act(async () => { dialog.open = false; dialog.open = true })
    view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />)
    expect(capture).toHaveBeenCalledTimes(1)
    expect(dialog.dataset.detailVisitId).toBe(visit)
  })
  it('잘못된 타깃 응답과 백그라운드를 세지 않고 닫고 재열면 새 방문으로 센다', async () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const view = render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="11" />, { wrapper: wrap })
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />)
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    visibility.mockReturnValue('visible')
    await act(async () => document.dispatchEvent(new Event('visibilitychange')))
    expect(capture).toHaveBeenCalledTimes(1)
    const first = capture.mock.calls[0][1]?.detail_visit_id
    view.rerender(<Probe location={{ kind: 'none' }} ready={null} />)
    view.rerender(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />)
    await waitFor(() => expect(capture).toHaveBeenCalledTimes(2))
    expect(capture.mock.calls[1][1]?.detail_visit_id).not.toBe(first)
  })
  it('뷰포트 밖 또는 크기 없는 상세는 활성으로 세지 않는다', async () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ ...rectangle, width: 0, height: 0 })
    render(<Probe location={{ kind: 'complex', complexId: '12' }} ready="12" />, { wrapper: wrap })
    await act(async () => {})
    expect(capture).not.toHaveBeenCalled()
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rectangle)
    await act(async () => window.dispatchEvent(new Event('resize')))
    expect(capture).toHaveBeenCalledTimes(1)
  })
})
