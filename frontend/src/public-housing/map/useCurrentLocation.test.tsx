import { act, renderHook, waitFor } from '@testing-library/react'
import { StrictMode, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCurrentLocation } from './useCurrentLocation.ts'

let succeed: PositionCallback
let fail: PositionErrorCallback | null
const getCurrentPosition = vi.fn<Geolocation['getCurrentPosition']>()

function position(latitude = 37.5, longitude = 127): GeolocationPosition {
  return {
    coords: { latitude, longitude, accuracy: 100, altitude: null,
      altitudeAccuracy: null, heading: null, speed: null, toJSON: () => ({}) },
    timestamp: Date.now(), toJSON: () => ({}),
  }
}

function error(code: number): GeolocationPositionError {
  return { code, message: 'browser error', PERMISSION_DENIED: 1,
    POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }
}

beforeEach(() => {
  getCurrentPosition.mockReset().mockImplementation((success, failure) => {
    succeed = success
    fail = failure ?? null
  })
  vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } })
})
afterEach(() => vi.unstubAllGlobals())

describe('현재 위치 확인', () => {
  it('시작 가능할 때 StrictMode에서도 한 번만 확인하고 유효 좌표를 전달한다', async () => {
    const onLocated = vi.fn()
    const wrapper = ({ children }: { children: ReactNode }) => <StrictMode>{children}</StrictMode>
    const { result, rerender } = renderHook(() => useCurrentLocation(true, onLocated), { wrapper })
    await waitFor(() => expect(getCurrentPosition).toHaveBeenCalledOnce())
    expect(getCurrentPosition.mock.calls[0]?.[2]).toEqual({
      enableHighAccuracy: false, maximumAge: 0, timeout: 8_000,
    })
    expect(result.current.state.kind).toBe('loading')
    expect(result.current.position).toBeNull()
    act(() => succeed(position()))
    expect(onLocated).toHaveBeenCalledExactlyOnceWith({ latitude: 37.5, longitude: 127 })
    expect(result.current.state.kind).toBe('located')
    expect(result.current.position).toEqual({ latitude: 37.5, longitude: 127 })
    rerender()
    expect(getCurrentPosition).toHaveBeenCalledOnce()
  })

  it('자동 시작을 기다리고 수동 요청은 중복 실행하지 않는다', () => {
    const { result } = renderHook(() => useCurrentLocation(false, vi.fn()))
    expect(result.current.position).toBeNull()
    expect(getCurrentPosition).not.toHaveBeenCalled()
    act(() => { result.current.locate(); result.current.locate() })
    expect(getCurrentPosition).toHaveBeenCalledOnce()
  })

  it('안내창 종료 전에 사용자가 위치를 선택하면 자동 시작하지 않는다', async () => {
    const { result, rerender } = renderHook(({ enabled }) => useCurrentLocation(enabled, vi.fn()), {
      initialProps: { enabled: false },
    })
    act(() => result.current.cancel())
    rerender({ enabled: true })
    await act(async () => {})
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })

  it('취소한 요청의 결과를 무시하고 버튼으로 다시 확인할 수 있다', () => {
    const onLocated = vi.fn()
    const { result } = renderHook(() => useCurrentLocation(false, onLocated))
    act(() => result.current.locate())
    const oldSuccess = succeed
    const oldFailure = fail
    act(() => result.current.cancel())
    act(() => result.current.locate())
    act(() => { oldSuccess(position(35, 129)); oldFailure?.(error(1)) })
    expect(onLocated).not.toHaveBeenCalled()
    expect(result.current.state.kind).toBe('loading')
    expect(result.current.position).toBeNull()
    act(() => succeed(position()))
    expect(onLocated).toHaveBeenCalledExactlyOnceWith({ latitude: 37.5, longitude: 127 })
    expect(result.current.position).toEqual({ latitude: 37.5, longitude: 127 })
  })

  it('사용자가 지도를 움직이거나 위치 확인이 일시 실패해도 마지막 위치를 유지한다', () => {
    const { result } = renderHook(() => useCurrentLocation(false, vi.fn()))
    act(() => result.current.locate())
    act(() => succeed(position()))
    act(() => result.current.cancel())
    expect(result.current.position).toEqual({ latitude: 37.5, longitude: 127 })
    act(() => result.current.locate())
    act(() => fail?.(error(2)))
    expect(result.current.position).toEqual({ latitude: 37.5, longitude: 127 })
  })

  it('취소한 요청의 거부는 무시하고 현재 요청에서 권한이 거부되면 마지막 위치를 지운다', () => {
    const { result } = renderHook(() => useCurrentLocation(false, vi.fn()))
    act(() => result.current.locate())
    act(() => succeed(position()))
    act(() => result.current.locate())
    const oldFailure = fail
    act(() => result.current.cancel())
    act(() => result.current.locate())
    act(() => oldFailure?.(error(1)))
    expect(result.current.position).toEqual({ latitude: 37.5, longitude: 127 })
    expect(result.current.state.kind).toBe('loading')
    act(() => fail?.(error(1)))
    expect(result.current.position).toBeNull()
    expect(result.current.state).toMatchObject({ kind: 'error', reason: 'permission-denied' })
  })

  it('화면을 닫은 후 위치 결과를 적용하지 않는다', () => {
    const onLocated = vi.fn()
    const { result, unmount } = renderHook(() => useCurrentLocation(false, onLocated))
    act(() => result.current.locate())
    unmount()
    act(() => succeed(position()))
    expect(onLocated).not.toHaveBeenCalled()
  })

  it.each([1, 2, 3])('브라우저 오류 %i를 안내하고 재시도를 허용한다', code => {
    const onLocated = vi.fn()
    const { result } = renderHook(() => useCurrentLocation(false, onLocated))
    act(() => result.current.locate())
    act(() => fail?.(error(code)))
    expect(result.current.state).toEqual({ kind: 'error', reason: code === 1 ? 'permission-denied' : 'failed', message: expect.stringContaining(
      code === 1 ? '차단' : '확인하지 못했어요',
    ) })
    expect(onLocated).not.toHaveBeenCalled()
    act(() => result.current.locate())
    expect(getCurrentPosition).toHaveBeenCalledTimes(2)
  })

  it.each([[NaN, 127], [91, 127], [37, 181], [37, Infinity]])(
    '잘못된 좌표 %s,%s는 지도로 전달하지 않는다', (latitude, longitude) => {
      const onLocated = vi.fn()
      const { result } = renderHook(() => useCurrentLocation(false, onLocated))
      act(() => result.current.locate())
      act(() => succeed(position(latitude, longitude)))
      expect(onLocated).not.toHaveBeenCalled()
      expect(result.current.state.kind).toBe('error')
    },
  )

  it('위치 기능이 없으면 지역 검색을 안내한다', () => {
    vi.stubGlobal('navigator', {})
    const { result } = renderHook(() => useCurrentLocation(false, vi.fn()))
    act(() => result.current.locate())
    expect(result.current.state).toEqual({ kind: 'error', reason: 'unavailable', message: expect.stringContaining('지역을 검색') })
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })

  it('브라우저 호출 자체가 실패해도 오류 상태로 복구한다', () => {
    getCurrentPosition.mockImplementation(() => { throw new Error('unavailable') })
    const { result } = renderHook(() => useCurrentLocation(false, vi.fn()))
    act(() => result.current.locate())
    expect(result.current.state.kind).toBe('error')
  })

  it('보안 컨텍스트가 아니면 위치 요청 없이 지역 검색을 안내한다', () => {
    vi.stubGlobal('isSecureContext', false)
    const { result } = renderHook(() => useCurrentLocation(false, vi.fn()))
    act(() => result.current.locate())
    expect(getCurrentPosition).not.toHaveBeenCalled()
    expect(result.current.state.kind).toBe('error')
  })
})
