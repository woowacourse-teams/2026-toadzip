import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FeedbackListPage } from './FeedbackListPage'
import { listFeedback, type FeedbackPage } from './api'

vi.mock('./api', () => ({ listFeedback: vi.fn() }))
beforeEach(() => { vi.mocked(listFeedback).mockReset() })
const entry = { id: 7, content: '지도에서 단지를 찾기 어려워요.\n검색을 개선해 주세요.', createdAt: '2026-10-07T01:00:00Z' }
const page = (content = entry.content): FeedbackPage => ({ items: [{ ...entry, content }], page: 0, hasNext: false, totalElements: 1, totalPages: 1 })
function setup(path = '/admin/feedback') { render(<MemoryRouter initialEntries={[path]}><FeedbackListPage /></MemoryRouter>) }

it('최신 의견의 접수시각과 전체 내용을 확인한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page())
  setup()
  expect(await screen.findByRole('heading', { name: '의견 #7' })).toBeVisible()
  expect(screen.getByText(/2026\. 10\. 07\. 10:00/)).toBeVisible()
  fireEvent.click(screen.getByText('전체 내용 보기'))
  const body = screen.getByRole('region', { name: '의견 7 전체 내용' })
  expect(body).toBeVisible()
  expect(body.textContent).toContain(entry.content)
})

it('본문 검색 시 첫 페이지로 이동하고 초기화한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue({ ...page(), page: 2, totalPages: 3 })
  setup('/admin/feedback?page=2')
  await screen.findByRole('heading', { name: '의견 #7' })
  vi.mocked(listFeedback).mockResolvedValue(page())
  fireEvent.change(screen.getByRole('textbox', { name: '의견 내용 검색' }), { target: { value: '  검색  ' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(listFeedback).toHaveBeenLastCalledWith(new URLSearchParams({ page: '0', size: '20', keyword: '검색' }), expect.any(AbortSignal)))
  await screen.findByRole('heading', { name: '의견 #7' })
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  await waitFor(() => expect(listFeedback).toHaveBeenLastCalledWith(new URLSearchParams({ page: '0', size: '20' }), expect.any(AbortSignal)))
})

it('다음 페이지를 조회한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue({ ...page(), hasNext: true, totalPages: 2, totalElements: 21 })
  setup()
  await screen.findByRole('heading', { name: '의견 #7' })
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await waitFor(() => expect(listFeedback).toHaveBeenLastCalledWith(new URLSearchParams({ page: '1', size: '20' }), expect.any(AbortSignal)))
})

it('빈 접수와 빈 검색 결과를 구분한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })
  setup()
  expect(await screen.findByText('접수된 의견이 없습니다.')).toBeVisible()
  fireEvent.change(screen.getByRole('textbox', { name: '의견 내용 검색' }), { target: { value: '없는 의견' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('검색 결과가 없습니다.')).toBeVisible()
})

it('조회 실패는 빈 결과로 표시하지 않으며 다시 불러올 수 있다', async () => {
  vi.mocked(listFeedback).mockRejectedValueOnce(new Error('연결 실패')).mockResolvedValueOnce(page())
  setup()
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
  expect(screen.queryByText('접수된 의견이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByRole('heading', { name: '의견 #7' })).toBeVisible()
})

it('잘못된 페이지 주소는 API를 호출하지 않는다', () => {
  setup('/admin/feedback?page=-1')
  expect(screen.getByRole('alert')).toHaveTextContent('검색 조건을 확인')
  expect(listFeedback).not.toHaveBeenCalled()
})

it('목록 범위를 벗어난 큰 페이지 주소는 마지막 유효 페이지로 이동한다', async () => {
  vi.mocked(listFeedback).mockResolvedValueOnce({
    items: [], page: 2147483647, hasNext: false, totalElements: 1, totalPages: 1,
  }).mockResolvedValueOnce(page())
  setup('/admin/feedback?page=2147483647')
  expect(await screen.findByRole('heading', { name: '의견 #7' })).toBeVisible()
  expect(listFeedback).toHaveBeenLastCalledWith(new URLSearchParams({ page: '0', size: '20' }), expect.any(AbortSignal))
})

it('이전 검색의 늦은 응답이 새 결과를 덮어쓰지 않는다', async () => {
  let finishOld: (value: FeedbackPage) => void = () => undefined
  vi.mocked(listFeedback).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
    .mockResolvedValueOnce(page('새 검색 결과'))
  setup()
  expect(screen.getByRole('status')).toHaveTextContent('불러오는 중')
  fireEvent.change(screen.getByRole('textbox', { name: '의견 내용 검색' }), { target: { value: '새 검색' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('새 검색 결과', { selector: 'article > p' })
  await act(async () => finishOld(page('오래된 결과')))
  expect(screen.queryByText('오래된 결과')).not.toBeInTheDocument()
})

it('사용자 입력 HTML을 실행하지 않고 텍스트로 표시한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page('<script>alert("test")</script>'))
  setup()
  await screen.findByRole('heading', { name: '의견 #7' })
  fireEvent.click(screen.getByText('전체 내용 보기'))
  const body = screen.getByRole('region', { name: '의견 7 전체 내용' })
  expect(body).toHaveTextContent('<script>alert("test")</script>')
  expect(body.querySelector('script')).toBeNull()
})
