import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CurrentLocationControl } from './CurrentLocationControl.tsx'
import { useCurrentLocation, type CurrentLocationState } from './useCurrentLocation.ts'

let succeed: PositionCallback
let fail: PositionErrorCallback | null
const getCurrentPosition = vi.fn<Geolocation['getCurrentPosition']>()

function LocationControl() {
  const { state, locate } = useCurrentLocation(false, () => {})
  return <CurrentLocationControl state={state} onLocate={locate} />
}

function rejectLocation(code: number) {
  act(() => fail?.({ code, message: 'browser error', PERMISSION_DENIED: 1,
    POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }))
}

beforeEach(() => {
  getCurrentPosition.mockReset().mockImplementation((success, failure) => {
    succeed = success
    fail = failure ?? null
  })
  vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } })
})
afterEach(() => vi.unstubAllGlobals())

describe('현재 위치 권한 안내', () => {
  it('위치 안내가 떠 있는 동안에만 표시 상태를 밖에서 알 수 있게 하고 스스로 닫히지 않는다', () => {
    vi.useFakeTimers()
    try {
      const { container } = render(<LocationControl />)
      const status = container.querySelector('[data-location-status]')
      expect(status).toHaveAttribute('hidden')
      fireEvent.click(screen.getByRole('button', { name: '현재 위치로 이동' }))
      rejectLocation(1)
      expect(status).not.toHaveAttribute('hidden')
      act(() => vi.advanceTimersByTime(60_000))
      expect(screen.getByRole('status')).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: '위치 안내 닫기' }))
      expect(status).toHaveAttribute('hidden')
    } finally {
      vi.useRealTimers()
    }
  })

  it.each([false, true])('설정 방법 펼침=%s에서 안내 전체를 닫고 새 위치 요청 후 다시 표시한다', expanded => {
    render(<LocationControl />)
    const locate = screen.getByRole('button', { name: '현재 위치로 이동' })
    fireEvent.click(locate)
    rejectLocation(1)
    if (expanded) fireEvent.click(screen.getByRole('button', { name: '설정 방법' }))
    const close = screen.getByRole('button', { name: '위치 안내 닫기' })
    close.focus()
    fireEvent.click(close)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '설정 방법' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '위치 사용 설정 방법' })).not.toBeInTheDocument()
    expect(locate).toHaveFocus()
    expect(locate).not.toHaveAttribute('aria-describedby')
    expect(getCurrentPosition).toHaveBeenCalledTimes(1)

    fireEvent.click(locate)
    rejectLocation(1)
    expect(screen.getByRole('status')).toBeVisible()
    expect(screen.getByRole('button', { name: '설정 방법' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: '위치 안내 닫기' })).toBeVisible()
  })

  it('권한 거부 뒤 설정 방법을 펼치고 재시도하면 위치 버튼에 포커스를 유지한다', () => {
    render(<LocationControl />)
    const locate = screen.getByRole('button', { name: '현재 위치로 이동' })
    fireEvent.click(locate)
    rejectLocation(1)

    const settings = screen.getByRole('button', { name: '설정 방법' })
    expect(settings).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: '위치 사용 설정 방법' })).not.toBeInTheDocument()
    settings.focus()
    fireEvent.click(settings)
    expect(settings).toHaveFocus()
    expect(settings).toHaveAttribute('aria-expanded', 'true')
    const guidance = screen.getByRole('region', { name: '위치 사용 설정 방법' })
    expect(settings).toHaveAttribute('aria-controls', guidance.id)
    expect(within(guidance).getByRole('heading', { name: 'iPhone · Safari' })).toBeVisible()
    expect(within(guidance).getByRole('heading', { name: 'Android · Chrome' })).toBeVisible()
    expect(screen.getByRole('status')).not.toContainElement(guidance)

    const retry = within(guidance).getByRole('button', { name: '다시 시도' })
    retry.focus()
    fireEvent.click(retry)
    expect(getCurrentPosition).toHaveBeenCalledTimes(2)
    expect(locate).toHaveFocus()
    expect(locate).toHaveAttribute('aria-disabled', 'true')
    expect(screen.queryByRole('button', { name: '설정 방법' })).not.toBeInTheDocument()
    fireEvent.click(locate)
    expect(getCurrentPosition).toHaveBeenCalledTimes(2)

    act(() => succeed({
      coords: { latitude: 37.5, longitude: 127, accuracy: 100, altitude: null,
        altitudeAccuracy: null, heading: null, speed: null, toJSON: () => ({}) },
      timestamp: Date.now(), toJSON: () => ({}),
    }))
    expect(locate).toHaveFocus()
    expect(locate).toHaveAttribute('aria-disabled', 'false')
    expect(screen.queryByRole('region', { name: '위치 사용 설정 방법' })).not.toBeInTheDocument()
  })

  it('안내에서 Escape를 누르면 접고 설정 방법 버튼으로 포커스를 돌린다', () => {
    render(<LocationControl />)
    fireEvent.click(screen.getByRole('button', { name: '현재 위치로 이동' }))
    rejectLocation(1)
    const settings = screen.getByRole('button', { name: '설정 방법' })
    fireEvent.click(settings)
    const retry = screen.getByRole('button', { name: '다시 시도' })
    retry.focus()
    fireEvent.keyDown(retry, { key: 'Escape' })
    expect(settings).toHaveFocus()
    expect(settings).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: '위치 사용 설정 방법' })).not.toBeInTheDocument()
  })

  it.each([2, 3])('권한 거부가 아닌 오류 %i에는 설정 방법을 표시하지 않는다', code => {
    render(<LocationControl />)
    fireEvent.click(screen.getByRole('button', { name: '현재 위치로 이동' }))
    rejectLocation(code)
    expect(screen.getByRole('status')).toHaveTextContent('현재 위치를 확인하지 못했어요')
    expect(screen.queryByRole('button', { name: '설정 방법' })).not.toBeInTheDocument()
  })

  it('위치를 사용할 수 없는 환경에는 설정 방법을 표시하지 않는다', () => {
    vi.stubGlobal('navigator', {})
    render(<LocationControl />)
    fireEvent.click(screen.getByRole('button', { name: '현재 위치로 이동' }))
    expect(screen.getByRole('status')).toHaveTextContent('이 환경에서는 위치를 사용할 수 없어요')
    expect(screen.queryByRole('button', { name: '설정 방법' })).not.toBeInTheDocument()
  })
})

