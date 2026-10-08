import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createStreetViewAttempt } from './events'
import { StreetViewFrame, type StreetViewSession } from './StreetViewFrame'
import { STREET_VIEW_CHANNEL, STREET_VIEW_VERSION } from './protocol'
import type { EnabledStreetViewConfiguration, StreetViewEvent } from './types'

const configuration: EnabledStreetViewConfiguration = {
  complexId: 2647, provider: 'NAVER', enabled: true, disabledReason: null, policyRevision: 4,
  initialization: {
    searchPosition: { latitude: 37.561443, longitude: 126.962715 },
    lookAtPosition: { latitude: 37.561443, longitude: 126.962715 }, tilt: 0, fov: 90,
  },
}

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks() })

function setup(strict = false) {
  const report = vi.fn<(event: StreetViewEvent) => void>()
  const attempt = createStreetViewAttempt(configuration, report)
  attempt.start()
  const session: StreetViewSession = { configuration, attempt, startedAt: performance.now(), sdkStartedAt: null, markerLabel: '단지명 · 출입구' }
  const callbacks = { onReady: vi.fn(), onLocation: vi.fn(), onMarkerStatus: vi.fn(), onFailure: vi.fn(), onClose: vi.fn() }
  const element = <StreetViewFrame session={session} ready={false} {...callbacks} />
  const view = render(strict ? <StrictMode>{element}</StrictMode> : element)
  const frame = screen.getByTitle<HTMLIFrameElement>('네이버 단지 주변 거리뷰')
  function send(payload: Record<string, unknown>, overrides: MessageEventInit = {}) {
    fireEvent(window, new MessageEvent('message', {
      origin: window.location.origin, source: frame.contentWindow,
      data: { channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION, attemptId: attempt.id, ...payload },
      ...overrides,
    }))
  }
  return { ...view, ...callbacks, frame, session, report, send }
}

