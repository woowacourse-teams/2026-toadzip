import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getStreetViewConfiguration } from './api'
import { StreetViewEntry } from './StreetViewEntry'
import { StreetViewPanel } from './StreetViewPanel'
import { useStreetView } from './useStreetView'
import { useMobileViewport } from '../public-housing/components/useMobileViewport'
import { HousingDetailOverlay } from '../public-housing/components/HousingDetailOverlay'
import { STREET_VIEW_CHANNEL, STREET_VIEW_VERSION } from './protocol'
import type { EnabledStreetViewConfiguration, StreetViewConfiguration, StreetViewEvent } from './types'

const { reports } = vi.hoisted(() => ({ reports: vi.fn<(event: StreetViewEvent) => void>() }))
vi.mock('./api', () => ({ getStreetViewConfiguration: vi.fn() }))
vi.mock('./events', async importOriginal => {
  const actual = await importOriginal<typeof import('./events')>()
  return { ...actual, createStreetViewAttempt: (configuration: EnabledStreetViewConfiguration) => actual.createStreetViewAttempt(configuration, reports) }
})

const configuration: EnabledStreetViewConfiguration = {
  complexId: 2647, provider: 'NAVER', enabled: true, disabledReason: null, policyRevision: 4,
  initialization: {
    searchPosition: { latitude: 37.561443, longitude: 126.962715 },
    lookAtPosition: { latitude: 37.561443, longitude: 126.962715 }, tilt: 0, fov: 90,
  },
}
const properties = { complexId: '2647', name: '어바니엘 충정로' }

function EntryFixture({ complexId, name, supported = true }: { complexId: string; name: string; supported?: boolean }) {
  const controller = useStreetView({ target: { complexId, name }, mobile: useMobileViewport(), supported })
  return <>
    <h2 id={`complex-detail-title-${complexId}`} tabIndex={-1}>단지 상세 제목</h2>
    <button type="button">상세 정보</button>
    <StreetViewEntry controller={controller} name={name} />
    <StreetViewPanel controller={controller} />
  </>
}
function OverlayFixture() {
  const mobile = useMobileViewport()
  const controller = useStreetView({ target: properties, mobile, supported: true })
  return <>
    <HousingDetailOverlay label="단지 상세" mobile={mobile} onClose={() => {}}>
      <button type="button">상세 닫기</button>
      <h2 id={`complex-detail-title-${properties.complexId}`} tabIndex={-1}>단지 상세 제목</h2>
      <button type="button">임대 조건 펼치기</button>
      <StreetViewEntry controller={controller} name={properties.name} />
    </HousingDetailOverlay>
    {controller.active && <StreetViewPanel controller={controller} />}
  </>
}

const request = vi.mocked(getStreetViewConfiguration)
let mobile = false
let viewportListeners: Set<() => void>

beforeEach(() => {
  vi.useFakeTimers()
  request.mockReset().mockResolvedValue(configuration)
  reports.mockClear()
  mobile = false
  viewportListeners = new Set()
  vi.stubGlobal('matchMedia', () => ({
    get matches() { return mobile },
    addEventListener: (_event: string, listener: () => void) => viewportListeners.add(listener),
    removeEventListener: (_event: string, listener: () => void) => viewportListeners.delete(listener),
  }))
})

afterEach(async () => {
  cleanup()
  await Promise.resolve()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

async function showEntry(strict = false) {
  const element = <EntryFixture {...properties} />
  const result = render(strict ? <StrictMode>{element}</StrictMode> : element)
  await act(async () => {})
  return result
}

async function openView() {
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: `${properties.name} 주변 거리뷰 보기` })) })
}

function sendRuntimeMessage(payload: Record<string, unknown>) {
  const frame = screen.getByTitle<HTMLIFrameElement>('네이버 단지 주변 거리뷰')
  fireEvent(window, new MessageEvent('message', {
    origin: window.location.origin, source: frame.contentWindow,
    data: { channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION, attemptId: reports.mock.calls[0]?.[0].attemptId, ...payload },
  }))
}

