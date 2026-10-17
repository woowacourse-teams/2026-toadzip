import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexCoveragePage } from './ComplexCoveragePage'
const mocks = vi.hoisted(() => ({ compareOfficialComplex: vi.fn() }))
vi.mock('./complexCoverage', async original => ({ ...await original<typeof import('./complexCoverage')>(), ...mocks }))
const entry = { name: '공식 단지', roadAddress: '서울 중구 세종대로 110' }
const source = { sourceUrl: 'https://example.com/list', asOf: '2026-10-12', agencyCode: 'LH', complexes: [entry] }
beforeEach(() => { vi.clearAllMocks() })
function setup() {
  render(<MemoryRouter><ComplexCoveragePage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'JSON 가져오기' }))
  fireEvent.change(screen.getByLabelText('공식 단지 목록 JSON'), { target: { value: JSON.stringify(source) } })
  fireEvent.click(screen.getByRole('button', { name: '공식 목록과 대조' }))
}
it('기본 화면에서 JSON 없이 출처와 단지명을 입력해 대조한다', async () => {
  mocks.compareOfficialComplex.mockResolvedValue({ entry, status: 'MATCHED', candidates: [], reason: '등록된 단지' })
  render(<MemoryRouter><ComplexCoveragePage /></MemoryRouter>)
  expect(screen.queryByLabelText('공식 단지 목록 JSON')).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('목록 출처 URL'), { target: { value: source.sourceUrl } })
  fireEvent.change(screen.getByLabelText('자료 기준일'), { target: { value: source.asOf } })
  fireEvent.change(screen.getByLabelText('1번째 단지명'), { target: { value: entry.name } })
  fireEvent.change(screen.getByLabelText('1번째 도로명주소'), { target: { value: entry.roadAddress } })
  fireEvent.click(screen.getByRole('button', { name: '공식 목록과 대조' }))
  await screen.findByRole('heading', { name: '공식 단지 · 등록 확인' })
  expect(mocks.compareOfficialComplex).toHaveBeenCalledWith(source, entry, expect.any(AbortSignal))
})
it('직접 입력한 목록에 단지를 추가하고 제거할 수 있다', () => {
  render(<MemoryRouter><ComplexCoveragePage /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('1번째 단지명'), { target: { value: '첫 단지' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 한 줄 추가' }))
  expect(screen.getByLabelText('2번째 단지명')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '2번째 단지 삭제' }))
  expect(screen.queryByLabelText('2번째 단지명')).not.toBeInTheDocument()
  expect(screen.getByLabelText('1번째 단지명')).toHaveValue('첫 단지')
})
it('미등록 후보에만 수기 등록 경로를 제공하고 출처·기준일을 표시한다', async () => {
  mocks.compareOfficialComplex.mockResolvedValue({ entry, status: 'MISSING', candidates: [], reason: '공식 목록에만 존재' })
  setup()
  expect(await screen.findByRole('link', { name: '미등록 확인 후 단지 추가' })).toHaveAttribute('href', '/admin/complexes/new')
  expect(screen.getByText(/공식 기준일:/)).toHaveTextContent('2026-10-12')
  expect(screen.getByRole('link', { name: /example.com/ })).toHaveAttribute('href', source.sourceUrl)
})
it('조회 실패에 등록 버튼을 제공하지 않고 재조회할 수 있다', async () => {
  mocks.compareOfficialComplex.mockResolvedValue({ entry, status: 'ERROR', candidates: [], reason: '조회 실패' })
  setup()
  await screen.findByRole('heading', { name: '공식 단지 · 조회 실패' })
  expect(screen.queryByRole('link', { name: '미등록 확인 후 단지 추가' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '공식 목록과 대조' })).toBeEnabled()
})
it('중지하거나 화면을 닫으면 요청을 취소하고 늦게 온 결과를 반영하지 않는다', async () => {
  let resolve!: (value: unknown) => void
  mocks.compareOfficialComplex.mockImplementation(() => new Promise(done => { resolve = done }))
  setup()
  const signal = mocks.compareOfficialComplex.mock.calls[0][2] as AbortSignal
  fireEvent.click(screen.getByRole('button', { name: '대조 중지' }))
  expect(signal.aborted).toBe(true)
  resolve({ entry, status: 'MISSING', candidates: [], reason: '늦은 결과' })
  await waitFor(() => expect(screen.queryByText('늦은 결과')).not.toBeInTheDocument())
  expect(screen.getByRole('button', { name: '공식 목록과 대조' })).toBeEnabled()
})
