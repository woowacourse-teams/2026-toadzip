import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { FolderExchangePanel } from './FolderExchangePanel'
import type { ReviewTarget } from './documentReview'
const mocks = vi.hoisted(() => ({ pickExchangeDirectory: vi.fn(), writeExchangeRequest: vi.fn(), readExchangeResult: vi.fn(), readExchangeWorker: vi.fn(), readExchangeProgress: vi.fn(), cancelExchangeRequest: vi.fn() }))
vi.mock('./folderExchange', () => mocks)
const connection = vi.hoisted(() => ({ readExchangeConnection: vi.fn(), saveExchangeConnection: vi.fn() }))
vi.mock('./reviewBrowserStore', () => connection)
const target: ReviewTarget = { key: 'complexes:7:basic', version: 1, label: '단지', endpoint: '/', fields: [], current: {}, body: {} }
const document = { fileName: '공고.pdf', sha256: 'a'.repeat(64), totalPages: 1, pages: [] }
beforeEach(() => {
  vi.clearAllMocks(); vi.useFakeTimers()
  connection.readExchangeConnection.mockResolvedValue(null); connection.saveExchangeConnection.mockResolvedValue(undefined)
  mocks.cancelExchangeRequest.mockResolvedValue(undefined)
  mocks.pickExchangeDirectory.mockResolvedValue({ name: '연동 전용' })
  mocks.writeExchangeRequest.mockResolvedValue({ id: 'one', requestFile: 'request.json', resultFile: 'result.json' })
  mocks.readExchangeResult.mockResolvedValue(null)
  mocks.readExchangeWorker.mockResolvedValue({ workerId: 'worker', cancellation: true })
  mocks.readExchangeProgress.mockResolvedValue('waiting')
})

it('실제 처리 취소를 전달하고 취소 후 늦은 결과를 가져오지 않는다', async () => {
  const imported = await setup()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 전달·결과 대기' })) })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 처리 취소' })) })
  expect(mocks.cancelExchangeRequest).toHaveBeenCalledOnce()
  expect(screen.getByRole('button', { name: '처리 취소 확인 중' })).toBeDisabled()
  mocks.readExchangeResult.mockResolvedValue('{"proposals":[]}')
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(imported).not.toHaveBeenCalled()
  expect(screen.getByRole('alert')).toHaveTextContent('취소 요청 전에 결과가 발행')
})

it('이전 폴더 권한을 사용자 동작에서 다시 확인하고 선택창 없이 연결한다', async () => {
  const previous = { name: '이전 폴더', queryPermission: vi.fn().mockResolvedValue('prompt'), requestPermission: vi.fn().mockResolvedValue('granted') }
  connection.readExchangeConnection.mockResolvedValue(previous)
  render(<FolderExchangePanel target={target} document={document} sourceUrl="https://example.com" disabled={false} onResult={vi.fn()} onBusyChange={vi.fn()} />)
  fireEvent.click(screen.getByText('로컬 폴더로 에이전트 자동 연결'))
  await act(async () => {})
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '이전 연동 폴더 연결 · 이전 폴더' })) })
  expect(previous.requestPermission).toHaveBeenCalledWith({ mode: 'readwrite' })
  expect(mocks.pickExchangeDirectory).not.toHaveBeenCalled()
  expect(screen.getByText('처리 도구 연결됨')).toBeVisible()
})
afterEach(() => vi.useRealTimers())
async function setup() {
  const imported = vi.fn()
  render(<FolderExchangePanel target={target} document={document} sourceUrl="https://example.com" disabled={false} onResult={imported} onBusyChange={vi.fn()} />)
  fireEvent.click(screen.getByText('로컬 폴더로 에이전트 자동 연결'))
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '연동 폴더 선택' })) })
  return imported
}
it('폴더 선택 취소는 다시 시작하는 방법을 한국어로 안내한다', async () => {
  mocks.pickExchangeDirectory.mockRejectedValue(new DOMException('User aborted', 'AbortError'))
  await setup()
  expect(screen.getByText(/폴더 연결을 완료하지 못했습니다/)).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '연동 폴더 선택' })).toBeEnabled()
})
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

it('처리 도구가 없으면 요청을 보내지 않고 연결된 뒤에만 전송을 허용한다', async () => {
  mocks.readExchangeWorker.mockResolvedValue(null)
  await setup()
  expect(screen.getByRole('button', { name: '요청 전달·결과 대기' })).toBeDisabled()
  expect(mocks.writeExchangeRequest).not.toHaveBeenCalled()
  mocks.readExchangeWorker.mockResolvedValue({ workerId: 'worker' })
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '처리 도구 연결 다시 확인' })) })
  expect(screen.getByText('처리 도구 연결됨')).toBeVisible()
  expect(screen.getByRole('button', { name: '요청 전달·결과 대기' })).toBeEnabled()
})

it('접수 후 처리 상태를 표시하고 실패 재시도에는 새 요청을 작성한다', async () => {
  mocks.readExchangeProgress.mockResolvedValue('processing')
  const imported = await setup()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 전달·결과 대기' })) })
  expect(screen.getByText(/요청을 접수해 Codex로 처리 중/)).toBeVisible()
  mocks.readExchangeResult.mockRejectedValueOnce(new Error('로그인 실패'))
  await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
  expect(screen.getByRole('alert')).toHaveTextContent('로그인 실패')
  mocks.readExchangeResult.mockResolvedValue('{"proposals":[]}')
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '새 요청으로 다시 시도' })) })
  expect(mocks.writeExchangeRequest).toHaveBeenCalledTimes(2)
  expect(imported).toHaveBeenCalledOnce()
})

it('중단된 이전 처리 도구의 요청은 덮어쓰지 않고 새 요청을 안내한다', async () => {
  mocks.readExchangeProgress.mockResolvedValue('interrupted')
  const imported = await setup()
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '요청 전달·결과 대기' })) })
  expect(screen.getByRole('alert')).toHaveTextContent('도구가 중단되거나 바뀌었습니다')
  expect(screen.getByRole('button', { name: '새 요청으로 다시 시도' })).toBeEnabled()
  expect(imported).not.toHaveBeenCalled()
})
