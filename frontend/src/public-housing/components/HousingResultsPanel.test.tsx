import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { HousingResultsPanel } from './HousingResultsPanel'

let resize: (() => void) | undefined
let mobile = true

beforeEach(() => {
  mobile = true
  vi.stubGlobal('innerHeight', 800)
  vi.stubGlobal('matchMedia', () => ({
    get matches() { return mobile },
    addEventListener: (_: string, callback: () => void) => { resize = callback },
    removeEventListener: () => { resize = undefined },
  }))
  vi.stubGlobal('PointerEvent', class extends MouseEvent {
    pointerId: number
    isPrimary: boolean
    constructor(type: string, options: PointerEventInit = {}) {
      super(type, options)
      this.pointerId = options.pointerId ?? 1
      this.isPrimary = options.isPrimary ?? true
    }
  })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const percent = Number.parseFloat(this.style.getPropertyValue('--sheet-height')) || 50
    return new DOMRect(0, 0, 390, Math.min(percent * 8, 792))
  })
  Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', { configurable: true, value: vi.fn() })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  delete (HTMLElement.prototype as Partial<HTMLElement>).setPointerCapture
})

function renderPanel() {
  return render(<HousingResultsPanel title="단지 목록" visible onCollapse={() => {}}>
    <section aria-label="단지 카드"><button>상세 보기</button></section>
  </HousingResultsPanel>)
}

function DismissiblePanel() {
  const [visible, setVisible] = useState(true)
  return <>
    <button onClick={() => setVisible(true)}>공고 열기</button>
    <HousingResultsPanel title="공고 목록" visible={visible} onCollapse={() => setVisible(false)}>
      <button>공고 상세 보기</button>
    </HousingResultsPanel>
  </>
}

it('목록을 아래로 드래그하면 닫히고 재열면 절반 높이에서 내용을 보여준다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 660, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 660, pointerId: 1 })
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '공고 열기' }))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '절반 펼침')
  expect(screen.getByRole('button', { name: '공고 상세 보기' })).toBeVisible()
})

it('작은 움직임이나 취소된 드래그는 목록을 닫지 않는다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 608, pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuetext', '절반 펼침')
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 2 })
  fireEvent.pointerMove(handle, { clientY: 680, pointerId: 2 })
  fireEvent.pointerCancel(handle, { clientY: 680, pointerId: 2 })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '절반 펼침')
})

it('제목 영역에서도 드래그해 펼칠 수 있고 키보드로 최대 높이와 절반 높이를 전환한다', () => {
  renderPanel()
  const header = screen.getByRole('heading', { name: '단지 목록' })
  fireEvent.pointerDown(header, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(header, { clientY: 0, pointerId: 1 })
  fireEvent.pointerUp(header, { clientY: 0, pointerId: 1 })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '크게 펼침')
  fireEvent.keyDown(screen.getByRole('slider'), { key: 'Enter' })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '절반 펼침')
  fireEvent.keyDown(screen.getByRole('slider'), { key: 'End' })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '크게 펼침')
})

it('절반 높이 목록은 아래 방향키로 완전히 닫을 수 있다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.keyDown(handle, { key: 'ArrowDown' })
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
})

it('손잡이를 위로 쓸어 크게 펼치고 길게 아래로 끌면 완전히 닫힌다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 0, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 0, pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuetext', '크게 펼침')
  fireEvent.pointerDown(handle, { clientY: 100, button: 0, pointerId: 2 })
  fireEvent.pointerMove(handle, { clientY: 750, pointerId: 2 })
  fireEvent.pointerUp(handle, { clientY: 750, pointerId: 2 })
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '공고 상세 보기' })).not.toBeInTheDocument()
})

it('취소된 제스처는 이전 높이를 유지하고 다른 손가락의 움직임은 무시한다', () => {
  renderPanel()
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 100, pointerId: 2 })
  expect(handle.closest('aside')).not.toHaveAttribute('data-dragging')
  fireEvent.pointerMove(handle, { clientY: 100, pointerId: 1 })
  fireEvent.pointerCancel(handle, { pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuetext', '절반 펼침')
  expect(handle.closest('aside')).not.toHaveAttribute('data-dragging')
})

it('하단 내비게이션 위에서 놓은 높이를 고정한다', () => {
  renderPanel()
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  const panel = handle.closest('aside')
  if (!panel) throw new Error('목록 패널이 없습니다')
  panel.style.bottom = '64px'
  const down = new PointerEvent('pointerdown', { bubbles: true, clientY: 600, button: 0, pointerId: 1 })
  const up = new PointerEvent('pointerup', { bubbles: true, clientY: 460, pointerId: 1 })
  Object.defineProperty(down, 'timeStamp', { value: 100 })
  Object.defineProperty(up, 'timeStamp', { value: 1100 })
  fireEvent(handle, down)
  fireEvent.pointerMove(handle, { clientY: 460, pointerId: 1 })
  fireEvent(handle, up)
  expect(handle).toHaveAttribute('aria-valuenow', '67.5')
})

it('모바일에서 높이를 바꿔도 데스크톱으로 넓히면 같은 목록 내용을 표시한다', () => {
  renderPanel()
  const opener = screen.getByRole('button', { name: '상세 보기' })
  fireEvent.keyDown(screen.getByRole('slider', { name: '목록 높이' }), { key: 'ArrowUp' })
  act(() => { mobile = false; resize?.() })
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '상세 보기' })).toBe(opener)
})

it('크게 보기와 절반 보기 버튼을 표시하지 않는다', () => {
  renderPanel()
  expect(screen.queryByRole('button', { name: '목록 크게 펼치기' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '목록 절반으로 줄이기' })).not.toBeInTheDocument()
})

it('절반보다 높게 끌면 놓은 62.5% 높이를 유지한다', () => {
  renderPanel()
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 500, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 500, pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuenow', '62.5')
  expect(handle).toHaveAttribute('aria-valuetext', '화면 높이의 63%')
  fireEvent.pointerDown(handle, { clientY: 500, button: 0, pointerId: 2 })
  fireEvent.pointerMove(handle, { clientY: 400, pointerId: 2 })
  fireEvent.pointerUp(handle, { clientY: 400, pointerId: 2 })
  expect(handle).toHaveAttribute('aria-valuenow', '75')
})

it.each([40, 200, 380])('절반에서 %ipx 내리면 튕기지 않고 완전히 닫힌다', (distance) => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 400, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 400 + distance, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 400 + distance, pointerId: 1 })
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '공고 상세 보기' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '공고 열기' }))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '절반 펼침')
})


it('드래그가 아닌 접기 버튼도 손잡이까지 완전히 숨긴다', () => {
  render(<DismissiblePanel />)
  fireEvent.click(screen.getByRole('button', { name: '목록 접기' }))
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '공고 열기' }))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '절반 펼침')
})
