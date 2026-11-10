import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConditionNudgeDialog } from './ConditionNudgeDialog'

describe('ConditionNudgeDialog', () => {
  it('asks about this announcement and shows the result example', () => {
    const onStart = vi.fn()
    const onLater = vi.fn()
    render(<ConditionNudgeDialog variant="detail" draftExists={false} onStart={onStart} onLater={onLater} />)
    const dialog = screen.getByRole('dialog', { name: '이 공고, 나도 신청할 수 있을까?' })
    expect(within(dialog).getByText('결과는 이렇게 보여요')).toBeInTheDocument()
    expect(within(dialog).getAllByRole('listitem', { hidden: true }).map((item) => item.textContent)).toEqual(['청년지원 가능', '신혼부부입력 필요', '고령자미충족'])
    fireEvent.click(within(dialog).getByRole('button', { name: '지금 입력해 보기' }))
    expect(onStart).toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: '다음에 할게요' }))
    expect(onLater).toHaveBeenCalledTimes(1)
    expect(within(dialog).getByText('마이페이지 > 내 조건 관리에서도 입력할 수 있어요')).toBeInTheDocument()
  })

  it('highlights 지원 가능한 공고 in the list variant', () => {
    render(<ConditionNudgeDialog variant="list" draftExists={false} onStart={vi.fn()} onLater={vi.fn()} />)
    const heading = screen.getByRole('heading', { name: '지원 가능한 공고만 모아 볼 수 있어요' })
    expect(within(heading).getByText('지원 가능한 공고').tagName).toBe('EM')
  })

  it('offers to resume a draft', () => {
    render(<ConditionNudgeDialog variant="detail" draftExists onStart={vi.fn()} onLater={vi.fn()} />)
    expect(screen.getByRole('heading', { name: '작성하던 내 조건이 있어요. 이어서 입력할까요?' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '이어서 입력하기' })).toBeInTheDocument()
  })

  it('closes as later from the close button', () => {
    const onLater = vi.fn()
    render(<ConditionNudgeDialog variant="afterLogin" draftExists={false} onStart={vi.fn()} onLater={onLater} />)
    expect(screen.getByRole('heading', { name: '로그인했어요! 이 공고, 나도 신청할 수 있을까요?' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(onLater).toHaveBeenCalled()
  })
})
