import { MemoryRouter } from 'react-router'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { LhAnnouncementQualityPanel } from './LhAnnouncementQualityPanel'
import type { DataPipelineExecution, LhAnnouncementQuality } from './api'

const apiMocks = vi.hoisted(() => ({
  getLhAnnouncementQuality: vi.fn(),
  applyVerifiedLhSupplyReplacement: vi.fn(),
}))

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  apiMocks.getLhAnnouncementQuality.mockReset()
  apiMocks.applyVerifiedLhSupplyReplacement.mockReset()
  apiMocks.getLhAnnouncementQuality.mockResolvedValue(quality())
  apiMocks.applyVerifiedLhSupplyReplacement.mockResolvedValue(undefined)
})

it('수집 성공률과 제품 충족률 및 보류 사유를 서로 구분해 표시한다', async () => {
  render(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={collectionExecution()} /></MemoryRouter>)

  expect((await screen.findAllByText('1/2 (50%)')).length).toBeGreaterThanOrEqual(2)
  expect(screen.getAllByText('1/3 (33%)')).toHaveLength(2)
  expect(screen.getByText('주택형 후보 모호: 2행')).toBeVisible()
  expect(screen.getByText(/빈 응답·공급행 감소로 교체하지 않은 현재 요청 수/)).toBeVisible()
  expect(screen.getByText('LH 신규 금액 미제공: 1건')).toBeVisible()
  expect(screen.getByText('원천 선택 충돌 1건')).toBeVisible()
  expect(screen.getByText('공급 원천 확보')).toBeVisible()
  expect(screen.getByText('상세 원천 확보')).toBeVisible()
  expect(screen.getByText(/과거 성공도 포함하며 이번 실행의 성공률이나 현재 값의 최신성을 뜻하지 않습니다/)).toBeVisible()
  expect(screen.getAllByText(/^가장 최근 실제 수집 /)).toHaveLength(2)
  expect(screen.getByText('주택형·공고 매핑 보류: 1건')).toBeVisible()
  expect(screen.getByText('미연결 LH 임대 공고 조사 후보 4건')).toBeVisible()
  fireEvent.click(screen.getByText('미연결 LH 임대 공고 조사 후보 4건'))
  expect(screen.getByText('pan-2')).toBeVisible()
  expect(screen.getByText('03:06:063:pan-2')).toBeVisible()
})

it('공식 근거 확인 후 선택한 지문의 공급 재조회만 요청한다', async () => {
  render(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={collectionExecution()} /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '확인한 정정 반영' }))
  fireEvent.change(screen.getByLabelText('마이홈 공고 ID'), { target: { value: 'myhome-1' } })
  fireEvent.change(screen.getByLabelText('LH 공식 공고문 URL'), {
    target: { value: 'https://apply.lh.or.kr/notice' },
  })
  fireEvent.change(screen.getByLabelText('철회·정정 확인 사유'), { target: { value: '24형 철회 확인' } })
  expect(screen.getByRole('button', { name: '승인하고 공급 다시 조회' })).toBeDisabled()
  fireEvent.click(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.'))
  fireEvent.click(screen.getByRole('button', { name: '승인하고 공급 다시 조회' }))

  await waitFor(() => expect(apiMocks.applyVerifiedLhSupplyReplacement).toHaveBeenCalledWith('myhome-1', {
    requestDescription: 'PAN_ID=pan-1',
    proposedFingerprint: 'a'.repeat(64),
    evidenceUrl: 'https://apply.lh.or.kr/notice',
    reason: '24형 철회 확인',
  }))
  expect(await screen.findByText(/근거 승인 후 LH 공급을 다시 조회했습니다/)).toBeVisible()
})

it('품질 조회 실패를 빈 결과로 표시하지 않는다', async () => {
  apiMocks.getLhAnnouncementQuality.mockRejectedValue(new Error('품질 조회 실패'))
  render(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={collectionExecution()} /></MemoryRouter>)

  expect(await screen.findByRole('alert')).toHaveTextContent('품질 조회 실패')
  expect(screen.queryByText('대상 없음')).not.toBeInTheDocument()
})

