import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { BookmarkProvider } from './BookmarkProvider'
import { useBookmarks } from './BookmarkContext'
import { BookmarkButton } from './BookmarkButton'
import type { BookmarkItem, BookmarkRepository } from './bookmarkRepository'
const item: BookmarkItem = { type: 'COMPLEX', targetId: '1', name: '서울 주택', createdAt: '2026-10-26T09:00:00', available: true, address: null, status: null, applicationStartDate: null, applicationEndDate: null }
function View() { const c = useBookmarks(); return <><BookmarkButton type="COMPLEX" targetId="1" name="서울 주택" /><span>{c?.mode}</span>{c?.items.map((i) => <p key={i.targetId}>{i.name}</p>)}</> }
function setup(repository: BookmarkRepository, loadUser = vi.fn().mockResolvedValue({ id: 7, email: null })) {
  render(<MemoryRouter><BookmarkProvider repository={repository} loadUser={loadUser}><View /></BookmarkProvider></MemoryRouter>)
}
it('회원은 북마크를 저장하고 해제하며 중복 클릭을 막는다', async () => {
  let saved = false
  const repository = { list: vi.fn(async () => saved ? [item] : []), save: vi.fn(async () => { saved = true }), remove: vi.fn(async () => { saved = false }) }
  setup(repository)
  const save = await screen.findByRole('button', { name: '서울 주택 북마크 저장' })
  await waitFor(() => expect(save).toBeEnabled())
  fireEvent.click(save); fireEvent.click(save)
  const remove = await screen.findByRole('button', { name: '서울 주택 북마크 해제' })
  expect(repository.save).toHaveBeenCalledOnce()
  await waitFor(() => expect(remove).toBeEnabled())
  fireEvent.click(remove)
  await waitFor(() => expect(screen.queryByText('서울 주택')).not.toBeInTheDocument())
  expect(repository.remove).toHaveBeenCalledOnce()
})
it('비회원은 로그인 모달을 열고 회원 저장 요청을 보내지 않는다', async () => {
  const repository = { list: vi.fn(), save: vi.fn(), remove: vi.fn() }
  setup(repository, vi.fn().mockResolvedValue(null))
  await screen.findByText('guest')
  fireEvent.click(screen.getByRole('button', { name: '서울 주택 북마크 저장' }))
  expect(screen.getByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(repository.save).not.toHaveBeenCalled()
})
it('로그아웃 뒤 도착한 회원 목록을 버린다', async () => {
  let resolve!: (items: readonly BookmarkItem[]) => void
  const pending = new Promise<readonly BookmarkItem[]>((done) => { resolve = done })
  const repository = { list: vi.fn().mockReturnValue(pending), save: vi.fn(), remove: vi.fn() }
  setup(repository)
  await waitFor(() => expect(repository.list).toHaveBeenCalledOnce())
  act(() => window.dispatchEvent(new Event('toadzip:logout')))
  await act(async () => resolve([item]))
  expect(screen.getByText('guest')).toBeVisible()
  expect(screen.queryByText('서울 주택')).not.toBeInTheDocument()
})
it('저장 실패 후 같은 버튼으로 다시 시도할 수 있다', async () => {
  const repository = { list: vi.fn().mockResolvedValue([]), save: vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined), remove: vi.fn() }
  setup(repository)
  const button = screen.getByRole('button', { name: '서울 주택 북마크 저장' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도')
  fireEvent.click(button)
  await waitFor(() => expect(repository.save).toHaveBeenCalledTimes(2))
})
it('다른 대상 변경의 이전 목록이 늦게 도착해도 최신 목록을 유지한다', async () => {
  const second = { ...item, targetId: '2', name: '두번째 주택' }
  let resolveOld!: (items: readonly BookmarkItem[]) => void
  const old = new Promise<readonly BookmarkItem[]>((resolve) => { resolveOld = resolve })
  const repository = { list: vi.fn().mockResolvedValueOnce([]).mockReturnValueOnce(old).mockResolvedValueOnce([item, second]), save: vi.fn().mockResolvedValue(undefined), remove: vi.fn() }
  function Both() { const c = useBookmarks(); return <><BookmarkButton type="COMPLEX" targetId="1" name="서울 주택" /><BookmarkButton type="COMPLEX" targetId="2" name="두번째 주택" /><output>{c?.items.map((i) => i.name).join(',')}</output></> }
  render(<MemoryRouter><BookmarkProvider repository={repository} loadUser={async () => ({ id: 7, email: null })}><Both /></BookmarkProvider></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 주택 북마크 저장' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '서울 주택 북마크 저장' }))
  await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button', { name: '두번째 주택 북마크 저장' }))
  await screen.findByRole('button', { name: '두번째 주택 북마크 해제' })
  await act(async () => resolveOld([item]))
  expect(screen.getByRole('button', { name: '두번째 주택 북마크 해제' })).toBeVisible()
})
it('포커스 재조회가 저장보다 먼저 끝나도 저장 완료 후 서버 목록으로 수렴한다', async () => {
  let finishWrite!: () => void
  let saved = false
  const pendingWrite = new Promise<void>((resolve) => { finishWrite = resolve })
  const repository = { list: vi.fn(async () => saved ? [item] : []), save: vi.fn(async () => { await pendingWrite; saved = true }), remove: vi.fn() }
  setup(repository)
  await waitFor(() => expect(screen.getByRole('button', { name: '서울 주택 북마크 저장' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '서울 주택 북마크 저장' }))
  fireEvent.focus(window)
  await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(2))
  await act(async () => finishWrite())
  expect(await screen.findByRole('button', { name: '서울 주택 북마크 해제' })).toBeVisible()
})

it('공통 세션 종료 이벤트 뒤 늦은 회원 목록을 복구하지 않는다', async () => {
  let resolve!: (items: readonly BookmarkItem[]) => void
  const repository = { list: vi.fn(() => new Promise<readonly BookmarkItem[]>(done => { resolve = done })), save: vi.fn(), remove: vi.fn() }
  setup(repository)
  await waitFor(() => expect(repository.list).toHaveBeenCalledOnce())
  act(() => window.dispatchEvent(new Event('toadzip:session-ended')))
  await act(async () => resolve([item]))
  expect(screen.getByText('guest')).toBeVisible()
  expect(screen.queryByText('서울 주택')).not.toBeInTheDocument()
})


it('북마크 해제는 응답 전에 사라지고 실패하면 원래 위치에 복원된다', async () => {
  let reject!: (error: Error) => void
  const repository = { list: vi.fn().mockResolvedValue([item]), save: vi.fn(), remove: vi.fn(() => new Promise<void>((_, fail) => { reject = fail })) }
  setup(repository)
  fireEvent.click(await screen.findByRole('button', { name: '서울 주택 북마크 해제' }))
  expect(screen.queryByText('서울 주택')).not.toBeInTheDocument()
  expect(screen.queryByText('저장 중…')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '서울 주택 북마크 저장' })).toBeDisabled()
  await act(async () => reject(new Error('offline')))
  expect(screen.getByText('서울 주택')).toBeVisible()
  expect(screen.getByRole('button', { name: '서울 주택 북마크 해제' })).toBeEnabled()
  expect(screen.getByRole('alert')).toHaveTextContent('다시 시도')
})

it('북마크 저장은 응답 전에 버튼에 반영하고 실패하면 되돌린다', async () => {
  let reject!: (error: Error) => void
  const repository = { list: vi.fn().mockResolvedValue([]), save: vi.fn(() => new Promise<void>((_, fail) => { reject = fail })), remove: vi.fn() }
  setup(repository)
  const button = screen.getByRole('button', { name: '서울 주택 북마크 저장' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button); fireEvent.click(button)
  expect(button).toHaveAttribute('aria-pressed', 'true')
  expect(button).toHaveTextContent('저장됨')
  expect(repository.save).toHaveBeenCalledOnce()
  await act(async () => reject(new Error('offline')))
  expect(button).toHaveAttribute('aria-pressed', 'false')
  expect(button).toBeEnabled()
})


it('다른 북마크를 저장하는 동안 해제 항목이 되살아나지 않고 실패한 항목만 복원한다', async () => {
  const second = { ...item, targetId: '2', name: '두번째 주택' }
  let rejectRemove!: (error: Error) => void
  const repository = { list: vi.fn().mockResolvedValueOnce([item]).mockResolvedValue([second, item]), save: vi.fn().mockResolvedValue(undefined), remove: vi.fn(() => new Promise<void>((_, reject) => { rejectRemove = reject })) }
  function Both() { const c = useBookmarks(); return <><BookmarkButton type="COMPLEX" targetId="1" name="서울 주택" /><BookmarkButton type="COMPLEX" targetId="2" name="두번째 주택" /><output aria-label="저장 목록">{c?.items.map(i => i.name).join(',')}</output></> }
  render(<MemoryRouter><BookmarkProvider repository={repository} loadUser={async () => ({ id: 7, email: null })}><Both /></BookmarkProvider></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '서울 주택 북마크 해제' }))
  fireEvent.click(screen.getByRole('button', { name: '두번째 주택 북마크 저장' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '두번째 주택 북마크 해제' })).toBeEnabled())
  expect(screen.getByLabelText('저장 목록')).toHaveTextContent(/^두번째 주택$/)
  await act(async () => rejectRemove(new Error('offline')))
  expect(screen.getByLabelText('저장 목록')).toHaveTextContent('두번째 주택,서울 주택')
})

