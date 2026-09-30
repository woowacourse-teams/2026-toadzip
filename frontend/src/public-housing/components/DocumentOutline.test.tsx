import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import DocumentOutline, { type DocumentOutlineEntry } from './DocumentOutline.tsx'

const entries: readonly DocumentOutlineEntry[] = [
  { id: 'overview', title: '공급 개요', depth: 0, pageNumber: 1 },
  { id: 'eligibility', title: '신청 자격', depth: 1, pageNumber: 4 },
  { id: 'schedule', title: '신청 일정', depth: 0, pageNumber: 8 },
]

it('추출된 목차가 없으면 토글과 탐색 영역을 표시하지 않는다', () => {
  const { container } = render(<DocumentOutline entries={[]} activeId={null} onNavigate={vi.fn()} />)
  expect(container).toBeEmptyDOMElement()
})

it('접힌 패널의 제목은 전환을 위해 유지하지만 탐색과 포커스에서는 제외한다', () => {
  const { container } = render(<DocumentOutline entries={entries} activeId={null} onNavigate={vi.fn()} />)
  const toggle = screen.getByRole('button', { name: '목차' })
  const panel = container.querySelector('nav[aria-label="문서 목차"]')
  expect(panel).toHaveAttribute('aria-hidden', 'true')
  expect(panel).toHaveAttribute('inert')
  expect(panel).toHaveTextContent('공급 개요')
  expect(screen.queryByRole('button', { name: /공급 개요/ })).not.toBeInTheDocument()

  fireEvent.mouseEnter(toggle)
  expect(panel).toHaveAttribute('aria-hidden', 'false')
  expect(panel).not.toHaveAttribute('inert')
  expect(screen.getByRole('button', { name: /공급 개요/ })).toBeInTheDocument()

  fireEvent.mouseLeave(toggle)
  expect(panel).toHaveAttribute('inert')
  expect(panel).toHaveTextContent('공급 개요')
})

it('토글을 누르거나 키보드 포커스와 마우스를 올리면 목차를 열 수 있다', () => {
  render(<DocumentOutline entries={entries} activeId={null} onNavigate={vi.fn()} />)
  const toggle = screen.getByRole('button', { name: '목차' })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()

  fireEvent.mouseEnter(toggle)
  expect(screen.getByRole('navigation', { name: '문서 목차' })).toBeInTheDocument()
  fireEvent.mouseLeave(toggle)
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()

  fireEvent.focus(toggle)
  expect(toggle).toHaveAttribute('aria-expanded', 'true')
  fireEvent.blur(toggle)
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()

  fireEvent.click(toggle)
  expect(toggle).toHaveAttribute('aria-expanded', 'true')
  expect(screen.getByRole('navigation', { name: '문서 목차' })).toHaveAttribute('id', toggle.getAttribute('aria-controls'))
  fireEvent.click(toggle)
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
})

it('원문 제목, 계층, 현재 위치를 보여주고 선택한 항목의 id로 이동한다', () => {
  const onNavigate = vi.fn()
  render(<DocumentOutline entries={entries} activeId="eligibility" onNavigate={onNavigate} />)
  fireEvent.click(screen.getByRole('button', { name: '목차' }))

  expect(screen.getByRole('button', { name: /공급 개요/ })).not.toHaveAttribute('aria-current')
  const current = screen.getByRole('button', { name: /신청 자격/ })
  expect(current).toHaveAttribute('aria-current', 'location')
  expect(current).toHaveAttribute('data-depth', '1')
  expect(current).toHaveTextContent('4쪽')
  fireEvent.click(screen.getByRole('button', { name: /신청 일정/ }))
  expect(onNavigate).toHaveBeenCalledWith('schedule')
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()
})

it('Escape는 목차만 닫고 토글에 포커스를 돌리며 상위 모달로 전파하지 않는다', () => {
  const onModalEscape = vi.fn()
  render(<div onKeyDown={onModalEscape}><DocumentOutline entries={entries} activeId={null} onNavigate={vi.fn()} /></div>)
  const toggle = screen.getByRole('button', { name: '목차' })
  fireEvent.click(toggle)
  const item = screen.getByRole('button', { name: /공급 개요/ })
  item.focus()
  fireEvent.keyDown(item, { key: 'Escape' })
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()
  expect(toggle).toHaveFocus()
  expect(onModalEscape).not.toHaveBeenCalled()
})

it('Escape로 닫은 뒤 포인터가 떠나도 토글에 포커스가 있으면 다시 열리지 않는다', () => {
  render(<DocumentOutline entries={entries} activeId={null} onNavigate={vi.fn()} />)
  const toggle = screen.getByRole('button', { name: '목차' })
  fireEvent.mouseEnter(toggle)
  fireEvent.focus(toggle)
  fireEvent.keyDown(toggle, { key: 'Escape' })
  fireEvent.mouseLeave(toggle)
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()
})

it('고정하지 않은 목차는 포인터가 떠나면 닫고, 고정한 목차는 유지한다', () => {
  render(<DocumentOutline entries={entries} activeId={null} onNavigate={vi.fn()} />)
  const toggle = screen.getByRole('button', { name: '목차' })
  fireEvent.mouseEnter(toggle)
  expect(screen.getByRole('navigation', { name: '문서 목차' })).toBeInTheDocument()
  fireEvent.mouseLeave(toggle)
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()
  fireEvent.click(toggle)
  fireEvent.mouseLeave(toggle)
  expect(screen.getByRole('navigation', { name: '문서 목차' })).toBeInTheDocument()
})

it('항목 이동 뒤 문서가 포커스를 가져가도 포인터가 머문 목차는 다시 열리지 않는다', () => {
  render(<><button>문서</button><DocumentOutline entries={entries} activeId={null} onNavigate={() => screen.getByRole('button', { name: '문서' }).focus()} /></>)
  const toggle = screen.getByRole('button', { name: '목차' })
  fireEvent.mouseEnter(toggle)
  fireEvent.click(toggle)
  fireEvent.click(screen.getByRole('button', { name: /공급 개요/ }))
  expect(screen.getByRole('button', { name: '문서' })).toHaveFocus()
  expect(screen.queryByRole('navigation', { name: '문서 목차' })).not.toBeInTheDocument()
})
