import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { BookmarkProvider } from '../bookmarks/BookmarkProvider'
import type { BookmarkItem, BookmarkRepository } from '../bookmarks/bookmarkRepository'
import { MyPageView } from './MyPageView'

const first: BookmarkItem = { type: 'COMPLEX', targetId: '1', name: '첫 단지', createdAt: '2026-10-27T00:00:00Z', available: true, address: '서울', status: null, applicationStartDate: null, applicationEndDate: null }
const second: BookmarkItem = { ...first, targetId: '2', name: '둘째 단지' }
const notice: BookmarkItem = { ...first, type: 'ANNOUNCEMENT', name: '같은 번호 공고' }
async function setup(repository: BookmarkRepository) {
  render(<MemoryRouter initialEntries={['/mypage/bookmarks']}><BookmarkProvider repository={repository} loadUser={async () => ({ id: 7, email: null })}>
    <MyPageView onOpenComplex={vi.fn()} onOpenAnnouncement={vi.fn()} onBrowseAnnouncements={vi.fn()} onClose={vi.fn()} />
  </BookmarkProvider></MemoryRouter>)
  const saved = within(await screen.findByRole('region', { name: '북마크 관리' }))
  await saved.findByText('첫 단지')
  return saved
}

it('부분·전체 선택을 지원하고 취소와 탭 변경은 북마크를 보존한다', async () => {
  const repository = { list: vi.fn().mockResolvedValue([first, second, notice]), remove: vi.fn(), save: vi.fn() }
  const saved = await setup(repository)
  expect(saved.queryByRole('button', { name: /북마크 삭제/ })).not.toBeInTheDocument()
  fireEvent.click(saved.getByRole('checkbox', { name: '첫 단지 선택' }))
  expect(saved.getByRole('checkbox', { name: '전체 선택' })).toBePartiallyChecked()
  expect(saved.getByRole('button', { name: '북마크 삭제 (1)' })).toBeEnabled()
  expect(saved.queryByRole('button', { name: '북마크 삭제 (2)' })).not.toBeInTheDocument()
  fireEvent.click(saved.getByRole('checkbox', { name: '전체 선택' }))
  expect(saved.getByRole('checkbox', { name: '둘째 단지 선택' })).toBeChecked()
  fireEvent.click(saved.getByRole('button', { name: '북마크 삭제 (2)' }))
  expect(saved.getByRole('group', { name: '북마크 해제 확인' })).toHaveTextContent('단지 북마크 2개')
  fireEvent.click(saved.getByRole('button', { name: '취소' }))
  expect(repository.remove).not.toHaveBeenCalled()
  fireEvent.click(saved.getByRole('checkbox', { name: '둘째 단지 선택' }))
  expect(saved.getByRole('button', { name: '북마크 삭제 (1)' })).toBeEnabled()
  fireEvent.click(saved.getByRole('checkbox', { name: '첫 단지 선택' }))
  expect(saved.queryByRole('button', { name: /북마크 삭제/ })).not.toBeInTheDocument()
  fireEvent.click(saved.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(saved.getByRole('tab', { name: '공고 1' }))
  expect(saved.getByRole('checkbox', { name: '같은 번호 공고 선택' })).not.toBeChecked()
  fireEvent.click(saved.getByRole('tab', { name: '단지 2' }))
  expect(saved.getByRole('checkbox', { name: '전체 선택' })).not.toBeChecked()
  expect(saved.queryByRole('button', { name: /북마크 삭제/ })).not.toBeInTheDocument()
})

it('선택한 북마크만 즉시 숨기고 같은 ID의 다른 분류는 보존한다', async () => {
  let items = [first, second, notice]
  let finish!: () => void
  const repository = { list: vi.fn(async () => items), save: vi.fn(), remove: vi.fn(() => new Promise<void>(resolve => {
    finish = () => { items = [second, notice]; resolve() }
  })) }
  const saved = await setup(repository)
  fireEvent.click(saved.getByRole('checkbox', { name: '첫 단지 선택' }))
  fireEvent.click(saved.getByRole('button', { name: '북마크 삭제 (1)' }))
  fireEvent.click(saved.getByRole('button', { name: '북마크 해제' }))
  expect(saved.queryByText('첫 단지')).not.toBeInTheDocument()
  expect(saved.getByText('둘째 단지')).toBeVisible()
  expect(saved.getByRole('tab', { name: '단지 1' })).toHaveFocus()
  expect(saved.queryByRole('button', { name: /북마크 삭제/ })).not.toBeInTheDocument()
  expect(repository.remove).toHaveBeenCalledExactlyOnceWith('COMPLEX', '1')
  await act(async () => finish())
  fireEvent.click(saved.getByRole('tab', { name: '공고 1' }))
  expect(saved.getByText('같은 번호 공고')).toBeVisible()
})

it('전체 선택 해제에서 성공한 항목은 유지하고 실패한 항목만 복원한다', async () => {
  let items = [first, second, notice]
  let fail!: (error: Error) => void
  const repository = { list: vi.fn(async () => items), save: vi.fn(), remove: vi.fn(async (_type: string, id: string) => {
    if (id === '2') return new Promise<void>((_resolve, reject) => { fail = reject })
    items = items.filter(item => !(item.type === 'COMPLEX' && item.targetId === id))
  }) }
  const saved = await setup(repository)
  fireEvent.click(saved.getByRole('checkbox', { name: '전체 선택' }))
  fireEvent.click(saved.getByRole('button', { name: '북마크 삭제 (2)' }))
  fireEvent.click(saved.getByRole('button', { name: '북마크 해제' }))
  expect(saved.queryByText('첫 단지')).not.toBeInTheDocument()
  expect(saved.queryByText('둘째 단지')).not.toBeInTheDocument()
  await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(2))
  await act(async () => fail(new Error('offline')))
  expect(saved.queryByText('첫 단지')).not.toBeInTheDocument()
  expect(saved.getByText('둘째 단지')).toBeVisible()
  expect(saved.getByRole('alert')).toHaveTextContent('다시 시도')
  expect(saved.getByRole('tab', { name: '공고 1' })).toBeInTheDocument()
})
