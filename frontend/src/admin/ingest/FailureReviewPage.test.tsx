import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FailureReviewPage } from './FailureReviewPage'
import { getFailureReviews, type FailureReview } from './failureReviewApi'
import { getDataPipelineStatus, startDataPipeline } from './api'

vi.mock('./failureReviewApi', () => ({ getFailureReviews: vi.fn() }))
vi.mock('./api', async (original) => ({ ...(await original<typeof import('./api')>()),
  getIngestFailures: vi.fn().mockResolvedValue([]), getDataPipelineStatus: vi.fn(),
  startDataPipeline: vi.fn(),
}))
const reviews = vi.mocked(getFailureReviews)
const row: FailureReview = {
  id: 7, category: 'complex', target: '두꺼비 단지', sourceKey: 'source-7', source: 'MYHOME_COMPLEX',
  reason: 'MISSING_REQUIRED_VALUE', detail: '주택형명 값이 없습니다.', status: 'PENDING',
  occurredAt: '2026-10-03T03:00:00Z', occurrenceCount: 2, recurrenceCount: 0,
  executionId: null, lastResolvedAt: null, raw: {}, productLinkStatus: 'EXISTS',
  product: { id: 42, name: '두꺼비 단지', resourceType: 'complexes', deleted: false,
    hasCoordinates: true, housingTypeCount: 0, linkedComplexCount: null, supplyRowCount: null,
    applicationScheduleCount: null, attachmentCount: null, applicationStartDate: null, applicationEndDate: null,
    applicationScheduleReviewed: null, publicDetailAvailable: true, publicListEligible: true, listExclusionReasons: [] },
}
beforeEach(() => {
  vi.clearAllMocks()
  reviews.mockResolvedValue({items:[row],page:0,totalElements:41,totalPages:3,hasNext:true})
  vi.mocked(getDataPipelineStatus).mockResolvedValue({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(this:HTMLDialogElement){this.setAttribute('open','')}})
  Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(this:HTMLDialogElement){this.removeAttribute('open');this.dispatchEvent(new Event('close'))}})
})

it('단지와 공고 문제를 분리하고 전체 건수와 페이지를 보여준다', async () => {
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  expect(await screen.findByRole('tab',{name:'단지 문제'})).toHaveAttribute('aria-selected','true')
  expect(await screen.findByText('총 41건')).toBeVisible()
  expect(screen.getByText('1 / 3 페이지')).toBeVisible()
  fireEvent.click(screen.getByRole('tab',{name:'공고 문제'}))
  await waitFor(() => expect(reviews.mock.lastCall?.[0]).toBe('announcement'))
})

it('실패와 별도로 현재 등록 데이터와 사용자 화면 조건을 확인하고 정확한 대상을 수정한다', async () => {
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'확인·처리: 두꺼비 단지'}))
  const link=screen.getByRole('link',{name:'단지 확인·수정'})
  expect(link).toHaveAttribute('href','/admin/complexes/42')
  expect(screen.getByRole('link',{name:'사용자 상세 보기'})).toHaveAttribute('href','/?complexId=42')
  expect(within(screen.getByRole('dialog')).getByText('주택형 0개')).toBeVisible()
  expect(screen.getByText(/주택형 정보를 제공할 수 없습니다/)).toBeVisible()
})

it('정확한 대상 연결이 없는 오류를 미등록이나 미노출로 단정하지 않는다', async () => {
  reviews.mockResolvedValue({items:[{...row,product:null,productLinkStatus:'UNKNOWN'}],page:0,totalElements:1,totalPages:1,hasNext:false})
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  expect(await screen.findByText('대상 연결 확인 필요')).toBeVisible()
  expect(screen.queryByRole('link',{name:'단지 확인·수정'})).not.toBeInTheDocument()
})

