import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FailureReviewPage } from './FailureReviewPage'
import { getFailureReview, getFailureReviews, type FailureReview } from './failureReviewApi'
import { getDataPipelineStatus, startDataPipeline } from './api'
import { newWindowName } from '../../test/newWindowName'

vi.mock('./failureReviewApi', () => ({ getFailureReviews: vi.fn(), getFailureReview: vi.fn() }))
vi.mock('./api', async (original) => ({ ...(await original<typeof import('./api')>()),
  getIngestFailures: vi.fn().mockResolvedValue([]), getDataPipelineStatus: vi.fn(),
  startDataPipeline: vi.fn(),
}))
const reviews = vi.mocked(getFailureReviews)
const row: FailureReview = {
  id: 7, category: 'complex', target: '두꺼비 단지', sourceKey: 'source-7', source: 'MYHOME_COMPLEX',
  reason: 'MISSING_REQUIRED_VALUE', detail: '주택형명 값이 없습니다.', status: 'PENDING',
  occurredAt: '2026-10-03T03:00:00Z', occurrenceCount: 2, recurrenceCount: 0,
  executionId: null, lastResolvedAt: null, lastResolvedBy: null, raw: {}, productLinkStatus: 'EXISTS',
  name: '두꺼비 단지', identifier: 'source-7', supplyRow: null,
  product: { id: 42, name: '두꺼비 단지', resourceType: 'complexes', deleted: false,
    hasCoordinates: true, housingTypeCount: 0, linkedComplexCount: null, supplyRowCount: null,
    applicationScheduleCount: null, attachmentCount: null, applicationStartDate: null, applicationEndDate: null,
    applicationScheduleReviewed: null, publicDetailAvailable: true, publicListEligible: true, listExclusionReasons: [] },
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getFailureReview).mockResolvedValue(row)
  reviews.mockResolvedValue({items:[row],page:0,totalElements:41,totalPages:3,hasNext:true})
  vi.mocked(getDataPipelineStatus).mockResolvedValue({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(this:HTMLDialogElement){this.setAttribute('open','')}})
  Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(this:HTMLDialogElement){this.removeAttribute('open');this.dispatchEvent(new Event('close'))}})
})

it('주소에 대상이 없으면 공고 문제를 열고 지금 연 탭에 미해결 건수를 붙인다', async () => {
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  expect(await screen.findByRole('tab',{name:'공고 문제 41건'})).toHaveAttribute('aria-selected','true')
  expect(reviews.mock.lastCall?.[0]).toBe('announcement')
  expect(screen.getByText('총 41건 · 최근 발생순')).toBeVisible()
  expect(screen.getByText('1 / 3 페이지')).toBeVisible()
  expect(screen.getByRole('link',{name:'공고 수집·정제 진행 상황'})).toHaveAttribute('href','/admin/ingest/announcements')
  // 다른 탭의 건수는 받지 않았으므로 붙이지 않고, 탭을 옮기면 옮긴 탭의 건수로 바뀐다.
  reviews.mockResolvedValue({items:[row],page:0,totalElements:7,totalPages:1,hasNext:false})
  fireEvent.click(screen.getByRole('tab',{name:'단지 문제'}))
  expect(await screen.findByRole('tab',{name:'단지 문제 7건'})).toHaveAttribute('aria-selected','true')
  expect(reviews.mock.lastCall?.[0]).toBe('complex')
  expect(screen.getByRole('tab',{name:'공고 문제'})).toHaveAttribute('aria-selected','false')
  expect(screen.getByRole('link',{name:'단지 수집·정제 진행 상황'})).toHaveAttribute('href','/admin/ingest/complexes')
})

it.each([['?domain=complex','단지 문제 41건'],['?category=complex','단지 문제'],['?category=collection','공고 문제']])(
  '주소 %s로 들어오면 지정한 탭을 연다', async (search, tab) => {
    render(<MemoryRouter initialEntries={[`/admin/failures${search}`]}><FailureReviewPage /></MemoryRouter>)
    expect(await screen.findByRole('tab',{name:tab})).toHaveAttribute('aria-selected','true')
  })

it('실패와 별도로 현재 등록 데이터와 사용자 화면 조건을 확인하고 정확한 대상을 수정한다', async () => {
  render(<MemoryRouter initialEntries={['/?domain=complex']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'확인·처리: 두꺼비 단지'}))
  const link=screen.getByRole('link',{name:'단지 확인·수정'})
  expect(new URL(link.getAttribute('href')!, 'https://test.invalid').pathname).toBe('/admin/complexes/42')
  expect(new URL(link.getAttribute('href')!, 'https://test.invalid').searchParams.get('failureReturnTo')).toContain('failure=complex%3A7')
  expect(screen.getByRole('link',{name:newWindowName('사용자 상세')})).toHaveAttribute('href','/?complexId=42')
  // 서비스 데이터 상태는 접어 두고 필요할 때 펼쳐 본다.
  fireEvent.click(within(screen.getByRole('dialog')).getByText('서비스 데이터·실패 기록 원문'))
  expect(within(screen.getByRole('dialog')).getByText('주택형 0개')).toBeVisible()
  expect(screen.getByText(/주택형 정보를 제공할 수 없습니다/)).toBeVisible()
})

