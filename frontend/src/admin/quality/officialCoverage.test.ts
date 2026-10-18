import { beforeEach, expect, it, vi } from 'vitest'
import { loadOfficialCoverage, prepareOfficialCoverage } from './officialCoverage'
import type { SourceRow } from '../ingest/sourceApi'
const mocks = vi.hoisted(() => ({ getSourceData: vi.fn() }))
vi.mock('../ingest/sourceApi', () => mocks)
const row: SourceRow = { id: 1, sourceKey: 'one', name: '단지', sourceUrl: 'https://example.com/list', originalUrl: null,
  collectedAt: '2026-10-16T00:00:00Z', sourceUpdatedAt: null,
  raw: { instt_nm: '한국토지주택공사', hsmp_nm: '단지', rn_adres: '서울 중구 세종대로 110' } }
beforeEach(() => vi.clearAllMocks())
it('전체 페이지의 중복을 제거하고 비교 불가 자료를 별도 집계한다', async () => {
  const rows = [row, { ...row, id: 2, sourceKey: 'two' }, { ...row, id: 3, sourceKey: 'three', raw: { ...row.raw, rn_adres: '' } }]
  mocks.getSourceData.mockImplementation((_category, _keyword, page) => Promise.resolve({
    items: page === 0 ? rows.slice(0, 2) : rows.slice(2), page, totalElements: 3, totalPages: 2, hasNext: page === 0,
  }))
  const result = await loadOfficialCoverage('MYHOME_COMPLEX', 'LH', '', new AbortController().signal, vi.fn())
  expect(result.list.complexes).toHaveLength(2)
  expect(result.list.complexes[1].roadAddress).toBe('')
  expect(result).toMatchObject({ rows: 3, duplicates: 1, skipped: 0 })
  expect(mocks.getSourceData).toHaveBeenCalledTimes(4)
})
it('조회 중 값이 바뀌거나 페이지가 반복되면 전체 목록으로 확정하지 않는다', async () => {
  mocks.getSourceData.mockResolvedValueOnce({ items: [row], page: 0, totalElements: 1, totalPages: 1, hasNext: false })
    .mockResolvedValueOnce({ items: [{ ...row, name: '변경' }], page: 0, totalElements: 1, totalPages: 1, hasNext: false })
  await expect(loadOfficialCoverage('MYHOME_COMPLEX', 'LH', '', new AbortController().signal, vi.fn())).rejects.toThrow('변경')
})
it('LH 상세의 도로명주소를 지원하고 지번은 주소 확인 필요로 보존하며 비활성 자료는 제외한다', () => {
  const valid = { ...row, raw: { complex_name: 'LH 단지', address: '서울 중구 세종대로 110' } }
  const result = prepareOfficialCoverage('LH_ANNOUNCEMENT_DETAIL', 'LH', [valid,
    { ...valid, raw: { complex_name: '지번 단지', address: '서울 중구 태평로1가 31' } },
    { ...valid, raw: { complex_name: '비활성', address: '서울 중구 세종대로 110', active: false } }])
  expect(result.list.complexes).toEqual([{ name: 'LH 단지', roadAddress: '서울 중구 세종대로 110' }, { name: '지번 단지', roadAddress: '' }])
  expect(result.skipped).toBe(1)
})
it('주소 없는 LH 임대 목록도 이름을 보존하고 원천 행 ID를 서비스 식별자로 혼용하지 않는다', () => {
  const result = prepareOfficialCoverage('LH_LEASE_CATALOG', 'LH', [{ ...row, raw: { complex_label: '임대 단지' } }])
  expect(result.list.complexes).toEqual([{ name: '임대 단지', roadAddress: '' }])
})
it('중지는 추가 조회와 불완전한 결과 전달을 막는다', async () => {
  const controller = new AbortController(); controller.abort()
  await expect(loadOfficialCoverage('MYHOME_COMPLEX', 'LH', '', controller.signal, vi.fn())).rejects.toThrow()
  expect(mocks.getSourceData).not.toHaveBeenCalled()
})
