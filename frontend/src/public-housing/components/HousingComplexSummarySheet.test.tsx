import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { HousingComplexSummarySheet } from './HousingComplexSummarySheet'
import { MemoryRouter } from 'react-router'
import { toComplexDetail } from '../api/publicHousingMapper'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot'

beforeEach(() => {
  vi.stubGlobal('PointerEvent', class extends MouseEvent {
    pointerId: number
    isPrimary: boolean
    constructor(type: string, options: PointerEventInit = {}) {
      super(type, options)
      this.pointerId = options.pointerId ?? 1
      this.isPrimary = options.isPrimary ?? true
    }
  })
  Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', { configurable: true, value: vi.fn() })
})
afterEach(() => {
  vi.unstubAllGlobals()
  delete (HTMLElement.prototype as Partial<HTMLElement>).setPointerCapture
})

function renderSummary(withDetail = false) {
  const expand = vi.fn()
  const close = vi.fn()
  render(<MemoryRouter><HousingComplexSummarySheet recent={{ complexId: '17', name: '가람', address: null }}
    detail={withDetail ? toComplexDetail(MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexDetails[0]) : null}
    status="ready" onExpand={expand} onClose={close} onRetry={() => {}} /></MemoryRouter>)
  return { expand, close, handle: screen.getByRole('button', { name: '단지 상세 펼치기' }) }
}

it('별도 이동 버튼이나 설명 없이 실제 상세 앞부분을 같은 순서로 잘라 보여준다', () => {
  renderSummary(true)
  const summary = screen.getByRole('region', { name: '단지 요약' })
  expect(screen.queryByRole('button', { name: '단지 상세 보기' })).not.toBeInTheDocument()
  expect(screen.queryByText('위로 올려 상세 정보를 확인하세요')).not.toBeInTheDocument()
  expect(summary.querySelector('[data-preview]')).toBeInTheDocument()
  const contents = summary.querySelector('[data-preview]')?.textContent ?? ''
  expect(contents.indexOf('서울가람 행복주택')).toBeLessThan(contents.indexOf('LH'))
  expect(contents.indexOf('LH')).toBeLessThan(contents.indexOf('서울특별시 중구 세종대로 110'))
  expect(contents.indexOf('서울가람 행복주택')).toBeLessThan(contents.indexOf('서울특별시 중구 세종대로 110'))
  expect(contents.indexOf('서울특별시 중구 세종대로 110')).toBeLessThan(contents.indexOf('현재 모집 공고'))
  expect(contents.indexOf('현재 모집 공고')).toBeLessThan(contents.indexOf('단지 기본 정보'))
  expect(summary.querySelector('[inert]')).toHaveAttribute('aria-hidden', 'true')
  expect(summary).toHaveAccessibleDescription(/서울특별시 중구 세종대로 110.*현재 모집 공고 1건/)
  expect(summary).toHaveAccessibleDescription(/LH.*행복주택/)
  expect(screen.getByRole('button', { name: '단지 요약 닫기' })).toBeEnabled()
})

it('콘텐츠에서도 위로 끌어 펼치며 닫기 버튼 조작은 펼침을 일으키지 않는다', () => {
  const { expand, close } = renderSummary(true)
  const title = screen.getByRole('heading', { name: '서울가람 행복주택' })
  fireEvent.pointerDown(title, { clientY: 600, pointerId: 1, button: 0 })
  fireEvent.pointerMove(title, { clientY: 480, pointerId: 1 })
  fireEvent.pointerUp(title, { clientY: 480, pointerId: 1 })
  expect(expand).toHaveBeenCalledOnce()
  const closeButton = screen.getByRole('button', { name: '단지 요약 닫기' })
  fireEvent.pointerDown(closeButton, { clientY: 600, pointerId: 2, button: 0 })
  fireEvent.pointerUp(closeButton, { clientY: 480, pointerId: 2 })
  fireEvent.click(closeButton)
  expect(close).toHaveBeenCalledOnce()
  expect(expand).toHaveBeenCalledOnce()
})

it('위로 끌어 상세를 펼치고 뒤따르는 클릭은 중복 탐색하지 않는다', () => {
  const { expand, handle } = renderSummary()
  fireEvent.pointerDown(handle, { clientY: 600, pointerId: 1, button: 0 })
  fireEvent.pointerMove(handle, { clientY: 450, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 450, pointerId: 1 })
  expect(expand).toHaveBeenCalledOnce()
  fireEvent.click(handle)
  expect(expand).toHaveBeenCalledOnce()
})

