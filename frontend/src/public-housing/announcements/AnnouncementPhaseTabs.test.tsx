import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AnnouncementPhaseTabs } from './AnnouncementPhaseTabs'

describe('AnnouncementPhaseTabs', () => {
  it('모집 중과 접수마감 탭 중 선택한 탭만 Tab 순서에 두고 목록을 가리킨다', () => {
    render(<AnnouncementPhaseTabs closed={false} panelId="announcement-list" onChange={vi.fn()} />)

    const open = screen.getByRole('tab', { name: '모집 중' })
    const closed = screen.getByRole('tab', { name: '접수마감' })
    expect(screen.getByRole('tablist', { name: '공고 진행 상태' })).toBeVisible()
    expect(open).toHaveAttribute('aria-selected', 'true')
    expect(open).toHaveAttribute('tabindex', '0')
    expect(open).toHaveAttribute('aria-controls', 'announcement-list')
    expect(closed).toHaveAttribute('aria-selected', 'false')
    expect(closed).toHaveAttribute('tabindex', '-1')
    expect(closed).not.toHaveAttribute('aria-controls')
  })

  it('누르거나 방향키·Home·End로 옮기면 바로 탭을 바꾸고 포커스를 옮긴다', () => {
    const onChange = vi.fn()
    const { rerender } = render(<AnnouncementPhaseTabs closed={false} panelId="list" onChange={onChange} />)

    fireEvent.click(screen.getByRole('tab', { name: '접수마감' }))
    expect(onChange).toHaveBeenLastCalledWith(true)

    fireEvent.keyDown(screen.getByRole('tab', { name: '모집 중' }), { key: 'ArrowRight' })
    expect(onChange).toHaveBeenLastCalledWith(true)
    expect(screen.getByRole('tab', { name: '접수마감' })).toHaveFocus()

    rerender(<AnnouncementPhaseTabs closed panelId="list" onChange={onChange} />)
    fireEvent.keyDown(screen.getByRole('tab', { name: '접수마감' }), { key: 'Home' })
    expect(onChange).toHaveBeenLastCalledWith(false)
    expect(screen.getByRole('tab', { name: '모집 중' })).toHaveFocus()
  })

  it('이미 선택한 탭을 다시 눌러도 바꾸지 않는다', () => {
    const onChange = vi.fn()
    render(<AnnouncementPhaseTabs closed panelId="list" onChange={onChange} />)

    fireEvent.click(screen.getByRole('tab', { name: '접수마감' }))

    expect(onChange).not.toHaveBeenCalled()
  })
})
