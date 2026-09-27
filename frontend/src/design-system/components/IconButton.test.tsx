import { fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { IconButton } from './IconButton'

describe('IconButton', () => {
  it('label과 ref를 실제 버튼에 연결하고 기본 동작으로 폼을 제출하지 않는다', () => {
    const ref = createRef<HTMLButtonElement>()
    const closed = vi.fn()
    const submitted = vi.fn()
    render(
      <form onSubmit={(event) => { event.preventDefault(); submitted() }}>
        <IconButton ref={ref} label="필터 닫기" onClick={closed}>
          <svg aria-hidden="true" focusable="false" />
        </IconButton>
      </form>,
    )
    const button = screen.getByRole('button', { name: '필터 닫기' })
    ref.current?.focus()
    expect(button).toHaveFocus()
    fireEvent.click(button)
    expect(closed).toHaveBeenCalledTimes(1)
    expect(submitted).not.toHaveBeenCalled()
  })

  it('disabled 닫기 버튼은 동작을 실행하지 않는다', () => {
    const closed = vi.fn()
    render(<IconButton label="필터 닫기" disabled onClick={closed}>×</IconButton>)
    fireEvent.click(screen.getByRole('button', { name: '필터 닫기' }))
    expect(closed).not.toHaveBeenCalled()
  })
})
