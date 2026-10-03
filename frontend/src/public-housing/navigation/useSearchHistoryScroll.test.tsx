import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSearchHistoryScroll } from './useSearchHistoryScroll.ts'

const originalScrollX = Object.getOwnPropertyDescriptor(window, 'scrollX')
const originalScrollY = Object.getOwnPropertyDescriptor(window, 'scrollY')
const originalRestoration = Object.getOwnPropertyDescriptor(window.history, 'scrollRestoration')

function setPagePosition(x: number, y: number) {
  Object.defineProperty(window, 'scrollX', { configurable: true, value: x })
  Object.defineProperty(window, 'scrollY', { configurable: true, value: y })
}

function restoreProperty(object: object, name: string, descriptor?: PropertyDescriptor) {
  if (descriptor) Object.defineProperty(object, name, descriptor)
  else Reflect.deleteProperty(object, name)
}

beforeEach(() => {
  setPagePosition(0, 0)
  Object.defineProperty(window.history, 'scrollRestoration', { configurable: true, writable: true, value: 'auto' })
  vi.spyOn(window, 'scrollTo').mockImplementation((x, y) => {
    if (typeof x === 'number' && typeof y === 'number') setPagePosition(x, y)
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  restoreProperty(window, 'scrollX', originalScrollX)
  restoreProperty(window, 'scrollY', originalScrollY)
  restoreProperty(window.history, 'scrollRestoration', originalRestoration)
})

function setup() {
  const complexes = { current: document.createElement('div') }
  const announcements = { current: document.createElement('div') }
  const hook = renderHook(({ key, restore, ready }) => useSearchHistoryScroll(
    key, restore, ready, complexes, announcements,
  ), { initialProps: { key: 'first', restore: false, ready: true } })
  return { ...hook, complexes: complexes.current, announcements: announcements.current }
}

describe('검색 이력별 스크롤 복원', () => {
  it('브라우저 자동 복원을 중지하고 unmount 시 원래 설정으로 돌린다', () => {
    const { unmount } = setup()
    expect(window.history.scrollRestoration).toBe('manual')
    unmount()
    expect(window.history.scrollRestoration).toBe('auto')
  })

  it('뒤로·앞으로 이동한 각 이력의 단지·공고·페이지 스크롤을 결과 준비 후 복원한다', () => {
    const { complexes, announcements, rerender } = setup()
    complexes.scrollTop = 120
    announcements.scrollTop = 230
    setPagePosition(10, 45)

    rerender({ key: 'second', restore: false, ready: true })
    expect(window.scrollTo).not.toHaveBeenCalled()
    complexes.scrollTop = 350
    announcements.scrollTop = 460
    setPagePosition(20, 70)

    rerender({ key: 'first', restore: true, ready: false })
    expect(window.scrollTo).not.toHaveBeenCalled()
    complexes.scrollTop = 0
    announcements.scrollTop = 0
    rerender({ key: 'first', restore: true, ready: true })
    expect(complexes.scrollTop).toBe(120)
    expect(announcements.scrollTop).toBe(230)
    expect(window.scrollTo).toHaveBeenLastCalledWith(10, 45)

    rerender({ key: 'second', restore: true, ready: true })
    expect(complexes.scrollTop).toBe(350)
    expect(announcements.scrollTop).toBe(460)
    expect(window.scrollTo).toHaveBeenLastCalledWith(20, 70)
  })

  it('복원은 한 번만 하고 이후 같은 이력의 재렌더링은 사용자 스크롤을 유지한다', () => {
    const { complexes, announcements, rerender } = setup()
    complexes.scrollTop = 120
    announcements.scrollTop = 230
    setPagePosition(0, 45)
    rerender({ key: 'second', restore: false, ready: true })
    rerender({ key: 'first', restore: true, ready: true })
    expect(window.scrollTo).toHaveBeenCalledOnce()

    complexes.scrollTop = 500
    announcements.scrollTop = 600
    setPagePosition(0, 800)
    rerender({ key: 'first', restore: true, ready: false })
    rerender({ key: 'first', restore: true, ready: true })
    expect(complexes.scrollTop).toBe(500)
    expect(announcements.scrollTop).toBe(600)
    expect(window.scrollTo).toHaveBeenCalledOnce()
  })

  it('복원 대기 중 새 이력으로 이동하면 이전 이력의 스크롤을 적용하지 않는다', () => {
    const { complexes, announcements, rerender } = setup()
    complexes.scrollTop = 120
    announcements.scrollTop = 230
    rerender({ key: 'second', restore: false, ready: true })
    rerender({ key: 'first', restore: true, ready: false })
    act(() => {
      complexes.scrollTop = 50
      announcements.scrollTop = 60
    })
    rerender({ key: 'third', restore: false, ready: true })
    expect(complexes.scrollTop).toBe(50)
    expect(announcements.scrollTop).toBe(60)
    expect(window.scrollTo).not.toHaveBeenCalled()
  })

  it('저장된 위치가 없는 외부 이력은 임의로 스크롤하지 않는다', () => {
    const { complexes, announcements, rerender } = setup()
    complexes.scrollTop = 120
    announcements.scrollTop = 230
    rerender({ key: 'unseen', restore: true, ready: true })
    expect(complexes.scrollTop).toBe(120)
    expect(announcements.scrollTop).toBe(230)
    expect(window.scrollTo).not.toHaveBeenCalled()
  })
})
