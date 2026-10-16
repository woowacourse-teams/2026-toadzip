import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CurrentLocationControl } from './CurrentLocationControl.tsx'
import { useCurrentLocation, type CurrentLocationState } from './useCurrentLocation.ts'

const deniedState: CurrentLocationState = {
  kind: 'error',
  message: '위치 사용이 차단되어 있어요. 브라우저 설정에서 허용하거나 지역을 검색해 주세요.',
}

afterEach(() => vi.unstubAllGlobals())

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
    render(<CurrentLocationControl state={{ kind }} onLocate={vi.fn()} />)

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '위치 안내 닫기' })).not.toBeInTheDocument()
    const locateButton = screen.getByRole('button', { name: '현재 위치로 이동' })
    expect(locateButton).not.toHaveAttribute('aria-describedby')
    if (kind === 'loading') expect(locateButton).toBeDisabled()
    else expect(locateButton).toBeEnabled()
  })
})
