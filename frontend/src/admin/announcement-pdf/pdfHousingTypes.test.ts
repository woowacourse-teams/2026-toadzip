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
  const row = { typeName: '059.6300A', values: { exclusiveArea: 59.63, supplyArea: 78.9091 } } as unknown as ExtractedRow
  await createPdfHousingTypes(3506, [row], [], new AbortController().signal)
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/3506/housing-types', 'POST',
    { version: 3, name: '059.6300A', exclusiveArea: 59.63, supplyArea: 78.9091, householdCount: null, fromAnnouncement: true },
    expect.any(AbortSignal))
})
