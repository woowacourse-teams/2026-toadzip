import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ChangeHistory } from './ChangeHistory'
import { getManagementHistory } from './api'

vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), getManagementHistory: vi.fn() }))
beforeEach(() => vi.mocked(getManagementHistory).mockReset())

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