it('승인 대상을 바꾸면 이전 대상의 근거와 확인 상태를 초기화한다', async () => {
  const data = quality()
  apiMocks.getLhAnnouncementQuality.mockResolvedValue({
    ...data,
    heldRequests: [...data.heldRequests, {
      requestDescription: 'PAN_ID=pan-2', reason: '다른 공고의 공급 감소',
      lastOccurredAt: '2026-09-28T00:00:00Z', proposedFingerprint: 'b'.repeat(64),
    }],
  })
  render(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={collectionExecution()} /></MemoryRouter>)
  const choices = await screen.findAllByRole('button', { name: '확인한 정정 반영' })
  fireEvent.click(choices[0])
  fireEvent.change(screen.getByLabelText('마이홈 공고 ID'), { target: { value: 'myhome-1' } })
  fireEvent.change(screen.getByLabelText('LH 공식 공고문 URL'), {
    target: { value: 'https://apply.lh.or.kr/notice-1' },
  })
  fireEvent.change(screen.getByLabelText('철회·정정 확인 사유'), { target: { value: '첫 번째 공고 확인' } })
  fireEvent.click(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.'))

  fireEvent.click(choices[1])

  expect(screen.getByText('선택한 요청: PAN_ID=pan-2')).toBeVisible()
  expect(screen.getByLabelText('마이홈 공고 ID')).toHaveValue('')
  expect(screen.getByLabelText('LH 공식 공고문 URL')).toHaveValue('')
  expect(screen.getByLabelText('철회·정정 확인 사유')).toHaveValue('')
  expect(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.')).not.toBeChecked()
  expect(screen.getByRole('button', { name: '승인하고 공급 다시 조회' })).toBeDisabled()
  expect(apiMocks.applyVerifiedLhSupplyReplacement).not.toHaveBeenCalled()
})

it('동일한 요청의 승인 지문이 바뀌면 다시 선택하고 근거를 확인해야 한다', async () => {
  render(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={collectionExecution()} /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '확인한 정정 반영' }))
  fireEvent.change(screen.getByLabelText('마이홈 공고 ID'), { target: { value: 'myhome-1' } })
  fireEvent.change(screen.getByLabelText('LH 공식 공고문 URL'), { target: { value: 'https://apply.lh.or.kr/notice' } })
  fireEvent.change(screen.getByLabelText('철회·정정 확인 사유'), { target: { value: '기존 지문 확인' } })
  fireEvent.click(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.'))
  const next = quality()
  apiMocks.getLhAnnouncementQuality.mockResolvedValue({
    ...next, heldRequests: next.heldRequests.map((item) => ({ ...item, proposedFingerprint: 'b'.repeat(64) })),
  })
  fireEvent.click(screen.getByRole('button', { name: '다시 조회' }))
  expect(await screen.findByRole('status')).toHaveTextContent('선택한 보류 요청이 변경되었습니다.')
  expect(screen.queryByRole('button', { name: '승인하고 공급 다시 조회' })).not.toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: '확인한 정정 반영' }))
  expect(screen.getByLabelText('마이홈 공고 ID')).toHaveValue('')
  expect(screen.getByLabelText('LH 공식 공고문 URL')).toHaveValue('')
  expect(screen.getByLabelText('철회·정정 확인 사유')).toHaveValue('')
  expect(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.')).not.toBeChecked()
  expect(apiMocks.applyVerifiedLhSupplyReplacement).not.toHaveBeenCalled()
})

it('겹친 품질 조회의 늦은 응답으로 철회된 확인을 되살리지 않는다', async () => {
  const view = render(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={collectionExecution()} /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '확인한 정정 반영' }))
  fireEvent.click(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.'))
  let resolveLate!: (value: LhAnnouncementQuality) => void
  const late = new Promise<LhAnnouncementQuality>((resolve) => { resolveLate = resolve })
  const next = quality()
  apiMocks.getLhAnnouncementQuality.mockReturnValueOnce(late).mockResolvedValueOnce({
    ...next, heldRequests: next.heldRequests.map((item) => ({ ...item, proposedFingerprint: 'b'.repeat(64) })),
  })
  fireEvent.click(screen.getByRole('button', { name: '다시 조회' }))
  view.rerender(<MemoryRouter><LhAnnouncementQualityPanel collectionExecution={{
    ...collectionExecution(), status: 'COMPLETED_WARNINGS',
  }} /></MemoryRouter>)
  expect(await screen.findByRole('status')).toHaveTextContent('선택한 보류 요청이 변경되었습니다.')
  expect(screen.queryByRole('button', { name: '승인하고 공급 다시 조회' })).not.toBeInTheDocument()
  await act(async () => { resolveLate(quality()) })
  expect(screen.queryByRole('button', { name: '승인하고 공급 다시 조회' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '확인한 정정 반영' }))
  expect(screen.getByText(`승인할 응답 지문: ${'b'.repeat(64)}`)).toBeVisible()
  expect(screen.getByLabelText('공고문에서 실제 철회·정정을 확인했습니다.')).not.toBeChecked()
})

function quality(): LhAnnouncementQuality {
  return {
    observedAt: '2026-09-28T00:00:00Z',
    connection: { total: 3, complexLinked: 2, housingTypeLinked: 1,
      unlinkedReasons: { '주택형 후보 모호': 2 } },
    amounts: { total: 3, fulfilled: 1 },
    schedules: { total: 1, reviewed: 1, withApplicationSchedule: 1 },
    supplyCollection: { totalRequests: 2, collectedRequests: 1, latestCollectedAt: '2026-09-28T00:00:00Z' },
    detailCollection: { totalRequests: 2, collectedRequests: 2, latestCollectedAt: '2026-09-28T00:00:00Z' },
    unlinkedLhLeaseCatalogCount: 4,
    unlinkedLhCandidates: [{ panId: 'pan-2', sourceKey: '03:06:063:pan-2',
      changedAt: '2026-09-28T00:00:00Z' }],
    preservedSourceRequestCount: 1,
    preservedReasons: { IncompleteLhSupplyReplacementException: 1 },
    preservedAmountTargetCount: 2,
    preservedAmountReasons: { LH_AMOUNT_NOT_PROVIDED: 1, MYHOME_MAPPING_REJECTED: 1 },
    heldRequests: [{ requestDescription: 'PAN_ID=pan-1', reason: '기존 공급행 감소',
      lastOccurredAt: '2026-09-28T00:00:00Z', proposedFingerprint: 'a'.repeat(64) }],
  }
}

function collectionExecution(): DataPipelineExecution {
  return {
    executionId: 'run-1', type: 'ANNOUNCEMENT_COLLECTION', status: 'COMPLETED',
    currentStepName: null, currentStepIndex: 0, totalStepCount: 4,
    completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
    completedStepResults: [{ step: 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
      stepName: 'LH 공고 공급 원본 수집', report: { successfulRequestCount: 1, failedRequestCount: 2, selectionFailedRequestCount: 1 } }],
  }
}
