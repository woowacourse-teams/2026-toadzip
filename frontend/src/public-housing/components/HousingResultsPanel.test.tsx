import { act, fireEvent, render, screen } from '@testing-library/react'
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
    return new DOMRect(0, 0, 390, [76, 400, 716][Number(this.dataset.snap ?? 1)])
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

it('손잡이를 위로 쓸어 크게 펼치고 길게 아래로 끌면 접힌 높이로 이동한다', () => {
  renderPanel()
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 280, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 280, pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuetext', '크게 펼침')
  fireEvent.pointerDown(handle, { clientY: 100, button: 0, pointerId: 2 })
  fireEvent.pointerMove(handle, { clientY: 750, pointerId: 2 })
  fireEvent.pointerUp(handle, { clientY: 750, pointerId: 2 })
  expect(handle).toHaveAttribute('aria-valuetext', '접힘')
  expect(screen.queryByRole('button', { name: '상세 보기' })).not.toBeInTheDocument()
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

it('하단 내비게이션 높이를 제외한 영역을 기준으로 드래그 펼침 위치를 정한다', () => {
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
  expect(handle).toHaveAttribute('aria-valuetext', '크게 펼침')
})

it('모바일에서 접어도 데스크톱으로 넓히면 같은 목록 내용을 다시 표시한다', () => {
  renderPanel()
  const opener = screen.getByRole('button', { name: '상세 보기' })
  fireEvent.keyDown(screen.getByRole('slider', { name: '목록 높이' }), { key: 'Home' })
  act(() => { mobile = false; resize?.() })
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '상세 보기' })).toBe(opener)
})
