import { beforeEach, expect, it, vi } from 'vitest'
import { compareOfficialComplex, parseOfficialList } from './complexCoverage'
const mocks = vi.hoisted(() => ({ getManagementPage: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), ...mocks }))
const entry = { name: '두꺼비', roadAddress: '서울 중구 세종대로 110' }
const source = { sourceUrl: 'https://example.com/complexes', asOf: '2026-10-12', agencyCode: 'LH', complexes: [entry] }
const candidate = { id: 7, name: entry.name, subtitle: entry.roadAddress, provider: 'LH', rental: 'HAPPY_HOUSING',
  deleted: false, modified: false, reviewRequired: false, updatedAt: null }
beforeEach(() => { vi.clearAllMocks(); mocks.getManagementPage.mockResolvedValue({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 }) })
it('출처·기준일·기관·중복·규모를 검증한다', () => {
  expect(parseOfficialList(JSON.stringify(source))).toEqual(source)
  for (const invalid of [{ ...source, sourceUrl: 'javascript:alert(1)' }, { ...source, asOf: '2026-02-30' },
    { ...source, complexes: [entry, entry] }, { ...source, complexes: Array.from({ length: 51 }, (_, index) => ({ ...entry, name: String(index) })) }]) {
    expect(() => parseOfficialList(JSON.stringify(invalid))).toThrow()
  }
})
it('이름·주소가 같으면 등록을 확인하고 비슷한 후보는 자동 연결하지 않는다', async () => {
  mocks.getManagementPage.mockResolvedValue({ items: [candidate], hasNext: false })
  expect((await compareOfficialComplex(source, entry, new AbortController().signal)).status).toBe('MATCHED')
  mocks.getManagementPage.mockResolvedValue({ items: [{ ...candidate, name: '다른 두꺼비' }], hasNext: false })
  expect((await compareOfficialComplex(source, entry, new AbortController().signal)).status).toBe('REVIEW')
})
it('공식 목록에만 있는 단지는 미등록 후보로 남긴다', async () => {
  expect((await compareOfficialComplex(source, entry, new AbortController().signal)).status).toBe('MISSING')
  expect(mocks.getManagementPage.mock.calls.some(call => call[1].get('deleted') === 'true')).toBe(true)
})
it('휴지통 후보와 조회 실패를 미등록으로 처리하지 않는다', async () => {
  mocks.getManagementPage.mockImplementation((_resource, params) => Promise.resolve({ items: params.get('deleted') === 'true' ? [{ ...candidate, deleted: true }] : [], hasNext: false }))
  expect((await compareOfficialComplex(source, entry, new AbortController().signal)).status).toBe('DELETED')
  mocks.getManagementPage.mockRejectedValue(new Error('연결 실패'))
  expect(await compareOfficialComplex(source, entry, new AbortController().signal)).toMatchObject({ status: 'ERROR', reason: '연결 실패' })
})
it('검색 결과 상한으로 대조가 불완전하면 미등록 판단을 막는다', async () => {
  mocks.getManagementPage.mockResolvedValue({ items: [], hasNext: true })
  expect((await compareOfficialComplex(source, entry, new AbortController().signal)).status).toBe('ERROR')
})
