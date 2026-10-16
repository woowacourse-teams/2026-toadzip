import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { OfficialSourceWorkflow } from './OfficialSourceWorkflow'

const mocks = vi.hoisted(() => ({ sources: vi.fn(), start: vi.fn(), status: vi.fn() }))
vi.mock('../ingest/sourceApi', async original => ({ ...await original<typeof import('../ingest/sourceApi')>(), getSourceData: mocks.sources }))
vi.mock('../ingest/api', () => ({ startDataPipeline: mocks.start, getDataPipelineStatus: mocks.status }))
beforeEach(() => vi.clearAllMocks())
it('공식 DB 목록에서 기관·주소를 확인하고 주택형별 중복을 제거해 대조 목록으로 가져온다', async () => {
  const row = { id: 1, sourceKey: 'key', sourceUrl: 'https://example.com/official-api', collectedAt: '2026-10-16T00:00:00Z', raw: { instt_nm: '한국토지주택공사', hsmp_nm: '공식 단지', rn_adres: '서울 중구 세종대로 110' } }
  mocks.sources.mockResolvedValue({ items: [row, { ...row, id: 2 }, { ...row, id: 3, raw: { ...row.raw, rn_adres: '' } }], page: 0, totalElements: 3, totalPages: 1, hasNext: false })
  const imported = vi.fn()
  render(<MemoryRouter><OfficialSourceWorkflow onImport={imported} disabled={false} /></MemoryRouter>)
  fireEvent.click(screen.getByText('공식 API 자료로 목록 준비하기'))
  fireEvent.click(screen.getByRole('button', { name: '저장 자료 불러오기' }))
  fireEvent.click(await screen.findByRole('button', { name: '이 페이지를 대조 목록으로 가져오기' }))
  expect(imported).toHaveBeenCalledWith({ sourceUrl: row.sourceUrl, asOf: '2026-10-16', agencyCode: 'LH', complexes: [{ name: '공식 단지', roadAddress: '서울 중구 세종대로 110' }] })
  expect(mocks.start).not.toHaveBeenCalled()
})
it('수집 실패를 숨기지 않고 저장된 범위만 대조한다고 안내한다', async () => {
  mocks.start.mockResolvedValue({ status: 'FAILED', currentStepName: null, failure: { message: '공식 API 응답 실패' } })
  render(<MemoryRouter><OfficialSourceWorkflow onImport={vi.fn()} disabled={false} /></MemoryRouter>)
  fireEvent.click(screen.getByText('공식 API 자료로 목록 준비하기'))
  fireEvent.click(screen.getByRole('button', { name: '공식 단지 API 수집' }))
  expect(await screen.findByText('공식 API 응답 실패')).toBeVisible()
  expect(screen.getByText(/현재 DB에 저장된 범위만 대조/)).toBeVisible()
  expect(screen.queryByRole('button', { name: '이 페이지를 대조 목록으로 가져오기' })).not.toBeInTheDocument()
})
it('전체 가져오기에서 50개를 넘는 단지와 제외 건수를 전달한다', async () => {
  mocks.sources.mockImplementation((_category, _keyword, page) => Promise.resolve({ items: Array.from({ length: 20 }, (_, index) => ({
    id: page * 20 + index + 1, sourceKey: String(page * 20 + index), name: '단지', sourceUrl: 'https://example.com/list', collectedAt: '2026-10-16T00:00:00Z',
    raw: { instt_nm: '한국토지주택공사', hsmp_nm: `단지${page * 20 + index}`, rn_adres: '서울 중구 세종대로 110' },
  })), page, totalElements: 60, totalPages: 3, hasNext: page < 2 }))
  const imported = vi.fn()
  render(<MemoryRouter><OfficialSourceWorkflow onImport={imported} disabled={false} /></MemoryRouter>)
  fireEvent.click(screen.getByText('공식 API 자료로 목록 준비하기'))
  fireEvent.click(screen.getByRole('button', { name: '검색 범위 전체 가져오기' }))
  expect(await screen.findByText(/저장 자료 60행 전체 조회 완료/)).toBeVisible()
  expect(imported.mock.calls[0][0].complexes).toHaveLength(60)
})