it('짧은 드래그와 취소는 펼치지 않고 방향키로 펼칠 수 있다', () => {
  const { expand, handle } = renderSummary()
  fireEvent.pointerDown(handle, { clientY: 600, pointerId: 1, button: 0 })
  fireEvent.pointerUp(handle, { clientY: 590, pointerId: 1 })
  expect(expand).not.toHaveBeenCalled()
  fireEvent.pointerDown(handle, { clientY: 600, pointerId: 2, button: 0 })
  fireEvent.pointerMove(handle, { clientY: 300, pointerId: 2 })
  fireEvent.pointerCancel(handle, { pointerId: 2 })
  expect(expand).not.toHaveBeenCalled()
  fireEvent.keyDown(handle, { key: 'ArrowUp' })
  expect(expand).toHaveBeenCalledOnce()
})

it('아래로 끌면 단지명만 남기고 접으며 위로 끌면 미리보기를 복원한다', () => {
  const { expand, close, handle } = renderSummary()
  fireEvent.pointerDown(handle, { clientY: 500, pointerId: 1, button: 0 })
  fireEvent.pointerMove(handle, { clientY: 650, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 650, pointerId: 1 })
  fireEvent.click(handle)
  expect(screen.getByRole('heading', { name: '가람' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '단지 상세 펼치기' })).not.toBeInTheDocument()
  expect(close).not.toHaveBeenCalled()
  expect(expand).not.toHaveBeenCalled()
  const foldedHandle = screen.getByRole('button', { name: '단지 미리보기 펼치기' })
  fireEvent.pointerDown(foldedHandle, { clientY: 650, pointerId: 2, button: 0 })
  fireEvent.pointerMove(foldedHandle, { clientY: 500, pointerId: 2 })
  fireEvent.pointerUp(foldedHandle, { clientY: 500, pointerId: 2 })
  expect(screen.getByRole('button', { name: '단지 상세 펼치기' })).toBeVisible()
  expect(expand).not.toHaveBeenCalled()
})

it('아래 방향 드래그 취소와 다른 포인터는 미리보기를 접지 않는다', () => {
  const { handle } = renderSummary()
  fireEvent.pointerDown(handle, { clientY: 500, pointerId: 1, button: 0 })
  fireEvent.pointerMove(handle, { clientY: 700, pointerId: 2 })
  fireEvent.pointerUp(handle, { clientY: 700, pointerId: 2 })
  fireEvent.pointerCancel(handle, { pointerId: 1 })
  expect(screen.getByRole('button', { name: '단지 상세 펼치기' })).toBeVisible()
})

it('접기 버튼과 방향키로 미리보기를 접고 다시 펼칠 수 있다', () => {
  const { close, handle } = renderSummary()
  fireEvent.click(screen.getByRole('button', { name: '단지 미리보기 접기' }))
  expect(screen.queryByRole('button', { name: '단지 상세 펼치기' })).not.toBeInTheDocument()
  fireEvent.keyDown(handle, { key: 'ArrowUp' })
  expect(screen.getByRole('button', { name: '단지 상세 펼치기' })).toBeVisible()
  fireEvent.keyDown(handle, { key: 'ArrowDown' })
  expect(screen.queryByRole('button', { name: '단지 상세 펼치기' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '단지 요약 닫기' }))
  expect(close).toHaveBeenCalledOnce()
})

it('확대한 상세를 아래로 끌면 닫지 않고 미리보기로 돌아간다', () => {
  const back = vi.fn()
  const close = vi.fn()
  render(<HousingComplexSummarySheet recent={undefined} detail={null} status="ready" expanded
    onExpand={() => {}} onBackToSummary={back} onClose={close} onRetry={() => {}}>
    <p>선택한 단지의 상세 내용</p>
  </HousingComplexSummarySheet>)
  const handle = screen.getByRole('button', { name: '단지 미리보기로 접기' })
  fireEvent.pointerDown(handle, { clientY: 20, pointerId: 1, button: 0 })
  fireEvent.pointerMove(handle, { clientY: 180, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 180, pointerId: 1 })
  fireEvent.click(handle)
  expect(back).toHaveBeenCalledOnce()
  expect(close).not.toHaveBeenCalled()
})
