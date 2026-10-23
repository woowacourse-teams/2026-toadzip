import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useMobileViewport } from './useMobileViewport'

function DevicePolicy() {
  return <output>{useMobileViewport() ? '모바일 화면' : '데스크톱 화면'}</output>
}

function viewport({ width, touch = false, hover = !touch }: { width: number; touch?: boolean; hover?: boolean }) {
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      if (query === '(max-width: 767px)') return width <= 767
      if (query === '(hover: none) and (pointer: coarse)') return touch && !hover
      throw new Error(`Unexpected query: ${query}`)
    },
    addEventListener: (_: string, callback: () => void) => listeners.add(callback),
    removeEventListener: (_: string, callback: () => void) => listeners.delete(callback),
  }))
  return {
    resize(next: number) { act(() => { width = next; listeners.forEach(listener => listener()) }) },
    changeInput(nextTouch: boolean, nextHover: boolean) {
      act(() => { touch = nextTouch; hover = nextHover; listeners.forEach(listener => listener()) })
    },
    listeners,
  }
}

afterEach(() => vi.unstubAllGlobals())

it('터치 중심 휴대폰은 세로에서 가로로 회전하고 다시 돌아와도 모바일 UI를 유지한다', () => {
  const device = viewport({ width: 390, touch: true })
  render(<DevicePolicy />)
  expect(screen.getByText('모바일 화면')).toBeVisible()
  device.resize(844)
  expect(screen.getByText('모바일 화면')).toBeVisible()
  device.resize(390)
  expect(screen.getByText('모바일 화면')).toBeVisible()
})

it.each([844, 915, 1024, 1366])('터치 중심 브라우저의 %ipx 직접 진입은 모바일 UI다', width => {
  viewport({ width, touch: true })
  render(<DevicePolicy />)
  expect(screen.getByText('모바일 화면')).toBeVisible()
})

it('마우스 브라우저는 넓은 화면에서 데스크톱, 좁은 창에서 모바일 UI다', () => {
  const device = viewport({ width: 844 })
  render(<DevicePolicy />)
  expect(screen.getByText('데스크톱 화면')).toBeVisible()
  device.resize(767)
  expect(screen.getByText('모바일 화면')).toBeVisible()
  device.resize(768)
  expect(screen.getByText('데스크톱 화면')).toBeVisible()
})

it('hover를 지원하는 넓은 터치 혼합 입력 환경은 데스크톱을 유지한다', () => {
  const device = viewport({ width: 1200, touch: true, hover: true })
  const { unmount } = render(<DevicePolicy />)
  expect(screen.getByText('데스크톱 화면')).toBeVisible()
  device.changeInput(true, false)
  expect(screen.getByText('모바일 화면')).toBeVisible()
  device.changeInput(false, true)
  expect(screen.getByText('데스크톱 화면')).toBeVisible()
  unmount()
  expect(device.listeners.size).toBe(0)
})