it('정확한 대상 연결이 없는 오류를 미등록이나 미노출로 단정하지 않는다', async () => {
  const unlinked={...row,product:null,productLinkStatus:'UNKNOWN'} as typeof row
  reviews.mockResolvedValue({items:[unlinked],page:0,totalElements:1,totalPages:1,hasNext:false})
  vi.mocked(getFailureReview).mockResolvedValue(unlinked)
  render(<MemoryRouter initialEntries={['/?domain=complex']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:/^확인·처리:/}))
  // 서비스 데이터 연결 상태는 확인·처리 창의 접힌 원문 영역에 남는다.
  expect(await within(screen.getByRole('dialog')).findByText('대상 연결 확인 필요')).toBeInTheDocument()
  expect(screen.queryByRole('link',{name:'단지 확인·수정'})).not.toBeInTheDocument()
})

it('단지 문제에는 종료된 재정제 버튼과 안내를 표시하지 않는다', async () => {
  render(<MemoryRouter initialEntries={['/?domain=complex']}><FailureReviewPage /></MemoryRouter>)
  await screen.findByText('총 41건 · 최근 발생순')
  expect(screen.queryByRole('button', { name: '단지 정제 다시 실행' })).not.toBeInTheDocument()
  expect(screen.queryByText(/지원 종료:/)).not.toBeInTheDocument()
  expect(startDataPipeline).not.toHaveBeenCalled()
})