describe('StreetViewFrame', () => {
  it('동일 출처 문서의 준비 후에만 초기 입력을 메시지로 보내며 URL에는 좌표를 넣지 않는다', () => {
    const { frame, send, session } = setup()
    expect(frame).toHaveAttribute('src', '/street-view.html')
    expect(frame).toHaveAttribute('aria-hidden', 'true')
    expect(frame).toHaveAttribute('tabindex', '-1')
    const child = frame.contentWindow
    if (!child) throw new Error('거리뷰 iframe 문서가 없습니다.')
    const post = vi.spyOn(child, 'postMessage')
    expect(post).not.toHaveBeenCalled()
    send({ type: 'BOOT_READY' })
    expect(post).toHaveBeenCalledExactlyOnceWith({
      channel: STREET_VIEW_CHANNEL, version: STREET_VIEW_VERSION, type: 'INIT',
      attemptId: session.attempt.id, initialization: configuration.initialization, markerLabel: session.markerLabel,
    }, window.location.origin)
  })

  it('다른 출처·iframe·버전·시도 ID의 메시지를 무시한다', () => {
    const { send, onReady, onLocation, onMarkerStatus, onClose, onFailure, report } = setup()
    send({ type: 'READY', aligned: true }, { origin: 'https://other.example.com' })
    send({ type: 'READY', aligned: true }, { source: window })
    send({ type: 'READY', aligned: true, version: 999 })
    send({ type: 'READY', aligned: true, attemptId: crypto.randomUUID() })
    send({ type: 'LOCATION', photodate: '2025-05', attemptId: crypto.randomUUID() })
    send({ type: 'CLOSE_REQUEST', attemptId: crypto.randomUUID() })
    send({ type: 'FAILED', phase: 'SDK', reasonCode: 'UNKNOWN' })
    send({ type: 'MARKER_STATUS', status: 'ATTACHED' }, { origin: 'https://other.example.com' })
    send({ type: 'MARKER_STATUS', status: 'ATTACHED' }, { source: window })
    send({ type: 'MARKER_STATUS', status: 'ATTACHED', version: 1 })
    send({ type: 'MARKER_STATUS', status: 'ATTACHED', attemptId: crypto.randomUUID() })
    send({ type: 'MARKER_STATUS', status: 'VISIBLE' })
    expect(onReady).not.toHaveBeenCalled()
    expect(onLocation).not.toHaveBeenCalled()
    expect(onMarkerStatus).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(onFailure).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledTimes(1)
  })

  it.each(['before', 'after'] as const)('handles marker status %s READY without reporting a second terminal result', (order) => {
    const { send, onReady, onMarkerStatus, onFailure, report } = setup()
    if (order === 'before') send({ type: 'MARKER_STATUS', status: 'UNAVAILABLE' })
    send({ type: 'READY', aligned: false })
    if (order === 'after') send({ type: 'MARKER_STATUS', status: 'UNAVAILABLE' })
    expect(onReady).toHaveBeenCalledExactlyOnceWith(false)
    expect(onMarkerStatus).toHaveBeenCalledExactlyOnceWith('UNAVAILABLE')
    expect(onFailure).not.toHaveBeenCalled()
    expect(report.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'READY'])
  })

  it('does not initialize a mixed-version child and exposes the document failure reason for refresh guidance', async () => {
    const { frame, send, onFailure, onMarkerStatus } = setup()
    const child = frame.contentWindow
    if (!child) throw new Error('거리뷰 iframe 문서가 없습니다.')
    const post = vi.spyOn(child, 'postMessage')
    send({ type: 'BOOT_READY', version: 1 })
    send({ type: 'MARKER_STATUS', status: 'ATTACHED', version: 1 })
    expect(post).not.toHaveBeenCalled()
    expect(onMarkerStatus).not.toHaveBeenCalled()
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(onFailure).toHaveBeenCalledExactlyOnceWith('DOCUMENT_TIMEOUT')
  })

  it('문서 준비가 안 되면 30초에 실패하고 숨겨진 프레임을 성공으로 취급하지 않는다', async () => {
    const { report, onFailure, onReady } = setup()
    await act(async () => { await vi.advanceTimersByTimeAsync(29_999) })
    expect(onFailure).not.toHaveBeenCalled()
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(onFailure).toHaveBeenCalledExactlyOnceWith('DOCUMENT_TIMEOUT')
    expect(onReady).not.toHaveBeenCalled()
    expect(report.mock.calls[1][0]).toMatchObject({ type: 'FAILED', phase: 'DOCUMENT', reasonCode: 'DOCUMENT_TIMEOUT', durationMs: 30_000 })
  })

  it('SDK 준비는 15초로 제한하며 같은 단계의 중복 알림이 마감 시간을 늘리지 않는다', async () => {
    const { send, report, onFailure } = setup()
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    send({ type: 'PHASE', phase: 'SDK' })
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    send({ type: 'PHASE', phase: 'SDK' })
    await act(async () => { await vi.advanceTimersByTimeAsync(4_999) })
    expect(onFailure).not.toHaveBeenCalled()
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(onFailure).toHaveBeenCalledOnce()
    expect(report.mock.calls[1][0]).toMatchObject({ type: 'FAILED', phase: 'SDK', reasonCode: 'INITIALIZATION_TIMEOUT', durationMs: 20_000 })
  })

  it('SDK가 준비된 후에는 전체 30초 제한만 남기고 늦은 SDK 단계로 되돌아가지 않는다', async () => {
    const { send, report, onFailure } = setup()
    send({ type: 'PHASE', phase: 'SDK' })
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    send({ type: 'PHASE', phase: 'PANORAMA' })
    send({ type: 'PHASE', phase: 'SDK' })
    await act(async () => { await vi.advanceTimersByTimeAsync(19_999) })
    expect(onFailure).not.toHaveBeenCalled()
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(report.mock.calls[1][0]).toMatchObject({ type: 'FAILED', phase: 'PANORAMA', reasonCode: 'INITIALIZATION_TIMEOUT', durationMs: 30_000 })
  })

  it('StrictMode 재등록은 시작·취소를 늘리지 않고 준비 후 모든 초기화 마감을 제거한다', async () => {
    const { send, report, onReady, onFailure, rerender, session } = setup(true)
    send({ type: 'PHASE', phase: 'SDK' })
    send({ type: 'PHASE', phase: 'PANORAMA' })
    send({ type: 'READY', aligned: true })
    send({ type: 'READY', aligned: true })
    expect(onReady).toHaveBeenCalledExactlyOnceWith(true)
    expect(report.mock.calls.map(([event]) => event.type)).toEqual(['STARTED', 'READY'])
    rerender(<StrictMode><StreetViewFrame session={session} ready onReady={onReady} onFailure={onFailure}
      onClose={() => {}} onLocation={() => {}} onMarkerStatus={() => {}} /></StrictMode>)
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(onFailure).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledTimes(2)
    expect(screen.getByTitle('네이버 단지 주변 거리뷰')).toHaveAttribute('tabindex', '0')
  })

  it('재등록이 있어도 문서 실행 시점의 전체 마감 시간을 유지한다', async () => {
    const report = vi.fn<(event: StreetViewEvent) => void>()
    const attempt = createStreetViewAttempt(configuration, report)
    attempt.start()
    const session: StreetViewSession = { configuration, attempt, startedAt: performance.now(), sdkStartedAt: null, markerLabel: '단지명 · 출입구' }
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    const onFailure = vi.fn()
    render(<StreetViewFrame session={session} ready={false} onReady={() => {}} onLocation={() => {}}
      onClose={() => {}} onFailure={onFailure} onMarkerStatus={() => {}} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(onFailure).toHaveBeenCalledOnce()
    expect(report.mock.calls[1][0].durationMs).toBe(30_000)
  })

  it('종료한 프레임은 늦은 메시지나 타이머를 처리하지 않는다', async () => {
    const { unmount, send, report, onReady, onFailure, onMarkerStatus, onClose } = setup()
    send({ type: 'PHASE', phase: 'SDK' })
    unmount()
    send({ type: 'READY', aligned: true })
    send({ type: 'FAILED', phase: 'SDK', reasonCode: 'SDK_AUTH_FAILED' })
    send({ type: 'CLOSE_REQUEST' })
    send({ type: 'MARKER_STATUS', status: 'ATTACHED' })
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(onReady).not.toHaveBeenCalled()
    expect(onFailure).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(onMarkerStatus).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledTimes(1)
  })
})
