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
  const before = screen.getByRole('table', { name: '변경 전' })
  expect(before).toHaveTextContent('기존 이름')
  expect(within(before).getByRole('row', { name: 'supplyRows[0].amount 0' })).toBeVisible()
  expect(within(before).getByRole('row', { name: 'supplyRows[0].enabled false' })).toBeVisible()
  expect(screen.getByRole('table', { name: '변경 후' })).toHaveTextContent('새 이름')
  expect(screen.getByRole('link', { name: 'https://example.com/notice' })).toBeVisible()
  expect(container.querySelector('pre')).toBeNull()
})

it('객체가 아닌 저장 이력도 값을 그대로 표시한다', async () => {
  vi.mocked(getManagementHistory).mockResolvedValue([{
    id: 1, action: 'UPDATE', actor: '운영자', occurredAt: '2026-10-01T00:00:00Z',
    beforeValue: '기존 이력 텍스트', afterValue: 'null',
  }])
  render(<ChangeHistory resource="complexes" id="7" version={2} />)
  fireEvent.click(await screen.findByText(/정보 수정 · 운영자/))
  expect(screen.getByRole('table', { name: '변경 전' })).toHaveTextContent('기존 이력 텍스트')
  expect(screen.getByRole('table', { name: '변경 후' })).toHaveTextContent('값 없음 (null)')
})