it('재처리가 끝나면 오류 목록을 다시 조회하고 해결 기록을 별도로 볼 수 있다', async () => {
  const running={status:'RUNNING',executionId:'run-7',type:'ANNOUNCEMENT_REFINEMENT'} as Awaited<ReturnType<typeof startDataPipeline>>
  vi.mocked(startDataPipeline).mockResolvedValue(running)
  vi.mocked(getDataPipelineStatus).mockResolvedValueOnce({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
    .mockResolvedValue({...running,status:'COMPLETED'})
  render(<MemoryRouter initialEntries={['/?domain=announcement']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'공고 정제 다시 실행'}))
  expect(startDataPipeline).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button',{name:'전체 공고 정제 실행'}))
  await waitFor(() => expect(startDataPipeline).toHaveBeenCalledWith('ANNOUNCEMENT_REFINEMENT'))
  await waitFor(() => expect(reviews.mock.calls.length).toBeGreaterThan(1),{timeout:3000})
  fireEvent.change(screen.getByRole('combobox',{name:'처리 상태'}),{target:{value:'RESOLVED'}})
  await waitFor(() => expect(reviews.mock.lastCall?.[2]).toBe('RESOLVED'))
})

it('새 실행으로 상태가 바뀌면 이전 실행의 완료로 표시하지 않고 최신 실행임을 알린다', async () => {
  const running={status:'RUNNING',executionId:'run-7',type:'ANNOUNCEMENT_REFINEMENT'} as Awaited<ReturnType<typeof startDataPipeline>>
  vi.mocked(startDataPipeline).mockResolvedValue(running)
  vi.mocked(getDataPipelineStatus).mockResolvedValueOnce({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
    .mockResolvedValue({...running,executionId:'run-8'})
  render(<MemoryRouter initialEntries={['/?domain=announcement']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'공고 정제 다시 실행'}))
  expect(startDataPipeline).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button',{name:'전체 공고 정제 실행'}))
  expect(await screen.findByText('실행 기록이 바뀌어 최신 정제 상태를 표시합니다.', {}, {timeout:3000})).toBeVisible()
  expect(await screen.findByText('공고 정제 실행 중 · 끝나면 목록을 갱신합니다.')).toHaveAttribute('role', 'status')
  expect(getDataPipelineStatus).toHaveBeenLastCalledWith('ANNOUNCEMENT_REFINEMENT')
  expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeDisabled()
})

it.each(['COMPLETED','FAILED'] as const)(
  '재처리 POST가 %s로 끝난 뒤 늦은 최초 GET 응답이 도착해도 실행 결과를 유지한다', async (status) => {
    const initial = deferred<Awaited<ReturnType<typeof getDataPipelineStatus>>>()
    const started = deferred<Awaited<ReturnType<typeof startDataPipeline>>>()
    vi.mocked(getDataPipelineStatus).mockReturnValueOnce(initial.promise)
    vi.mocked(startDataPipeline).mockReturnValueOnce(started.promise)
    render(<MemoryRouter initialEntries={['/?domain=announcement']}><FailureReviewPage /></MemoryRouter>)
    await waitFor(() => expect(getDataPipelineStatus).toHaveBeenCalledWith('ANNOUNCEMENT_REFINEMENT'))
    fireEvent.click(screen.getByRole('button',{name:'공고 정제 다시 실행'}))
    fireEvent.click(screen.getByRole('button',{name:'전체 공고 정제 실행'}))
    await waitFor(() => expect(startDataPipeline).toHaveBeenCalledWith('ANNOUNCEMENT_REFINEMENT'))
    expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeDisabled()
    await act(() => started.resolve({ status, executionId:'new-run', type:'ANNOUNCEMENT_REFINEMENT' } as Awaited<ReturnType<typeof startDataPipeline>>))
    expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeEnabled()
    // 이 화면에서 시작한 실행은 실패로 끝났을 때만 결과를 알리고, 완료는 목록이 보여 준다.
    const failure = /공고 정제가 '실패'로 끝났습니다/
    if (status === 'FAILED') expect(screen.getByRole('alert')).toHaveTextContent(failure)
    else expect(screen.queryByText(failure)).not.toBeInTheDocument()
    // 늦게 온 최초 응답이 이전 실행을 '진행 중'으로 알려도 방금 끝난 실행 결과를 덮지 않는다.
    await act(() => initial.resolve({status:'RUNNING',executionId:'old-run',type:'ANNOUNCEMENT_REFINEMENT'} as Awaited<ReturnType<typeof getDataPipelineStatus>>))
    expect(screen.queryByText(/끝나면 목록을 갱신합니다/)).not.toBeInTheDocument()
    expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeEnabled()
  },
)

function deferred<T>() {
  let resolve: (value: T) => void = () => { throw new Error('Promise not initialized') }
  const promise = new Promise<T>(onResolve => { resolve = onResolve })
  return { promise, resolve }
}

it('도메인을 바꾼 뒤 도착한 이전 도메인 상태 응답을 무시한다', async () => {
  const initial = deferred<Awaited<ReturnType<typeof getDataPipelineStatus>>>()
  vi.mocked(getDataPipelineStatus).mockReturnValueOnce(initial.promise)
    .mockResolvedValue({status:'COMPLETED',executionId:'announcement-run',type:'ANNOUNCEMENT_REFINEMENT'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
  render(<MemoryRouter initialEntries={['/?domain=complex']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('tab',{name:'공고 문제'}))
  await waitFor(() => expect(getDataPipelineStatus).toHaveBeenCalledWith('ANNOUNCEMENT_REFINEMENT'))
  expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeEnabled()
  // 끝난 정제는 문구로 다시 알리지 않고, 이전 탭(단지)의 늦은 '진행 중' 응답은 공고 탭을 잠그지 않는다.
  await act(() => initial.resolve({status:'RUNNING',executionId:'old-complex-run',type:'COMPLEX_REFINEMENT'} as Awaited<ReturnType<typeof getDataPipelineStatus>>))
  expect(screen.queryByText(/정제 실행 중/)).not.toBeInTheDocument()
  // 화면을 열 때 받은 지난 실행(완료)은 문구로 알리지 않는다.
  expect(screen.queryByText(/끝나면 목록을 갱신합니다|공고 정제가 /)).not.toBeInTheDocument()
  expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeEnabled()
})


it('다시 실행은 범위를 알리는 확인 창에서 취소하면 시작하지 않는다', async () => {
  render(<MemoryRouter initialEntries={['/?domain=announcement']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'공고 정제 다시 실행'}))
  const confirm = screen.getByRole('dialog',{name:'저장된 공고 원천 전체를 다시 정제합니까?'})
  expect(confirm).toHaveTextContent('이 목록에 없는 공고도 함께 처리합니다.')
  fireEvent.click(within(confirm).getByRole('button',{name:'취소'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(startDataPipeline).not.toHaveBeenCalled()
})

it('다시 실행을 시작하지 못하면 표 위 오류 상자로 알린다', async () => {
  vi.mocked(startDataPipeline).mockRejectedValue(new Error('이미 다른 정제가 실행 중입니다.'))
  render(<MemoryRouter initialEntries={['/?domain=announcement']}><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'공고 정제 다시 실행'}))
  fireEvent.click(screen.getByRole('button',{name:'전체 공고 정제 실행'}))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveClass('admin-alert')
  expect(alert).toHaveTextContent('이미 다른 정제가 실행 중입니다.')
  expect(alert.compareDocumentPosition(screen.getByRole('table')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
})
