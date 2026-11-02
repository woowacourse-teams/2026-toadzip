import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { getMatchingHousingTypes } from './supplyMatchingApi'

vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))
describe('단지 주택형 조회 API 계약', () => {
  beforeEach(() => vi.resetAllMocks())
  it('선택한 단지의 주택형만 조회한다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([{ id: 1745, name: '26', exclusiveArea: 26.5195, supplyArea: 40.6486 }])
    expect(await getMatchingHousingTypes(556)).toHaveLength(1)
    expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/ingest/announcement-supply-matches/complexes/556/housing-types',
      'GET', undefined, undefined)
  })
  it('주택형 후보의 지금 기본 임대조건과 그 출처를 읽고, 모르는 출처는 거절한다', async () => {
    const type = { id: 7, name: '26', exclusiveArea: 26.37, supplyArea: null, basicDeposit: 8_100_000, basicMonthlyRent: 156_230, rentalConditionSource: 'ANNOUNCEMENT' }
    vi.mocked(requestManagementApi).mockResolvedValue([type])
    await expect(getMatchingHousingTypes(3)).resolves.toEqual([type])
    vi.mocked(requestManagementApi).mockResolvedValue([{ ...type, rentalConditionSource: 'LH' }])
    await expect(getMatchingHousingTypes(3)).rejects.toThrow('주택형 응답이 올바르지 않습니다.')
  })
})
