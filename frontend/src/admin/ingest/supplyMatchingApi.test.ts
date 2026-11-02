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
})
