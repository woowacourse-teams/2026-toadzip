import { fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'

describe('Button', () => {
  it('기본 버튼은 제출하지 않고 명시적 submit만 폼을 제출한다', () => {
    const submitted = vi.fn()
    render(
      <form onSubmit={(event) => { event.preventDefault(); submitted() }}>
        <Button>닫기</Button>
        <Button type="submit">적용</Button>
      </form>,
    )
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(submitted).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(submitted).toHaveBeenCalledTimes(1)
  })

  it('disabled와 접근성 설명을 보존하고 비활성 동작을 실행하지 않는다', () => {
    const clicked = vi.fn()
    render(<>
      <p id="reason">조건을 확인해 주세요.</p>
      <Button disabled onClick={clicked} aria-describedby="reason">적용</Button>
    </>)
    const button = screen.getByRole('button', { name: '적용' })
    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription('조건을 확인해 주세요.')
    fireEvent.click(button)
    expect(clicked).not.toHaveBeenCalled()
  })

  it('ref로 실제 버튼에 포커스를 옮길 수 있다', () => {
    const ref = createRef<HTMLButtonElement>()
    render(<Button ref={ref}>적용</Button>)
    ref.current?.focus()
    expect(screen.getByRole('button', { name: '적용' })).toHaveFocus()
  })
})