describe('StreetViewEntry', () => {
  it('모바일에서는 버튼과 API 요청을 만들지 않는다', async () => {
    mobile = true
    await showEntry()
    expect(screen.queryByRole('button', { name: /주변 거리뷰 보기/ })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
    expect(reports).not.toHaveBeenCalled()
  })

  it('실행 정보를 확인하기 전에는 버튼이 비활성이며 실행하지 않는다', async () => {
    request.mockReturnValue(new Promise(() => {}))
    await showEntry()
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveAccessibleDescription('거리뷰 이용 가능 여부를 확인하고 있어요.')
    fireEvent.click(button)
    fireEvent.keyDown(button, { key: 'Enter' })
    fireEvent.keyDown(button, { key: ' ' })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('비활성 사유는 마우스를 600ms 유지하면 표시하고 떠나면 숨긴다', async () => {
    request.mockResolvedValue({ ...configuration, enabled: false, disabledReason: 'POLICY_DISABLED', initialization: null })
    await showEntry()
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    expect(button).toHaveAccessibleDescription('현재 거리뷰 제공이 중단되어 있어요.')
    fireEvent.mouseEnter(button)
    await act(async () => { await vi.advanceTimersByTimeAsync(599) })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(screen.getByRole('tooltip')).toHaveTextContent('현재 거리뷰 제공이 중단되어 있어요.')
    fireEvent.mouseLeave(button)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('비활성 버튼에도 키보드 포커스를 주고 Escape는 안내만 닫는다', async () => {
    request.mockResolvedValue({ ...configuration, enabled: false, disabledReason: 'INVALID_COORDINATES', initialization: null })
    const outerEscape = vi.fn()
    render(<div onKeyDown={outerEscape}><EntryFixture {...properties} /></div>)
    await act(async () => {})
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    act(() => button.focus())
    expect(button).toHaveFocus()
    await act(async () => { await vi.advanceTimersByTimeAsync(600) })
    expect(screen.getByRole('tooltip')).toHaveTextContent('위치정보를 확인할 수 없어')
    fireEvent.keyDown(button, { key: 'Escape' })
    expect(outerEscape).not.toHaveBeenCalled()
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    expect(button).toHaveFocus()
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    fireEvent.click(button)
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
  })

  it('마우스로만 연 안내도 다른 곳에 포커스가 있을 때 Escape로 닫고 상세는 유지한다', async () => {
    request.mockResolvedValue({ ...configuration, enabled: false, disabledReason: 'POLICY_DISABLED', initialization: null })
    const outerEscape = vi.fn()
    render(<div onKeyDown={outerEscape}>
      <button type="button">다른 정보</button>
      <EntryFixture {...properties} />
    </div>)
    await act(async () => {})
    const otherButton = screen.getByRole('button', { name: '다른 정보' })
    act(() => otherButton.focus())
    fireEvent.mouseEnter(screen.getByRole('button', { name: /주변 거리뷰 보기/ }))
    await act(async () => { await vi.advanceTimersByTimeAsync(600) })
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    expect(otherButton).toHaveFocus()
    fireEvent.keyDown(otherButton, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    expect(outerEscape).not.toHaveBeenCalled()
    expect(otherButton).toHaveFocus()
    fireEvent.keyDown(otherButton, { key: 'Escape' })
    expect(outerEscape).toHaveBeenCalledOnce()
  })

  it('조회 실패 뒤 다시 확인하면 버튼을 활성화한다', async () => {
    request.mockRejectedValueOnce(new Error('offline'))
    await showEntry()
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    expect(button).toHaveAttribute('aria-disabled', 'true')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 확인' })) })
    expect(button).toHaveAttribute('aria-disabled', 'false')
    expect(screen.queryByRole('button', { name: '다시 확인' })).not.toBeInTheDocument()
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('열기 직전 정책을 재조회하고 중단됐으면 SDK나 시작 이벤트를 만들지 않는다', async () => {
    request.mockResolvedValueOnce(configuration).mockResolvedValueOnce({
      ...configuration, enabled: false, disabledReason: 'POLICY_DISABLED', initialization: null, policyRevision: 5,
    })
    await showEntry()
    await openView()
    expect(request).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('region', { name: '단지 주변 거리뷰' })).toHaveTextContent('현재 거리뷰 제공이 중단되어 있어요.')
    expect(screen.queryByTitle('네이버 단지 주변 거리뷰')).not.toBeInTheDocument()
    expect(reports).not.toHaveBeenCalled()
  })

  it('재조회한 canonical 단지와 정책 버전으로 시작하고 준비·촬영일을 화면에 반영한다', async () => {
    request.mockResolvedValueOnce(configuration).mockResolvedValueOnce({ ...configuration, complexId: 12, policyRevision: 8 })
    await showEntry()
    await openView()
    expect(reports.mock.calls[0][0]).toMatchObject({ complexId: 12, policyRevision: 8, type: 'STARTED' })
    expect(screen.getByRole('status')).toHaveTextContent('거리뷰를 불러오는 중이에요.')
    sendRuntimeMessage({ type: 'PHASE', phase: 'SDK' })
    sendRuntimeMessage({ type: 'PHASE', phase: 'PANORAMA' })
    sendRuntimeMessage({ type: 'LOCATION', photodate: '2025-05' })
    sendRuntimeMessage({ type: 'READY', aligned: true })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toHaveAttribute('aria-hidden', 'false')
    expect(screen.getByText('촬영일 2025-05')).toBeInTheDocument()
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'READY'])
    sendRuntimeMessage({ type: 'LOCATION', photodate: null })
    expect(screen.getByText('촬영일 미제공')).toBeInTheDocument()
  })

  it('자동 방향을 적용하지 못해도 영상과 직접 둘러보기 안내를 제공한다', async () => {
    await showEntry()
    await openView()
    sendRuntimeMessage({ type: 'READY', aligned: false })
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toHaveAttribute('aria-hidden', 'false')
    expect(screen.getByText('초기 방향을 맞추지 못했어요. 화면을 움직여 주변을 확인해 주세요.')).toBeInTheDocument()
  })

  it('StrictMode에서도 실제 열기와 최초 닫기만 한 번씩 보고하고 버튼 포커스를 복원한다', async () => {
    await showEntry(true)
    expect(reports).not.toHaveBeenCalled()
    await openView()
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    const close = screen.getByRole('button', { name: '지도 보기' })
    act(() => close.focus())
    await act(async () => { fireEvent.click(close) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(button).toHaveFocus()
    expect(reports.mock.calls.map(([event]) => [event.type, event.reasonCode])).toEqual([
      ['STARTED', null], ['CANCELLED', 'USER_CLOSED'],
    ])
  })

  it('iframe 내부의 닫기 요청은 거리뷰만 닫고 바깥 상세에 키 이벤트를 전파하지 않는다', async () => {
    const outerKeyDown = vi.fn()
    render(<div onKeyDown={outerKeyDown}><EntryFixture {...properties} /></div>)
    await act(async () => {})
    await openView()
    await act(async () => { sendRuntimeMessage({ type: 'CLOSE_REQUEST' }) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(reports.mock.calls[1][0]).toMatchObject({ type: 'CANCELLED', reasonCode: 'USER_CLOSED' })
  })

  it('거리뷰는 일반 영역이며 Escape로 닫은 뒤 상세와 포커스를 유지한다', async () => {
    const outerEscape = vi.fn()
    render(<div onKeyDown={outerEscape}><EntryFixture {...properties} /></div>)
    await act(async () => {})
    await openView()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '지도 보기' })).toHaveFocus()
    await act(async () => { fireEvent.keyDown(screen.getByRole('region', { name: '단지 주변 거리뷰' }), { key: 'Escape' }) })
    expect(outerEscape).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '단지 상세 제목' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /주변 거리뷰 보기/ })).toHaveFocus()
  })

  it('대상 변경은 진행 중 시도를 취소하고 이전 단지 버튼으로 포커스를 복원하지 않는다', async () => {
    const result = await showEntry()
    await openView()
    await act(async () => { result.rerender(<EntryFixture {...properties} complexId="42" name="다른 단지" />) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(reports.mock.calls[1][0]).toMatchObject({ type: 'CANCELLED', reasonCode: 'TARGET_CHANGED' })
    expect(request).toHaveBeenLastCalledWith(42, expect.any(AbortSignal))
    expect(screen.getByRole('button', { name: '다른 단지 주변 거리뷰 보기' })).not.toHaveFocus()
  })

  it('모바일 폭 전환은 진행 중 iframe을 정리하고 존재하지 않는 취소 사유를 전송하지 않는다', async () => {
    await showEntry()
    await openView()
    const requestsBeforeResize = request.mock.calls.length
    act(() => { mobile = true; viewportListeners.forEach(listener => listener()) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /주변 거리뷰 보기/ })).not.toBeInTheDocument()
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED'])
    expect(request).toHaveBeenCalledTimes(requestsBeforeResize)
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(reports).toHaveBeenCalledTimes(1)
  })

  it('초기화 중 페이지를 떠나면 iframe을 정리하고 unmount도 대상 변경 취소로 오인하지 않는다', async () => {
    const result = await showEntry(true)
    await openView()
    sendRuntimeMessage({ type: 'PHASE', phase: 'SDK' })
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toBeInTheDocument()
    await act(async () => { fireEvent(window, new Event('pagehide')) })
    expect(screen.queryByTitle('네이버 단지 주변 거리뷰')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    await act(async () => { result.unmount() })
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED'])
  })

  it('늦게 끝난 이전 단지의 조회가 현재 단지 버튼이나 실행을 변경하지 않는다', async () => {
    let finishPrevious: ((value: StreetViewConfiguration) => void) | undefined
    request.mockReturnValueOnce(new Promise(resolve => { finishPrevious = resolve }))
    const result = await showEntry()
    request.mockResolvedValue({ ...configuration, enabled: false, disabledReason: 'INVALID_COORDINATES', initialization: null })
    await act(async () => { result.rerender(<EntryFixture {...properties} complexId="42" name="다른 단지" />) })
    await act(async () => { finishPrevious?.(configuration) })
    const button = screen.getByRole('button', { name: '다른 단지 주변 거리뷰 보기' })
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveAccessibleDescription('이 단지의 위치정보를 확인할 수 없어 거리뷰를 제공하지 못해요.')
    expect(reports).not.toHaveBeenCalled()
  })

  it('실패 후 재시도는 정책 재조회와 새 시도 ID로 시작한다', async () => {
    await showEntry()
    await openView()
    sendRuntimeMessage({ type: 'PHASE', phase: 'SDK' })
    sendRuntimeMessage({ type: 'FAILED', phase: 'SDK', reasonCode: 'SDK_AUTH_FAILED' })
    expect(screen.getByRole('alert')).toHaveTextContent('거리뷰를 불러오지 못했어요.')
    expect(screen.queryByTitle('네이버 단지 주변 거리뷰')).not.toBeInTheDocument()
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })) })
    expect(request).toHaveBeenCalledTimes(3)
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'FAILED', 'STARTED'])
    expect(reports.mock.calls[2][0].attemptId).not.toBe(reports.mock.calls[0][0].attemptId)
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toBeInTheDocument()
  })

  it('정상 표시 이후 탐색 실패는 안내하되 초기화 종료 이벤트를 다시 보내지 않는다', async () => {
    await showEntry()
    await openView()
    sendRuntimeMessage({ type: 'READY', aligned: true })
    sendRuntimeMessage({ type: 'FAILED', phase: 'PANORAMA', reasonCode: 'PANORAMA_QUERY_FAILED' })
    expect(screen.getByRole('alert')).toHaveTextContent('거리뷰를 불러오지 못했어요.')
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'READY'])
  })

  it('설정 재조회가 진행 중일 때 닫으면 늦은 성공이 거리뷰를 다시 열지 않는다', async () => {
    let finishOpening: ((value: StreetViewConfiguration) => void) | undefined
    request.mockResolvedValueOnce(configuration).mockReturnValueOnce(new Promise(resolve => { finishOpening = resolve }))
    await showEntry()
    await openView()
    expect(screen.getByRole('status')).toHaveTextContent('거리뷰를 불러오는 중이에요.')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '지도 보기' })) })
    await act(async () => { finishOpening?.(configuration) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(reports).not.toHaveBeenCalled()
  })

  it('local snapshot에서 기능을 제공하지 않으면 버튼과 실행 정보 요청을 만들지 않는다', async () => {
    render(<EntryFixture {...properties} supported={false} />)
    await act(async () => {})
    expect(screen.queryByRole('button', { name: /주변 거리뷰 보기/ })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('활성 버튼은 영역을 연결하고 정책 차단 상태에서도 닫기를 우선한다', async () => {
    request.mockResolvedValueOnce(configuration).mockResolvedValueOnce({
      ...configuration, enabled: false, disabledReason: 'POLICY_DISABLED', initialization: null,
    })
    await showEntry()
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    expect(button).toHaveAttribute('aria-pressed', 'false')
    await openView()
    expect(button).toHaveAttribute('aria-pressed', 'true')
    expect(button).toHaveAttribute('aria-disabled', 'false')
    expect(button).toHaveAttribute('aria-controls', screen.getByRole('region', { name: '단지 주변 거리뷰' }).id)
    await act(async () => { fireEvent.click(button) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(button).toHaveAttribute('aria-pressed', 'false')
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(request).toHaveBeenCalledTimes(2)
    expect(reports).not.toHaveBeenCalled()
  })

  it('재조회 실패가 발생한 열린 영역도 활성 버튼으로 닫는다', async () => {
    request.mockResolvedValueOnce(configuration).mockRejectedValueOnce(new Error('offline'))
    await showEntry()
    await openView()
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    expect(button).toHaveAttribute('aria-disabled', 'false')
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await act(async () => { fireEvent.click(button) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 확인' })).toBeInTheDocument()
  })

  it('비모달 상세 안의 설명은 Escape로 닫되 열린 첨부 모달의 Escape를 가로채지 않는다', async () => {
    request.mockResolvedValue({ ...configuration, enabled: false, disabledReason: 'POLICY_DISABLED', initialization: null })
    const attachmentEscape = vi.fn()
    render(<>
      <dialog open aria-modal="false" aria-label="단지 상세"><EntryFixture {...properties} /></dialog>
      <dialog open aria-modal="true" aria-label="첨부파일" onKeyDown={attachmentEscape}><button type="button">문서 조작</button></dialog>
    </>)
    await act(async () => {})
    const button = screen.getByRole('button', { name: /주변 거리뷰 보기/ })
    act(() => button.focus())
    await act(async () => { await vi.advanceTimersByTimeAsync(600) })
    fireEvent.keyDown(button, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    const attachment = screen.getByRole('button', { name: '문서 조작' })
    act(() => attachment.focus())
    fireEvent.mouseEnter(button)
    await act(async () => { await vi.advanceTimersByTimeAsync(600) })
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(attachment, { key: 'Escape' })
    expect(attachmentEscape).toHaveBeenCalledOnce()
  })

  it.each([true, false])('핀 실패와 READY의 순서에 관계없이 영상을 유지한다 (핀 먼저: %s)', async markerFirst => {
    await showEntry()
    await openView()
    if (markerFirst) sendRuntimeMessage({ type: 'MARKER_STATUS', status: 'UNAVAILABLE' })
    sendRuntimeMessage({ type: 'READY', aligned: true })
    if (!markerFirst) sendRuntimeMessage({ type: 'MARKER_STATUS', status: 'UNAVAILABLE' })
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toHaveAttribute('aria-hidden', 'false')
    expect(screen.getByRole('status')).toHaveTextContent('출입구 위치 표시를 불러오지 못했어요. 거리뷰는 계속 둘러볼 수 있어요.')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'READY'])
  })

  it('준비와 촬영일·핀 응답이 도착해도 상세를 읽는 사용자에게서 포커스를 빼앗지 않는다', async () => {
    await showEntry()
    await openView()
    const detail = screen.getByRole('button', { name: '상세 정보' })
    act(() => detail.focus())
    sendRuntimeMessage({ type: 'READY', aligned: true })
    sendRuntimeMessage({ type: 'LOCATION', photodate: '2026-10' })
    sendRuntimeMessage({ type: 'MARKER_STATUS', status: 'ATTACHED' })
    expect(detail).toHaveFocus()
  })

  it('문서 준비 시간 초과는 다시 시도와 화면 새로고침 안내를 제공한다', async () => {
    await showEntry()
    await openView()
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(screen.getByRole('alert')).toHaveTextContent('거리뷰를 불러오지 못했어요.')
    expect(screen.getByText('계속 연결되지 않으면 화면을 새로고침한 뒤 다시 열어 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(reports.mock.calls[1][0]).toMatchObject({ type: 'FAILED', phase: 'DOCUMENT', reasonCode: 'DOCUMENT_TIMEOUT' })
  })

  it('모바일 전환 때 거리뷰에 있던 포커스만 상세 제목으로 옮긴다', async () => {
    await showEntry()
    await openView()
    expect(screen.getByRole('button', { name: '지도 보기' })).toHaveFocus()
    await act(async () => { mobile = true; viewportListeners.forEach(listener => listener()) })
    expect(screen.getByRole('heading', { name: '단지 상세 제목' })).toHaveFocus()
    await act(async () => { mobile = false; viewportListeners.forEach(listener => listener()) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    await openView()
    const detail = screen.getByRole('button', { name: '상세 정보' })
    act(() => detail.focus())
    act(() => { mobile = true; viewportListeners.forEach(listener => listener()) })
    expect(detail).toHaveFocus()
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'STARTED'])
  })

  it('설정 재조회 중 대상이 바뀌면 이전 응답은 새 상세의 거리뷰를 열지 않는다', async () => {
    let finishOpening: ((value: StreetViewConfiguration) => void) | undefined
    request.mockResolvedValueOnce(configuration).mockReturnValueOnce(new Promise(resolve => { finishOpening = resolve }))
    const result = await showEntry()
    await openView()
    await act(async () => { result.rerender(<EntryFixture {...properties} complexId="42" name="다른 단지" />) })
    await act(async () => { finishOpening?.(configuration) })
    expect(screen.queryByRole('region', { name: '단지 주변 거리뷰' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다른 단지 주변 거리뷰 보기' })).toHaveAttribute('aria-pressed', 'false')
    expect(reports).not.toHaveBeenCalled()
  })

  it('재시도 뒤 이전 iframe의 늦은 핀·준비 메시지가 새 화면에 반영되지 않는다', async () => {
    await showEntry()
    await openView()
    const previousWindow = screen.getByTitle<HTMLIFrameElement>('네이버 단지 주변 거리뷰').contentWindow
    const previousAttempt = reports.mock.calls[0][0].attemptId
    sendRuntimeMessage({ type: 'FAILED', phase: 'SDK', reasonCode: 'SDK_LOAD_FAILED' })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })) })
    for (const payload of [{ type: 'MARKER_STATUS', status: 'UNAVAILABLE' }, { type: 'READY', aligned: true }]) {
      fireEvent(window, new MessageEvent('message', { origin: window.location.origin, source: previousWindow,
        data: { channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION, attemptId: previousAttempt, ...payload } }))
    }
    expect(screen.getByRole('status')).toHaveTextContent('거리뷰를 불러오는 중이에요.')
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.queryByText(/출입구 위치 표시를 불러오지/)).not.toBeInTheDocument()
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'FAILED', 'STARTED'])
  })


  it.each(['button', 'iframe'])('모바일 상세가 autofocus해도 거리뷰 %s의 포커스는 상세 제목으로 옮긴다', async target => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
      this.open = true
      this.querySelector('button')?.focus()
    })
    render(<OverlayFixture />)
    await act(async () => {})
    await openView()
    sendRuntimeMessage({ type: 'READY', aligned: true })
    if (target === 'iframe') act(() => screen.getByTitle('네이버 단지 주변 거리뷰').focus())
    expect(target === 'iframe' ? screen.getByTitle('네이버 단지 주변 거리뷰') : screen.getByRole('button', { name: '지도 보기' })).toHaveFocus()
    await act(async () => { mobile = true; viewportListeners.forEach(listener => listener()) })
    expect(screen.queryByTitle('네이버 단지 주변 거리뷰')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '단지 상세 제목' })).toHaveFocus()
    expect(reports.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'READY'])
  })

  it('거리뷰가 열린 상태에서도 상세를 읽던 포커스는 모바일 전환 때 유지한다', async () => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
      this.open = true
      this.querySelector('button')?.focus()
    })
    render(<OverlayFixture />)
    await act(async () => {})
    await openView()
    const detailButton = screen.getByRole('button', { name: '임대 조건 펼치기' })
    act(() => detailButton.focus())
    await act(async () => { mobile = true; viewportListeners.forEach(listener => listener()) })
    expect(detailButton).toHaveFocus()
  })


  it('iframe 문서가 포커스를 받아 부모 window가 blur되면 React focus 이벤트 없이도 복귀 위치를 보존한다', async () => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
      this.open = true
      this.querySelector('button')?.focus()
    })
    render(<OverlayFixture />)
    await act(async () => {})
    await openView()
    sendRuntimeMessage({ type: 'READY', aligned: true })
    const frame = screen.getByTitle('네이버 단지 주변 거리뷰')
    act(() => frame.focus())
    // Crossing into an iframe can blur the parent focus chain without a new React focus event.
    fireEvent.blur(frame, { relatedTarget: null })
    await act(async () => { fireEvent(window, new Event('blur')) })
    await act(async () => { mobile = true; viewportListeners.forEach(listener => listener()) })
    expect(screen.getByRole('heading', { name: '단지 상세 제목' })).toHaveFocus()
  })


  it('각 MediaQueryList 알림이 따로 전달되어도 거리뷰에서 모바일 상세로 포커스를 복귀한다', async () => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
      this.open = true
      this.querySelector('button')?.focus()
    })
    render(<OverlayFixture />)
    await act(async () => {})
    await openView()
    expect(screen.getByRole('button', { name: '지도 보기' })).toHaveFocus()
    mobile = true
    for (const listener of viewportListeners) await act(async () => { listener() })
    expect(screen.getByRole('heading', { name: '단지 상세 제목' })).toHaveFocus()
  })


  it('767px 경계의 브라우저 자동 포커스가 React 알림보다 먼저 와도 마지막 PC 거리뷰 포커스를 유지한다', async () => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
      this.open = true
      this.querySelector('button')?.focus()
    })
    render(<OverlayFixture />)
    await act(async () => {})
    await openView()
    expect(screen.getByRole('button', { name: '지도 보기' })).toHaveFocus()
    mobile = true
    // Native breakpoint/dialog focus can run before React commits the media query update.
    act(() => screen.getByRole('button', { name: '상세 닫기' }).focus())
    await act(async () => { viewportListeners.forEach(listener => listener()) })
    expect(screen.getByRole('heading', { name: '단지 상세 제목' })).toHaveFocus()
  })

})
