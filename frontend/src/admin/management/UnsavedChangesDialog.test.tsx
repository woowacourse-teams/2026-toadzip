import { fireEvent, render, screen } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes } from 'react-router'
import { expect, it, vi } from 'vitest'
import { UnsavedChangesDialog } from './UnsavedChangesDialog'
import { useUnsavedChanges } from './useUnsavedChanges'

function Editor() {
  useUnsavedChanges(true)
  return <><p>편집 중</p><Link to="/other">다른 화면</Link></>
}

function renderEditor() {
  render(<MemoryRouter initialEntries={['/edit']}>
    <Routes><Route path="/edit" element={<Editor />} /><Route path="/other" element={<p>다른 화면 도착</p>} /></Routes>
    <UnsavedChangesDialog />
  </MemoryRouter>)
}

it('편집 중 이동은 앱 확인 창으로 묻고, 계속 수정하면 머문다', () => {
  const confirm = vi.spyOn(window, 'confirm')
  renderEditor()
  fireEvent.click(screen.getByRole('link', { name: '다른 화면' }))
  expect(screen.getByRole('dialog', { name: '저장하지 않은 변경사항이 있습니다' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '계속 수정' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByText('편집 중')).toBeVisible()
  expect(confirm).not.toHaveBeenCalled()
  confirm.mockRestore()
})

it('저장하지 않고 이동을 고르면 원래 누른 링크로 이동한다', () => {
  renderEditor()
  fireEvent.click(screen.getByRole('link', { name: '다른 화면' }))
  fireEvent.click(screen.getByRole('button', { name: '저장하지 않고 이동' }))
  expect(screen.getByText('다른 화면 도착')).toBeVisible()
})
