import { act, fireEvent, render, screen } from '@testing-library/react'
import { createPortal } from 'react-dom'
import type { ComponentProps } from 'react'
import { useMobileViewport } from './useMobileViewport'
import { afterEach, expect, it, vi } from 'vitest'
import { HousingDetailOverlay } from './HousingDetailOverlay'

function ResponsiveDetail(props: Omit<ComponentProps<typeof HousingDetailOverlay>, 'mobile'>) {
  return <HousingDetailOverlay {...props} mobile={useMobileViewport()} />
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('모바일로 전환할 때 열린 하위 모달과 입력 포커스를 상세 위에 유지한다', () => {
  let mobile = false
  let resize: (() => void) | undefined
  vi.stubGlobal('matchMedia', () => ({ get matches() { return mobile },
    addEventListener: (_: string, callback: () => void) => { resize = callback }, removeEventListener: () => {},
  }))
  const shown: HTMLDialogElement[] = []
  vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
    this.open = true
    shown.push(this)
  })
  render(<ResponsiveDetail label="단지 상세" onClose={() => {}}>
    {createPortal(<dialog open aria-label="첨부파일"><button>첨부 닫기</button></dialog>, document.body)}
  </ResponsiveDetail>)
  const focused = screen.getByRole('button', { name: '첨부 닫기' })
  focused.focus()
  act(() => { mobile = true; resize?.() })
  expect(shown.map((element) => element.getAttribute('aria-label'))).toEqual(['단지 상세', '첨부파일'])
  expect(focused).toHaveFocus()
})

it('첨부파일의 cancel은 상세를 유지하고 상세 자체의 cancel만 닫는다', () => {
  const closeDetail = vi.fn()
  const closeAttachment = vi.fn()
  render(<ResponsiveDetail label="단지 상세" onClose={closeDetail}>
    {createPortal(<dialog open aria-label="첨부파일" onCancel={closeAttachment} />, document.body)}
  </ResponsiveDetail>)
  fireEvent(screen.getByRole('dialog', { name: '첨부파일' }), new Event('cancel', { cancelable: true }))
  expect(closeAttachment).toHaveBeenCalledOnce()
  expect(closeDetail).not.toHaveBeenCalled()
  fireEvent(screen.getByRole('dialog', { name: '단지 상세' }), new Event('cancel', { cancelable: true }))
  expect(closeDetail).toHaveBeenCalledOnce()
})


it('상세 안의 입력 포커스는 모바일 showModal 자동 포커스 이후에도 유지한다', () => {
  let mobile = false
  let resize: (() => void) | undefined
  vi.stubGlobal('matchMedia', () => ({ get matches() { return mobile },
    addEventListener: (_: string, callback: () => void) => { resize = callback }, removeEventListener: () => {},
  }))
  vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
    this.open = true
    this.querySelector('button')?.focus()
  })
  render(<ResponsiveDetail label="단지 상세" onClose={() => {}}>
    <button>상세 닫기</button><button>임대 조건 펼치기</button>
  </ResponsiveDetail>)
  const focused = screen.getByRole('button', { name: '임대 조건 펼치기' })
  focused.focus()
  act(() => { mobile = true; resize?.() })
  expect(focused).toHaveFocus()
  act(() => { mobile = false; resize?.() })
  expect(focused).toHaveFocus()
})