it('해제 중 로그아웃하면 실패 응답으로 이전 계정의 북마크를 복원하지 않는다', async () => {
  let rejectRemove!: (error: Error) => void
  setup({ list: vi.fn().mockResolvedValue([item]), save: vi.fn(), remove: vi.fn(() => new Promise<void>((_, reject) => { rejectRemove = reject })) })
  fireEvent.click(await screen.findByRole('button', { name: '서울 주택 북마크 해제' }))
  act(() => window.dispatchEvent(new Event('toadzip:logout')))
  await act(async () => rejectRemove(new Error('offline')))
  expect(screen.getByText('guest')).toBeVisible()
  expect(screen.queryByText('서울 주택')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})


it('다른 대상 저장이 실패해도 먼저 성공한 해제 결과를 되돌리지 않는다', async () => {
  let resolveList!: (items: readonly BookmarkItem[]) => void
  let rejectSave!: (error: Error) => void
  const repository = { list: vi.fn().mockResolvedValueOnce([item]).mockImplementationOnce(() => new Promise<readonly BookmarkItem[]>(resolve => { resolveList = resolve })), remove: vi.fn().mockResolvedValue(undefined), save: vi.fn(() => new Promise<void>((_, reject) => { rejectSave = reject })) }
  function Both() { const c = useBookmarks(); return <><BookmarkButton type="COMPLEX" targetId="1" name="서울 주택" /><BookmarkButton type="COMPLEX" targetId="2" name="두번째 주택" /><output aria-label="저장 목록">{c?.items.map(i => i.name).join(',')}</output></> }
  render(<MemoryRouter><BookmarkProvider repository={repository} loadUser={async () => ({ id: 7, email: null })}><Both /></BookmarkProvider></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '서울 주택 북마크 해제' }))
  await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button', { name: '두번째 주택 북마크 저장' }))
  await act(async () => rejectSave(new Error('offline')))
  await act(async () => resolveList([]))
  expect(screen.getByLabelText('저장 목록')).toBeEmptyDOMElement()
  expect(screen.getByRole('button', { name: '서울 주택 북마크 저장' })).toHaveAttribute('aria-pressed', 'false')
})
