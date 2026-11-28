import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { ManagementError } from '../management/api'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { FeedbackListPage } from './FeedbackListPage'
import { getFeedback, getFeedbackChanges, getFeedbackCounts, listFeedback, saveFeedback, type FeedbackCounts, type FeedbackEntry, type FeedbackPage } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  listFeedback: vi.fn(), getFeedback: vi.fn(), getFeedbackCounts: vi.fn(), getFeedbackChanges: vi.fn(), saveFeedback: vi.fn() }))
const counts: FeedbackCounts = { OPEN: 3, IN_PROGRESS: 2, ON_HOLD: 1, DONE: 12, total: 18 }
beforeEach(() => {
  vi.mocked(listFeedback).mockReset(); vi.mocked(getFeedbackCounts).mockReset().mockResolvedValue(counts)
  vi.mocked(getFeedbackChanges).mockReset().mockResolvedValue([]); vi.mocked(saveFeedback).mockReset(); vi.mocked(getFeedback).mockReset()
})
afterEach(() => vi.restoreAllMocks())
const entry: FeedbackEntry = { version: 0, status: 'OPEN', note: '', relatedResource: '', relatedId: null, actor: null, processedAt: null, id: 7, content: '지도에서 단지를 찾기 어려워요.\n검색을 개선해 주세요.', createdAt: '2026-10-07T01:00:00Z' }
const other: FeedbackEntry = { ...entry, id: 8, content: '두 번째 의견', createdAt: '2026-10-06T01:00:00Z' }
const third: FeedbackEntry = { ...entry, id: 9, content: '세 번째 의견', createdAt: '2026-10-05T01:00:00Z' }
const page = (...items: FeedbackEntry[]): FeedbackPage => ({ items, page: 0, hasNext: false, totalElements: items.length, totalPages: items.length ? 1 : 0 })
const lastRequest = () => vi.mocked(listFeedback).mock.lastCall![0].toString()
function Where() { return <output data-testid="where">{useLocation().search}</output> }
function setup(path = '/admin/feedback') { render(<MemoryRouter initialEntries={[path]}><FeedbackListPage /><Where /></MemoryRouter>) }
const row = (text: string | RegExp) => screen.getByRole('button', { name: text })

it('처음에는 접수 탭이 선택되고 접수 의견만 요청하며 탭마다 건수를 보여 준다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page(entry))
  setup()
  expect(await screen.findByText('지도에서 단지를 찾기 어려워요.', { exact: false })).toBeVisible()
  expect(new URLSearchParams(lastRequest()).get('status')).toBe('OPEN')
  expect(screen.getByRole('heading', { name: '사용자 의견' })).toBeVisible()
  expect(screen.getByText('앱에서 받은 의견을 읽고 처리 기록을 남깁니다.')).toBeVisible()
  const group = screen.getByRole('group', { name: '상태별 의견' })
  await waitFor(() => expect(within(group).getByRole('button', { name: '접수 3' })).toHaveAttribute('aria-pressed', 'true'))
  expect(within(group).getByRole('button', { name: '처리 중 2' })).toHaveAttribute('aria-pressed', 'false')
  expect(within(group).getByRole('button', { name: '보류 1' })).toBeVisible()
  expect(within(group).getByRole('button', { name: '처리 완료 12' })).toBeVisible()
  expect(within(group).getByRole('button', { name: '전체 18' })).toBeVisible()
  expect(screen.getByText('접수 최신순 · 한국 시각')).toBeVisible()
  expect(screen.queryByLabelText('의견 상태 필터')).not.toBeInTheDocument()
})

it('전체 탭은 상태 없이 요청하고 주소에 ALL을 둔다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page(entry))
  setup('/admin/feedback?page=0&status=DONE')
  await screen.findByText('지도에서 단지를 찾기 어려워요.', { exact: false })
  expect(new URLSearchParams(lastRequest()).get('status')).toBe('DONE')
  fireEvent.click(await screen.findByRole('button', { name: /^전체/ }))
  await waitFor(() => expect(new URLSearchParams(lastRequest()).has('status')).toBe(false))
  expect(screen.getByTestId('where')).toHaveTextContent('status=ALL')
})

