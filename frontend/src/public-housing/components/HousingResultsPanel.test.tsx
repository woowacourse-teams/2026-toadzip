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

it('목록을 15% 아래까지 내리면 닫히고 재열면 절반 높이에서 내용을 보여준다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 900, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 900, pointerId: 1 })
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

it('아래 방향키로 절반에서 25%까지 줄인 뒤 한 번 더 내리면 닫힌다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  for (const height of ['40', '30', '25']) {
    fireEvent.keyDown(handle, { key: 'ArrowDown' })
    expect(handle).toHaveAttribute('aria-valuenow', height)
    expect(screen.getByRole('button', { name: '공고 상세 보기' })).toBeVisible()
  }
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
  fireEvent.pointerMove(handle, { clientY: 800, pointerId: 2 })
  fireEvent.pointerUp(handle, { clientY: 800, pointerId: 2 })
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

it.each([280, 300, 380])('절반에서 %ipx 내려 15% 이하가 되면 완전히 닫힌다', (distance) => {
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


it.each([
  [40, '45'], [200, '25'], [240, '25'], [279, '25'],
])('절반에서 %ipx 내려도 닫히지 않고 %s%% 높이를 유지한다', (distance, expectedHeight) => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  const detail = screen.getByRole('button', { name: '공고 상세 보기' })
  const content = detail.parentElement
  if (!content) throw new Error('목록 내용이 없습니다')
  content.scrollTop = 160
  fireEvent.pointerDown(handle, { clientY: 400, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 400 + distance, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 400 + distance, pointerId: 1 })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', expectedHeight)
  expect(screen.getByRole('button', { name: '공고 상세 보기' })).toBe(detail)
  expect(content.scrollTop).toBe(160)
})

it('25%에서 살짝 내리면 유지하고 다시 올려 원하는 높이로 펼칠 수 있다', () => {
  render(<DismissiblePanel />)
  const handle = screen.getByRole('slider', { name: '목록 높이' })
  fireEvent.pointerDown(handle, { clientY: 400, button: 0, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 600, pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuenow', '25')
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 2 })
  fireEvent.pointerUp(handle, { clientY: 620, pointerId: 2 })
  expect(handle).toHaveAttribute('aria-valuenow', '25')
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 3 })
  fireEvent.pointerUp(handle, { clientY: 500, pointerId: 3 })
  expect(handle).toHaveAttribute('aria-valuenow', '37.5')
})

it('드래그가 아닌 접기 버튼도 손잡이까지 완전히 숨긴다', () => {
  render(<DismissiblePanel />)
  fireEvent.click(screen.getByRole('button', { name: '목록 접기' }))
  expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '공고 열기' }))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '절반 펼침')
})

it('가로로 직접 접근하면 목록을 크게 펼쳐 내용을 읽을 공간을 확보한다', () => {
  vi.stubGlobal('innerWidth', 844)
  vi.stubGlobal('innerHeight', 390)
  renderPanel()
  expect(screen.getByRole('slider', { name: '목록 높이' })).toHaveAttribute('aria-valuetext', '크게 펼침')
})

it('회전 후 세로로 돌아오면 사용자가 정한 목록 높이와 같은 내용·포커스를 보존한다', () => {
  vi.stubGlobal('innerWidth', 390)
  const { container } = renderPanel()
  fireEvent.keyDown(screen.getByRole('slider'), { key: 'ArrowUp' })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '60')
  const detail = screen.getByRole('button', { name: '상세 보기' })
  const content = container.querySelector('.housing-results__content')!
  content.scrollTop = 120
  detail.focus()

  vi.stubGlobal('innerWidth', 844)
  vi.stubGlobal('innerHeight', 390)
  fireEvent(window, new Event('resize'))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '100')
  expect(screen.getByRole('button', { name: '상세 보기' })).toBe(detail)
  expect(detail).toHaveFocus()

  vi.stubGlobal('innerWidth', 390)
  vi.stubGlobal('innerHeight', 800)
  fireEvent(window, new Event('resize'))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '60')
  expect(content.scrollTop).toBe(120)
  expect(detail).toHaveFocus()
})

it('가로에서 높이를 줄이고 접어도 다시 열면 크게 펼친다', () => {
  vi.stubGlobal('innerWidth', 844)
  vi.stubGlobal('innerHeight', 390)
  render(<DismissiblePanel />)
  fireEvent.keyDown(screen.getByRole('slider'), { key: 'Enter' })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '50')
  fireEvent.keyDown(screen.getByRole('slider'), { key: 'Home' })
  fireEvent.click(screen.getByRole('button', { name: '공고 열기' }))
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '100')
})

it('드래그 도중 화면이 회전하면 이전 방향의 드래그 좌표를 버린다', () => {
  vi.stubGlobal('innerWidth', 390)
  const { container } = renderPanel()
  const handle = screen.getByRole('slider')
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 500, pointerId: 1 })
  expect(container.querySelector('[data-dragging="true"]')).toBeInTheDocument()
  vi.stubGlobal('innerWidth', 844)
  vi.stubGlobal('innerHeight', 390)
  fireEvent(window, new Event('resize'))
  expect(container.querySelector('[data-dragging="true"]')).not.toBeInTheDocument()
  fireEvent.pointerUp(handle, { clientY: 700, pointerId: 1 })
  expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '크게 펼침')
})

it('높이가 충분한 터치 태블릿도 방향별 높이를 보존하고 회전 중 드래그를 취소한다', () => {
  vi.stubGlobal('innerWidth', 768)
  vi.stubGlobal('innerHeight', 1024)
  renderPanel()
  const handle = screen.getByRole('slider')
  fireEvent.keyDown(handle, { key: 'End' })
  fireEvent.pointerDown(handle, { clientY: 600, button: 0, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: 550, pointerId: 1 })
  expect(handle.closest('aside')).toHaveAttribute('data-dragging', 'true')
  vi.stubGlobal('innerWidth', 1024)
  vi.stubGlobal('innerHeight', 768)
  fireEvent(window, new Event('resize'))
  expect(handle.closest('aside')).not.toHaveAttribute('data-dragging')
  expect(handle).toHaveAttribute('aria-valuenow', '50')
  fireEvent.pointerUp(handle, { clientY: 700, pointerId: 1 })
  vi.stubGlobal('innerWidth', 768)
  vi.stubGlobal('innerHeight', 1024)
  fireEvent(window, new Event('resize'))
  expect(handle).toHaveAttribute('aria-valuenow', '100')
})


it('1/4 높이에서 가로로 회전했다가 돌아와도 세로 높이와 스크롤을 유지한다', () => {
  vi.stubGlobal('innerWidth', 390)
  const { container } = renderPanel()
  const handle = screen.getByRole('slider')
  fireEvent.pointerDown(handle, { clientY: 400, button: 0, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: 600, pointerId: 1 })
  expect(handle).toHaveAttribute('aria-valuenow', '25')
  const content = container.querySelector('.housing-results__content')!
  content.scrollTop = 120

  vi.stubGlobal('innerWidth', 844)
  vi.stubGlobal('innerHeight', 390)
  fireEvent(window, new Event('resize'))
  expect(handle).toHaveAttribute('aria-valuenow', '100')
  vi.stubGlobal('innerWidth', 390)
  vi.stubGlobal('innerHeight', 800)
  fireEvent(window, new Event('resize'))
  expect(handle).toHaveAttribute('aria-valuenow', '25')
  expect(content.scrollTop).toBe(120)
})
