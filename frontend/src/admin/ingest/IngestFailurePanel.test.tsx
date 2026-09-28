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

it('수집 요청은 지역명과 조치로 표시하고 요청 문자열은 상세에 보존한다', async () => {
  const request = 'brtcCode=47&signguCode=130&pageNo=1&numOfRows=500'
  fetchFailures.mockResolvedValue([{...row,target:request,sourceKey:request,source:'MYHOME_COMPLEX',
    reason:'ExternalDataRequestException',detail:'HTTP 429, resultCode=23, 초당 서비스 요청제한 횟수 초과 에러',
    executionId:'old-run',raw:{requestDescription:request}}])
  render(<IngestFailurePanel initialCategory="collection" executionId="run-1" />, {wrapper:MemoryRouter})
  expect(await screen.findByText('경상북도 경주시')).toBeVisible()
  expect(screen.getByText('마이홈 단지 수집')).toBeVisible()
  expect(screen.getByText('요청 1페이지 · 페이지당 500건')).toBeVisible()
  expect(screen.getByText('요청 속도 제한')).toBeVisible()
  expect(screen.getByText('다른 실행 기록')).toBeVisible()
  expect(screen.getByText('이 페이지 1건 · 선택한 실행에서 발생 0건')).toBeVisible()
  expect(screen.getByText(`원천 키: ${request}`)).not.toBeVisible()
  fireEvent.click(screen.getByText('요청·오류 원문 보기'))
  expect(screen.getByText(`원천 키: ${request}`)).toBeVisible()
  expect(screen.getByText(/미해결 · 발생/)).toBeVisible()
})