const deniedState: CurrentLocationState = {
  kind: 'error',
  reason: 'permission-denied',
  message: '위치 사용이 차단되어 있어요. 브라우저 설정에서 허용하거나 지역을 검색해 주세요.',
}

describe('현재 위치 안내 닫기', () => {
  it('오류 안내만 닫고 위치 이동 버튼과 재시도를 유지한다', () => {
    const onLocate = vi.fn()
    const { rerender } = render(<CurrentLocationControl state={deniedState} onLocate={onLocate} />)
    const locateButton = screen.getByRole('button', { name: '현재 위치로 이동' })
    expect(screen.getByRole('status')).toHaveTextContent(deniedState.message)
    expect(locateButton).toHaveAccessibleDescription(deniedState.message)

    fireEvent.click(screen.getByRole('button', { name: '위치 안내 닫기' }))

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '위치 안내 닫기' })).not.toBeInTheDocument()
    expect(locateButton).toBeEnabled()
    expect(locateButton).not.toHaveAttribute('aria-describedby')
    expect(onLocate).not.toHaveBeenCalled()

    rerender(<CurrentLocationControl state={deniedState} onLocate={onLocate} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    rerender(<CurrentLocationControl state={{ ...deniedState }} onLocate={onLocate} />)
    expect(screen.getByRole('status')).toBeVisible()
    fireEvent.click(locateButton)
    expect(onLocate).toHaveBeenCalledOnce()
  })

  it('포커스된 닫기 버튼이 사라지면 위치 이동 버튼으로 포커스를 돌린다', () => {
    render(<CurrentLocationControl state={deniedState} onLocate={vi.fn()} />)
    const closeButton = screen.getByRole('button', { name: '위치 안내 닫기' })
    closeButton.focus()

    fireEvent.click(closeButton)

    expect(screen.getByRole('button', { name: '현재 위치로 이동' })).toHaveFocus()
  })

  it('다른 요소에 있는 포커스는 안내를 닫아도 이동하지 않는다', () => {
    render(<>
      <button type="button">지역 검색</button>
      <CurrentLocationControl state={deniedState} onLocate={vi.fn()} />
    </>)
    const searchButton = screen.getByRole('button', { name: '지역 검색' })
    searchButton.focus()

    fireEvent.click(screen.getByRole('button', { name: '위치 안내 닫기' }))

    expect(searchButton).toHaveFocus()
  })

  it.each(['denied', 'unavailable'] as const)('닫은 뒤 위치 재시도가 즉시 %s 오류로 실패해도 다시 안내한다', failure => {
    vi.stubGlobal('navigator', failure === 'unavailable' ? {} : {
      geolocation: {
        getCurrentPosition: (_success: PositionCallback, onError: PositionErrorCallback) => {
          onError({ code: 1, message: 'permission denied', PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2, TIMEOUT: 3 })
        },
      },
    })
    function LocationControl() {
      const { state, locate } = useCurrentLocation(false, vi.fn())
      return <CurrentLocationControl state={state} onLocate={locate} />
    }
    render(<LocationControl />)
    const locateButton = screen.getByRole('button', { name: '현재 위치로 이동' })
    fireEvent.click(locateButton)
    const firstMessage = screen.getByRole('status').textContent
    fireEvent.click(screen.getByRole('button', { name: '위치 안내 닫기' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    fireEvent.click(locateButton)

    expect(screen.getByRole('status').textContent).toBe(firstMessage)
    expect(screen.getByRole('button', { name: '위치 안내 닫기' })).toBeVisible()
    expect(locateButton).toHaveAccessibleDescription(firstMessage ?? '')
  })

  it.each(['idle', 'loading', 'located'] as const)('%s 상태에서는 오류 안내와 닫기 버튼을 표시하지 않는다', kind => {
    const onLocate = vi.fn()
    render(<CurrentLocationControl state={{ kind }} onLocate={onLocate} />)

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '위치 안내 닫기' })).not.toBeInTheDocument()
    const locateButton = screen.getByRole('button', { name: '현재 위치로 이동' })
    expect(locateButton).not.toHaveAttribute('aria-describedby')
    expect(locateButton).toHaveAttribute('aria-disabled', String(kind === 'loading'))
    fireEvent.click(locateButton)
    expect(onLocate).toHaveBeenCalledTimes(kind === 'loading' ? 0 : 1)
  })
})
