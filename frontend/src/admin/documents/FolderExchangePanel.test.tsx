import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { FolderExchangePanel } from './FolderExchangePanel'
import type { ReviewTarget } from './documentReview'
const mocks = vi.hoisted(() => ({ pickExchangeDirectory: vi.fn(), writeExchangeRequest: vi.fn(), readExchangeResult: vi.fn() }))
vi.mock('./folderExchange', () => mocks)
const target: ReviewTarget = { key: 'complexes:7:basic', version: 1, label: '단지', endpoint: '/', fields: [], current: {}, body: {} }
const document = { fileName: '공고.pdf', sha256: 'a'.repeat(64), totalPages: 1, pages: [] }
beforeEach(() => {
  vi.clearAllMocks(); vi.useFakeTimers()
  mocks.pickExchangeDirectory.mockResolvedValue({ name: '연동 전용' })
  mocks.writeExchangeRequest.mockResolvedValue({ id: 'one', requestFile: 'request.json', resultFile: 'result.json' })
  mocks.readExchangeResult.mockResolvedValue(null)
})
afterEach(() => vi.useRealTimers())
async function setup() {
  const imported = vi.fn()
  render(<FolderExchangePanel target={target} document={document} sourceUrl="https://example.com" disabled={false} onResult={imported} onBusyChange={vi.fn()} />)
  fireEvent.click(screen.getByText('로컬 폴더로 에이전트 자동 연결'))
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '연동 폴더 선택' })) })
  return imported
}
it('결과가 오면 자동 검증 결과를 전달하고 DB 저장은 수행하지 않는다', async () => {
  mocks.readExchangeResult.mockResolvedValue('{"proposals":[]}')
  const imported = await setup()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 전달·결과 대기' })) })
  expect(imported).toHaveBeenCalledWith('{"proposals":[]}')
  expect(screen.getByText(/검증해 가져왔습니다/)).toBeVisible()
})
it('대기 중지·화면 해제 뒤 도착한 결과는 가져오지 않는다', async () => {
  let resolve!: (value: string) => void
  mocks.readExchangeResult.mockImplementation(() => new Promise(done => { resolve = done }))
  const imported = await setup()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 전달·결과 대기' })) })
  fireEvent.click(screen.getByRole('button', { name: '결과 대기 중지' }))
  await act(async () => { resolve('{"proposals":[]}'); await vi.advanceTimersByTimeAsync(2000) })
  expect(imported).not.toHaveBeenCalled()
  expect(screen.getByText(/결과 대기를 중지했습니다/)).toBeVisible()
})
it('10분 동안 결과가 없으면 상태를 안내하고 대기를 종료한다', async () => {
  await setup()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 전달·결과 대기' })) })
  await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 1500) })
  expect(screen.getByRole('alert')).toHaveTextContent('10분')
  expect(screen.queryByRole('button', { name: '결과 대기 중지' })).not.toBeInTheDocument()
})
