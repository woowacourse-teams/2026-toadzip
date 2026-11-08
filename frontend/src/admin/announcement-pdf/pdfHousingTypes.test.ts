import { beforeEach, expect, test, vi } from 'vitest'
import { createPdfHousingTypes } from './pdfHousingTypes'
import type { ExtractedRow } from './extraction'

const mocks = vi.hoisted(() => ({ requestManagementApi: vi.fn(), getManagementDetail: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(),
  requestManagementApi: mocks.requestManagementApi, getManagementDetail: mocks.getManagementDetail }))
vi.mock('../management/managementContract', async original => ({ ...await original<typeof import('../management/managementContract')>(),
  parseManagementDetail: (value: unknown) => value }))

beforeEach(() => {
  mocks.requestManagementApi.mockReset().mockResolvedValue({ data: { version: 4 } })
  mocks.getManagementDetail.mockReset().mockResolvedValue({ data: { version: 3 } })
})

test('공고문 행으로 만든 주택형은 공고 값으로 표시해 관리자 수정 보호를 받지 않게 한다', async () => {
  const row = { typeName: '059.6300A', values: { exclusiveArea: 59.63, supplyArea: 78.9091 }, subtypes: [] } as unknown as ExtractedRow
  await createPdfHousingTypes(3506, [row], [], new AbortController().signal)
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/3506/housing-types', 'POST',
    { version: 3, name: '059.6300A', exclusiveArea: 59.63, supplyArea: 78.9091, householdCount: null, fromAnnouncement: true },
    expect.any(AbortSignal))
})

const row = (name: string, area: number) => ({ typeName: name, values: { exclusiveArea: area, supplyArea: null }, subtypes: [] }) as unknown as ExtractedRow

test('기존 전용면적과 PDF 안의 같은 면적을 재생성하지 않고 서로 다른 면적에 응답 버전을 이어 쓴다', async () => {
  mocks.requestManagementApi.mockResolvedValueOnce({ data: { version: 4 } }).mockResolvedValueOnce({ data: { version: 5 } })
  await createPdfHousingTypes(3506, [row('26A', 26.12), row('26A(고령자)', 26.12), row('36A', 36.5), row('46A', 46.1)],
    [{ name: '기존형', exclusiveArea: 26.12 }], new AbortController().signal)
  expect(mocks.requestManagementApi.mock.calls.map(call => call[2])).toEqual([
    expect.objectContaining({ name: '36A', exclusiveArea: 36.5, version: 3 }),
    expect.objectContaining({ name: '46A', exclusiveArea: 46.1, version: 4 }),
  ])
})

test('같은 면적의 다른 이름은 첫 형명으로 한 번만 생성한다', async () => {
  await createPdfHousingTypes(3506, [row('26A', 26.12), row('26A(고령자)', 26.12)], [], new AbortController().signal)
  expect(mocks.requestManagementApi.mock.calls.map(call => call[2])).toEqual([expect.objectContaining({ name: '26A', exclusiveArea: 26.12 })])
})

test('같은 면적이어도 공고문이 따로 적은 주택타입(55C-1·55D-1)은 따로 만든다', async () => {
  await createPdfHousingTypes(3508, [row('55C-1', 55.98), row('55D-1', 55.98)], [], new AbortController().signal)
  expect(mocks.requestManagementApi.mock.calls.map(call => call[2].name)).toEqual(['55C-1', '55D-1'])
})
