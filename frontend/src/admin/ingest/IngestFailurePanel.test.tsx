import { MemoryRouter } from 'react-router'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { IngestFailurePanel } from './IngestFailurePanel'
import { getIngestFailures, type IngestFailure } from './api'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()), getIngestFailures: vi.fn(),
}))
const fetchFailures = vi.mocked(getIngestFailures)
const row: IngestFailure = {
  target: '공고 A · 공급행 3', sourceKey: 'A:3', reason: 'COMPLEX_NOT_FOUND',
  detail: '주소와 단지명에 일치하는 단지가 없습니다.', status: 'PENDING',
  occurredAt: '2026-09-26T03:00:00Z', occurrenceCount: 2, executionId: 'run-1', source: null, raw: {},
}
beforeEach(() => fetchFailures.mockReset())

it('실패한 원천 행과 사유 및 선택한 실행을 구분해 보여준다', async () => {
  fetchFailures.mockResolvedValue([row])
  render(<IngestFailurePanel initialCategory="announcement" executionId="run-1" />, {wrapper: MemoryRouter})
  expect(await screen.findByText('공고 A · 공급행 3')).toBeVisible()
  expect(screen.getByText('연결할 단지를 찾지 못함')).toBeVisible()
  expect(screen.getByText(row.detail)).toBeVisible()
  expect(screen.getByText('선택한 실행에서 발생')).toBeVisible()
  expect(fetchFailures).toHaveBeenCalledWith('announcement', false, 0)
})

it('조회 실패를 빈 목록으로 표시하지 않고 재시도한다', async () => {
  fetchFailures.mockRejectedValueOnce(new Error('접속 실패')).mockResolvedValueOnce([row])
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper: MemoryRouter})
  expect(await screen.findByRole('alert')).toHaveTextContent('접속 실패')
  expect(screen.queryByText('이 페이지에 표시할 실패가 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '목록 새로고침' }))
  expect(await screen.findByText(row.target)).toBeVisible()
})

it('페이지와 해결 이력을 조회하고 분류를 바꾸면 첫 페이지로 돌아간다', async () => {
  fetchFailures.mockResolvedValue(Array.from({ length: 20 }, () => row))
  render(<IngestFailurePanel initialCategory="announcement" executionId={null} />, {wrapper: MemoryRouter})
  await waitFor(() => expect(screen.getByRole('button', { name: '다음 페이지' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  await waitFor(() => expect(fetchFailures).toHaveBeenLastCalledWith('announcement', false, 1))
  fireEvent.change(screen.getByLabelText('실패 분류'), { target: { value: 'collection' } })
  await waitFor(() => expect(fetchFailures).toHaveBeenLastCalledWith('collection', false, 0))
  fireEvent.change(screen.getByLabelText('조회 범위'), { target: { value: 'history' } })
  await waitFor(() => expect(fetchFailures).toHaveBeenLastCalledWith('collection', true, 0))
})
