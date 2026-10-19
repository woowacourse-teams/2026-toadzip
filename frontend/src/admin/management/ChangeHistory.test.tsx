import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { blankRow } from '../supply/supplyCorrectionApi'
import { ChangeHistory } from './ChangeHistory'
import { getManagementHistory } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), getManagementHistory: vi.fn() }))
beforeEach(() => vi.mocked(getManagementHistory).mockReset())

it('원천 비교 수정은 변경값과 당시 원천 근거를 분리해 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 2, action: 'UPDATE_COMPARED', actor: '운영자', occurredAt: '2026-10-15T00:00:00Z',
    beforeValue: JSON.stringify({ totalHouseholdCount: 10, version: 1 }),
    afterValue: JSON.stringify({ totalHouseholdCount: 120, version: 2, comparisonEvidence: { note: '단지 전체 범위 확인', sources: [
      { category: 'MYHOME_COMPLEX', data: { name: '두꺼비 원천', collectedAt: '2026-10-08T00:00:00Z', raw: { hshld_co: 120 } } },
    ] } }),
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByText(/원천 비교 후 수정 · 운영자/))
  expect(screen.getByRole('table', { name: '수정한 항목' })).not.toHaveTextContent('comparisonEvidence')
  const evidence = screen.getByRole('region', { name: '확인한 DB 원천 근거' })
  expect(evidence).toHaveTextContent('단지 전체 범위 확인')
  expect(evidence).toHaveTextContent('두꺼비 원천')
  fireEvent.click(within(evidence).getByText('수정 당시 원천 값'))
  expect(within(evidence).getByRole('table')).toHaveTextContent('120')
})

it('변경 전후 값을 경로·값 표로 표시하고 원문 URL을 연결한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: '{"name":"기존 이름","supplyRows":[{"amount":0,"enabled":false}]}',
    afterValue: '{"name":"새 이름","originalUrl":"https://example.com/notice"}',
  }])
  const { container } = render(<ChangeHistory resource="announcements" id="7" version={2} />)
  fireEvent.click(await screen.findByText(/정보 수정 · 운영자/))
  expect(screen.getByRole('table', { name: '수정한 항목' })).toHaveTextContent('새 이름')
  fireEvent.click(screen.getByText('전체 기록 보기'))
  const before = screen.getByRole('table', { name: '변경 전' })
  expect(before).toHaveTextContent('기존 이름')
  expect(within(before).getByRole('row', { name: 'supplyRows[0].amount 0' })).toBeVisible()
  expect(within(before).getByRole('row', { name: 'supplyRows[0].enabled false' })).toBeVisible()
  expect(screen.getByRole('table', { name: '변경 후' })).toHaveTextContent('새 이름')
  expect(within(screen.getByRole('table', { name: '변경 후' })).getByRole('link', { name: 'https://example.com/notice' })).toBeVisible()
  expect(container.querySelector('pre')).toBeNull()
})

it('바뀐 값만 먼저 보여주고 PDF 근거를 읽기 쉬운 이름으로 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: JSON.stringify({ name: '같은 이름', totalHouseholdCount: 80, version: 1 }),
    afterValue: JSON.stringify({ name: '같은 이름', totalHouseholdCount: 120, version: 2,
      documentEvidence: { note: '원문 확인', citations: [{ field: 'totalHouseholdCount', page: 1, quote: '총 120세대' }] } }),
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByText(/정보 수정 · 운영자/))
  const changes = screen.getByRole('table', { name: '수정한 항목' })
  expect(changes).toHaveTextContent('전체 세대수')
  expect(changes).toHaveTextContent('80')
  expect(changes).toHaveTextContent('120')
  expect(changes).not.toHaveTextContent('같은 이름')
  expect(within(changes).getAllByRole('row')).toHaveLength(2)
  const evidence = screen.getByRole('table', { name: '확인한 PDF 근거' })
  expect(evidence).toHaveTextContent('원문 확인')
  expect(evidence).toHaveTextContent('총 120세대')
  expect(evidence).not.toHaveTextContent('citations[0]')
  expect(screen.getByText('전체 기록 보기').closest('details')).not.toHaveAttribute('open')
})

it('객체가 아닌 저장 이력도 값을 그대로 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: '기존 이력 텍스트', afterValue: 'null',
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByText(/정보 수정 · 운영자/))
  fireEvent.click(screen.getByText('전체 기록 보기'))
  expect(screen.getByRole('table', { name: '변경 전' })).toHaveTextContent('기존 이력 텍스트')
  expect(screen.getByRole('table', { name: '변경 후' })).toHaveTextContent('값 없음 (null)')
})

it('공급정보 일괄 보정 이력은 원천 전체 대신 바뀐 값과 공식 근거를 먼저 보여준다', async () => {
  const previous = { ...blankRow(0), id: 1, key: 'row:1', complexName: '한스빌', typeName: '26', householdCount: 10 }
  vi.mocked(getManagementHistory).mockResolvedValue([{ id: 4, action: 'CORRECT_SUPPLY', actor: '운영자', occurredAt: '2026-10-19T00:00:00Z',
    beforeValue: JSON.stringify({ rows: [previous] }),
    afterValue: JSON.stringify({ rows: [{ ...previous, householdCount: null }], reason: '모집수 미확정', sourceUrl: 'https://example.com/notice.pdf', sourcePage: '3쪽', operationId: '작업번호', sources: [{ raw: '긴 원천 자료' }] }),
  }])
  render(<ChangeHistory resource="announcements" id="7" version={2} />)
  fireEvent.click(await screen.findByText(/공급정보 일괄 보정 · 운영자/))
  const record = screen.getByRole('region', { name: '저장한 공급정보 보정' })
  expect(within(record).getByRole('heading', { name: '저장한 변경 1개' })).toBeVisible()
  expect(record).toHaveTextContent('모집수 미확정')
  expect(within(record).getByRole('link', { name: '공식 근거 ↗' })).toHaveAttribute('href', 'https://example.com/notice.pdf')
  expect(within(record).getByRole('table', { name: '보정 작업' })).not.toBeVisible()
})
