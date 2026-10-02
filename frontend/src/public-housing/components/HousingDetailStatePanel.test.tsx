import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { HousingDetailStatePanel } from './HousingDetailStatePanel.tsx'

describe.each([
  { kind: 'complex' as const, label: '단지', missing: '단지를 찾을 수 없습니다.' },
  { kind: 'announcement' as const, label: '공고', missing: '공고를 찾을 수 없습니다.' },
])('$label 상세 상태 패널', ({ kind, label, missing }) => {
  const props = { kind, id: '17', errorMessage: null, onClose: vi.fn(), onRetry: vi.fn() }

  it('로딩을 알리고 제목에 포커스하며 재시도는 표시하지 않는다', () => {
    render(<HousingDetailStatePanel {...props} status="loading" />)
    expect(screen.getByRole('complementary', { name: `${label} 상세 정보` })).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent(`${label} 상세를 불러오고 있습니다.`)
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('존재하지 않는 상세에는 오류 설명만 제공한다', () => {
    render(<HousingDetailStatePanel {...props} status="not-found" />)
    expect(screen.getByRole('alert')).toHaveTextContent(missing)
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('요청 실패를 설명하고 사용자 재시도를 전달한다', () => {
    const retry = vi.fn()
    render(<HousingDetailStatePanel {...props} status="error" errorMessage="연결을 확인해 주세요." onRetry={retry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('연결을 확인해 주세요.')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  it('Escape는 상위로 전달하지 않고 상세를 한 번 닫는다', () => {
    const close = vi.fn()
    const outerKey = vi.fn()
    render(<div onKeyDown={outerKey}><HousingDetailStatePanel {...props} status="error" onClose={close} /></div>)
    fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' })
    expect(close).toHaveBeenCalledOnce()
    expect(outerKey).not.toHaveBeenCalled()
  })

  it('상세 ID와 상태가 바뀌면 페이지 스크롤 없이 포커스를 복원한다', () => {
    const { rerender } = render(<HousingDetailStatePanel {...props} status="loading" />)
    const panel = screen.getByRole('complementary')
    const focus = vi.spyOn(panel, 'focus')
    screen.getByRole('button', { name: `${label} 상세 닫기` }).focus()
    rerender(<HousingDetailStatePanel {...props} id="18" status="error" />)
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    expect(panel).toHaveFocus()
    focus.mockRestore()
  })
})