it('탭을 옮기면 첫 페이지의 그 상태 의견을 요청하고 선택을 비운다', async () => {
  vi.mocked(listFeedback).mockResolvedValue({ ...page(entry), page: 2, totalPages: 3 })
  setup('/admin/feedback?page=2&feedback=7')
  await screen.findByRole('heading', { name: '의견 #7' })
  fireEvent.click(within(screen.getByRole('group', { name: '상태별 의견' })).getByRole('button', { name: /^처리 중/ }))
  await waitFor(() => expect(new URLSearchParams(lastRequest()).get('status')).toBe('IN_PROGRESS'))
  expect(new URLSearchParams(lastRequest()).get('page')).toBe('0')
  expect(screen.getByTestId('where')).not.toHaveTextContent('feedback=')
})

it('검색어는 목록과 건수 요청에 모두 들어가고 검색어 지우기로 되돌린다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page(entry))
  setup()
  await screen.findByText('지도에서 단지를 찾기 어려워요.', { exact: false })
  vi.mocked(listFeedback).mockResolvedValue(page())
  fireEvent.change(screen.getByRole('textbox', { name: '의견 내용 검색' }), { target: { value: '  검색  ' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(new URLSearchParams(lastRequest()).get('keyword')).toBe('검색'))
  expect(getFeedbackCounts).toHaveBeenLastCalledWith('검색', expect.any(AbortSignal))
  vi.mocked(listFeedback).mockResolvedValue(page(entry))
  fireEvent.click(await screen.findByRole('button', { name: '검색어 지우기' }))
  await waitFor(() => expect(new URLSearchParams(lastRequest()).has('keyword')).toBe(false))
  expect(getFeedbackCounts).toHaveBeenLastCalledWith('', expect.any(AbortSignal))
})

it('행에는 상태·접수 시각·내용·번호·관련 대상·메모 유무를 보여 주고 누르면 처리 패널이 열린다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page({ ...entry, relatedResource: 'COMPLEX', relatedId: 3, note: '확인 중' }, other))
  setup()
  const first = await screen.findByRole('button', { name: /지도에서 단지를/ })
  expect(first).toHaveTextContent('접수')
  expect(first).toHaveTextContent('10.07 10:00')
  expect(first).toHaveTextContent('#7')
  expect(first).toHaveTextContent('단지 #3')
  expect(first).toHaveTextContent('메모 있음')
  expect(row(/두 번째 의견/)).not.toHaveTextContent('메모 있음')
  fireEvent.click(first)
  expect(await screen.findByRole('heading', { name: '의견 #7' })).toBeVisible()
  expect(first).toHaveAttribute('aria-current', 'true')
  expect(screen.getByTestId('where')).toHaveTextContent('feedback=7')
})

