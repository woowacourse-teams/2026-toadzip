import { act, fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { FailureSourceRecovery } from './FailureSourceRecovery'
import { refreshLhAnnouncement } from './api'
import type { FailureReview } from './failureReviewApi'

vi.mock('./api', async original => ({...(await original<typeof import('./api')>()), refreshLhAnnouncement:vi.fn()}))
const row: FailureReview = {id:1, category:'enrichment',target:'공고',sourceKey:'key',source:'LH_ANNOUNCEMENT',
  reason:'ANNOUNCEMENT_NOT_FOUND',detail:'연결 없음',status:'PENDING',occurredAt:'2026-10-18T00:00:00Z',
  occurrenceCount:1,recurrenceCount:0,lastResolvedAt:null,executionId:null,
  raw:{sourceAnnouncementIdentifier:'21395',panId:'PAN-7'},productLinkStatus:'NOT_FOUND',product:null}
const report = {operation:'LH_ANNOUNCEMENT_SUPPLY',externalApiCallCount:1,successfulRequestCount:1,
  storedRowCount:3,failedRequestCount:0,rateLimitedRequestCount:0,selectionFailedRequestCount:0,skippedRequestCount:0}
beforeEach(() => vi.resetAllMocks())

it.each(['details','supplies'] as const)('%s 재수집은 선택한 마이홈 공고만 요청하고 결과를 다시 확인한다', async source => {
  const onProcessed = vi.fn(), onBusy = vi.fn()
  let complete!: (value: typeof report) => void
  vi.mocked(refreshLhAnnouncement).mockReturnValue(new Promise(resolve => {complete = resolve}))
  render(<FailureSourceRecovery row={row} disabled={false} onProcessed={onProcessed} onBusy={onBusy}/>)
  fireEvent.click(screen.getByRole('button',{name:source === 'details' ? '이 공고 LH 상세 재수집' : '이 공고 LH 공급 재수집'}))
  expect(refreshLhAnnouncement).toHaveBeenCalledExactlyOnceWith(source,'21395')
  screen.getAllByRole('button').forEach(button => expect(button).toBeDisabled())
  expect(onBusy).toHaveBeenLastCalledWith(true)
  await act(async () => complete(report))
  expect(screen.getByRole('status')).toHaveTextContent('원천 반영 후 정제가 필요합니다.')
  expect(onProcessed).toHaveBeenCalledOnce()
  expect(onBusy).toHaveBeenLastCalledWith(false)
})

it('대상이 없거나 요청에 실패하면 해결됐다고 표시하지 않고 재시도할 수 있다', async () => {
  const onProcessed = vi.fn()
  vi.mocked(refreshLhAnnouncement).mockResolvedValueOnce({...report,externalApiCallCount:0,successfulRequestCount:0,storedRowCount:0})
    .mockRejectedValueOnce(new Error('공급행 감소로 기존 원천을 보존했습니다.'))
  render(<FailureSourceRecovery row={row} disabled={false} onProcessed={onProcessed} onBusy={vi.fn()}/>)
  fireEvent.click(screen.getByRole('button',{name:'이 공고 LH 공급 재수집'}))
  expect(await screen.findByRole('status')).toHaveTextContent('재조회할 LH 요청이 없습니다.')
  fireEvent.click(screen.getByRole('button',{name:'이 공고 LH 공급 재수집'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('공급행 감소로 기존 원천을 보존했습니다.')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(screen.getByRole('button',{name:'이 공고 LH 공급 재수집'})).toBeEnabled()
  expect(onProcessed).toHaveBeenCalledTimes(2)
})