it('재처리가 끝나면 오류 목록을 다시 조회하고 해결 기록을 별도로 볼 수 있다', async () => {
  const running={status:'RUNNING',executionId:'run-7',type:'COMPLEX_REFINEMENT'} as Awaited<ReturnType<typeof startDataPipeline>>
  vi.mocked(startDataPipeline).mockResolvedValue(running)
  vi.mocked(getDataPipelineStatus).mockResolvedValueOnce({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
    .mockResolvedValue({...running,status:'COMPLETED'})
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'단지 정제 다시 실행'}))
  await waitFor(() => expect(startDataPipeline).toHaveBeenCalledWith('COMPLEX_REFINEMENT'))
  await waitFor(() => expect(reviews.mock.calls.length).toBeGreaterThan(1),{timeout:3000})
  fireEvent.change(screen.getByRole('combobox',{name:'처리 상태'}),{target:{value:'RESOLVED'}})
  await waitFor(() => expect(reviews.mock.lastCall?.[2]).toBe('RESOLVED'))
})

it('새 실행으로 상태가 바뀌면 이전 실행의 완료로 표시하지 않고 최신 실행임을 알린다', async () => {
  const running={status:'RUNNING',executionId:'run-7',type:'COMPLEX_REFINEMENT'} as Awaited<ReturnType<typeof startDataPipeline>>
  vi.mocked(startDataPipeline).mockResolvedValue(running)
  vi.mocked(getDataPipelineStatus).mockResolvedValueOnce({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>)
    .mockResolvedValue({...running,executionId:'run-8'})
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button',{name:'단지 정제 다시 실행'}))
  expect(await screen.findByText('실행 기록이 바뀌어 최신 정제 상태를 표시합니다.', {}, {timeout:3000})).toBeVisible()
  expect(screen.queryByText(/단지 정제: 선택된 작업 완료/)).not.toBeInTheDocument()
  expect(getDataPipelineStatus).toHaveBeenLastCalledWith('COMPLEX_REFINEMENT')
  expect(screen.getByRole('button',{name:'단지 정제 다시 실행'})).toBeDisabled()
})

it.each([['COMPLETED','완료'],['FAILED','실패']] as const)(
  '재처리 POST가 %s로 끝난 뒤 늦은 최초 GET 응답이 도착해도 실행 결과를 유지한다', async (status, label) => {
    const initial = deferred<Awaited<ReturnType<typeof getDataPipelineStatus>>>()
    const started = deferred<Awaited<ReturnType<typeof startDataPipeline>>>()
    vi.mocked(getDataPipelineStatus).mockReturnValueOnce(initial.promise)
    vi.mocked(startDataPipeline).mockReturnValueOnce(started.promise)
    render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
    await waitFor(() => expect(getDataPipelineStatus).toHaveBeenCalledWith('COMPLEX_REFINEMENT'))
    fireEvent.click(screen.getByRole('button',{name:'단지 정제 다시 실행'}))
    expect(startDataPipeline).toHaveBeenCalledWith('COMPLEX_REFINEMENT')
    expect(screen.getByRole('button',{name:'단지 정제 다시 실행'})).toBeDisabled()
    await act(() => started.resolve({ status, executionId:'new-run', type:'COMPLEX_REFINEMENT' } as Awaited<ReturnType<typeof startDataPipeline>>))
    const message = `단지 정제: ${label} · 처리 결과에 따라 오류 목록을 갱신합니다.`
    expect(screen.getByText(message)).toBeVisible()
    await act(() => initial.resolve({status:'IDLE'} as Awaited<ReturnType<typeof getDataPipelineStatus>>))
    expect(screen.getByText(message)).toBeVisible()
    expect(screen.getByRole('button',{name:'단지 정제 다시 실행'})).toBeEnabled()
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
  render(<MemoryRouter><FailureReviewPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('tab',{name:'공고 문제'}))
  const message = '공고 정제: 완료 · 처리 결과에 따라 오류 목록을 갱신합니다.'
  expect(await screen.findByText(message)).toBeVisible()
  await act(() => initial.resolve({status:'RUNNING',executionId:'old-complex-run',type:'COMPLEX_REFINEMENT'} as Awaited<ReturnType<typeof getDataPipelineStatus>>))
  expect(screen.getByText(message)).toBeVisible()
  expect(screen.getByRole('button',{name:'공고 정제 다시 실행'})).toBeEnabled()
})