it('저장하면 목록과 건수를 다시 불러오고 탭에서 빠진 의견 자리의 다음 의견을 선택한다', async () => {
  vi.mocked(listFeedback).mockResolvedValueOnce(page(entry, other, third))
  setup('/admin/feedback?feedback=8')
  await screen.findByRole('heading', { name: '의견 #8' })
  vi.mocked(saveFeedback).mockResolvedValue({ ...other, version: 1, status: 'IN_PROGRESS' })
  vi.mocked(listFeedback).mockResolvedValue(page(entry, third))
  const before = { list: vi.mocked(listFeedback).mock.calls.length, counts: vi.mocked(getFeedbackCounts).mock.calls.length }
  fireEvent.click(screen.getByRole('button', { name: '처리 중' }))
  fireEvent.click(screen.getByRole('button', { name: '처리 기록 저장' }))
  expect(await screen.findByRole('heading', { name: '의견 #9' })).toBeVisible()
  expect(saveFeedback).toHaveBeenCalledWith(8, expect.objectContaining({ status: 'IN_PROGRESS', version: 0 }))
  expect(vi.mocked(listFeedback).mock.calls.length).toBe(before.list + 1)
  expect(vi.mocked(getFeedbackCounts).mock.calls.length).toBe(before.counts + 1)
  expect(screen.queryByText('두 번째 의견')).not.toBeInTheDocument()
  expect(screen.getByText('의견 #8의 처리 기록을 저장했습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: /세 번째 의견|#10/ }))
  expect(screen.queryByText('의견 #8의 처리 기록을 저장했습니다.')).not.toBeInTheDocument()
})

it('탭에서 마지막 의견이 빠지면 첫 의견을, 하나도 안 남으면 빈 패널을 보여 준다', async () => {
  vi.mocked(listFeedback).mockResolvedValueOnce(page(entry, other))
  setup('/admin/feedback?feedback=8')
  await screen.findByRole('heading', { name: '의견 #8' })
  vi.mocked(saveFeedback).mockResolvedValue({ ...other, version: 1, status: 'DONE', note: '처리함' })
  vi.mocked(listFeedback).mockResolvedValue(page(entry))
  fireEvent.click(screen.getByRole('button', { name: '처리 완료' }))
  fireEvent.change(screen.getByLabelText(/처리 결과·보류 이유/), { target: { value: '처리함' } })
  fireEvent.click(screen.getByRole('button', { name: '처리 기록 저장' }))
  expect(await screen.findByRole('heading', { name: '의견 #7' })).toBeVisible()
  vi.mocked(saveFeedback).mockResolvedValue({ ...entry, version: 1, status: 'IN_PROGRESS' })
  vi.mocked(listFeedback).mockResolvedValue(page())
  fireEvent.click(screen.getByRole('button', { name: '처리 중' }))
  fireEvent.click(screen.getByRole('button', { name: '처리 기록 저장' }))
  expect(await screen.findByText('새로 접수된 의견이 없습니다.')).toBeVisible()
  expect(screen.queryByRole('heading', { name: /의견 #/ })).not.toBeInTheDocument()
})

it('저장하지 않은 변경이 있으면 다른 의견·탭으로 옮기기 전에 확인한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page(entry, other))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  setup('/admin/feedback?feedback=7')
  await screen.findByRole('heading', { name: '의견 #7' })
  fireEvent.click(screen.getByRole('button', { name: '보류' }))
  fireEvent.click(row(/두 번째 의견/))
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('heading', { name: '의견 #7' })).toBeVisible()
  fireEvent.click(within(screen.getByRole('group', { name: '상태별 의견' })).getByRole('button', { name: /^처리 중/ }))
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(screen.getByTestId('where')).not.toHaveTextContent('status=')
  confirm.mockReturnValue(true)
  fireEvent.click(row(/두 번째 의견/))
  expect(await screen.findByRole('heading', { name: '의견 #8' })).toBeVisible()
})

it('탭별·검색별 빈 상태 문구를 구분한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page())
  setup()
  expect(await screen.findByText('새로 접수된 의견이 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: /^보류/ }))
  expect(await screen.findByText('이 상태의 의견이 없습니다.')).toBeVisible()
  fireEvent.change(screen.getByRole('textbox', { name: '의견 내용 검색' }), { target: { value: '없는 의견' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('검색 결과가 없습니다.')).toBeVisible()
})

it('목록 조회 실패는 빈 결과로 표시하지 않으며 다시 불러올 수 있다', async () => {
  vi.mocked(listFeedback).mockRejectedValueOnce(new Error('연결 실패')).mockResolvedValueOnce(page(entry))
  setup()
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
  expect(screen.queryByText('새로 접수된 의견이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('지도에서 단지를 찾기 어려워요.', { exact: false })).toBeVisible()
})

it('건수 조회가 실패하면 오류를 알리고 건수 없이 탭은 계속 쓸 수 있다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page(entry))
  vi.mocked(getFeedbackCounts).mockRejectedValueOnce(new Error('사용자 의견 건수 응답이 올바르지 않습니다.'))
  setup()
  expect(await screen.findByRole('alert')).toHaveTextContent('건수 응답이 올바르지 않습니다')
  expect(screen.getByRole('button', { name: '접수' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '건수 다시 불러오기' }))
  expect(await screen.findByRole('button', { name: '접수 3' })).toBeVisible()
})

it('잘못된 주소 조건은 API를 호출하지 않는다', () => {
  setup('/admin/feedback?page=-1')
  expect(screen.getByRole('alert')).toHaveTextContent('검색 조건을 확인')
  expect(listFeedback).not.toHaveBeenCalled()
  expect(getFeedbackCounts).not.toHaveBeenCalled()
})

it('알 수 없는 상태 주소는 거절한다', () => {
  setup('/admin/feedback?status=DELETED')
  expect(screen.getByRole('alert')).toHaveTextContent('검색 조건을 확인')
  expect(listFeedback).not.toHaveBeenCalled()
})

it('목록 범위를 벗어난 큰 페이지 주소는 마지막 유효 페이지로 이동한다', async () => {
  vi.mocked(listFeedback).mockResolvedValueOnce({ items: [], page: 2147483647, hasNext: false, totalElements: 1, totalPages: 1 })
    .mockResolvedValueOnce(page(entry))
  setup('/admin/feedback?page=2147483647')
  expect(await screen.findByText('지도에서 단지를 찾기 어려워요.', { exact: false })).toBeVisible()
  expect(new URLSearchParams(lastRequest()).get('page')).toBe('0')
})

it('다음 페이지를 조회한다', async () => {
  vi.mocked(listFeedback).mockResolvedValue({ ...page(entry), hasNext: true, totalPages: 2, totalElements: 21 })
  setup()
  await screen.findByText('지도에서 단지를 찾기 어려워요.', { exact: false })
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  await waitFor(() => expect(new URLSearchParams(lastRequest()).get('page')).toBe('1'))
})

it('이전 검색의 늦은 응답이 새 결과를 덮어쓰지 않는다', async () => {
  let finishOld: (value: FeedbackPage) => void = () => undefined
  vi.mocked(listFeedback).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
    .mockResolvedValueOnce(page({ ...entry, content: '새 검색 결과' }))
  setup()
  expect(screen.getByText('의견 목록을 불러오는 중…')).toBeVisible()
  fireEvent.change(screen.getByRole('textbox', { name: '의견 내용 검색' }), { target: { value: '새 검색' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('새 검색 결과')
  await act(async () => finishOld(page({ ...entry, content: '오래된 결과' })))
  expect(screen.queryByText('오래된 결과')).not.toBeInTheDocument()
})

it('사용자 입력 HTML을 실행하지 않고 텍스트로 표시하며 빈 본문은 추가 의견 없음으로 둔다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page({ ...entry, content: '<script>alert("test")</script>' }, { ...other, content: '' }))
  setup('/admin/feedback?feedback=7')
  const body = await screen.findByRole('region', { name: '의견 내용' })
  expect(body).toHaveTextContent('<script>alert("test")</script>')
  expect(body.querySelector('script')).toBeNull()
  expect(row(/추가 의견 없음/)).toBeVisible()
})

it('저장이 충돌하면 목록 행만 최신 상태로 바꾸고 건수를 다시 받으며 패널과 작성 내용은 그대로 둔다', async () => {
  vi.mocked(listFeedback).mockResolvedValue(page(entry, other))
  vi.mocked(saveFeedback).mockRejectedValueOnce(new ManagementError('충돌', 409))
  vi.mocked(getFeedback).mockResolvedValue({ ...entry, version: 2, status: 'IN_PROGRESS' })
  setup('/admin/feedback?feedback=7')
  await screen.findByRole('form', { name: '의견 7 처리' })
  await waitFor(() => expect(getFeedbackCounts).toHaveBeenCalledTimes(1))
  await screen.findByRole('button', { name: /^전체 \d+$/ })
  // 다시 받는 건수 응답이 오기 전에도 탭 숫자가 그대로 남는지 보려고 두 번째 응답을 붙잡아 둔다.
  vi.mocked(getFeedbackCounts).mockReturnValueOnce(new Promise(() => {}))
  fireEvent.click(screen.getByRole('button', { name: '처리 완료' }))
  fireEvent.change(screen.getByLabelText(/처리 결과·보류 이유/), { target: { value: '내가 쓴 결과' } })
  fireEvent.click(screen.getByRole('button', { name: '처리 기록 저장' }))
  await screen.findByRole('table', { name: '최신 저장과 내 작성 비교' })
  expect(screen.getByRole('button', { name: /^전체 \d+$/ })).toBeVisible()
  expect(row(/처리 중.*지도에서 단지를 찾기/)).toBeVisible()
  expect(row(/접수.*두 번째 의견/)).toBeVisible()
  await waitFor(() => expect(getFeedbackCounts).toHaveBeenCalledTimes(2))
  expect(listFeedback).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('form', { name: '의견 7 처리' })).toBeVisible()
  expect(screen.getByLabelText(/처리 결과·보류 이유/)).toHaveValue('내가 쓴 결과')
})
